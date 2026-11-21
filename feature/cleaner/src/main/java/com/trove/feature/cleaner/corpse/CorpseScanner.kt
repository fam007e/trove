package com.trove.feature.cleaner.corpse

import android.content.Context
import android.os.Environment
import com.trove.feature.cleaner.core.FsWalk
import com.trove.feature.cleaner.core.PackageNameValidator
import com.trove.feature.cleaner.core.RootShell
import com.trove.feature.cleaner.core.ShellProtocol
import java.io.File

/**
 * Corpse Finder (clean-room behavior, after SD Maid SE's documented module):
 * leftover data from apps that are no longer installed.
 *
 * Candidate areas are the well-known per-app homes. Root-requiring areas
 * (/data/data, /data/app-lib) are enumerated through [RootShell]. External
 * app areas go through java.io on normal devices, but on FUSE-restricted
 * builds (GSIs) Android/data is hidden from apps even with all-files access
 * — there the scan routes through the real backing store /data/media/0
 * instead, which only root can reach. User 0 only.
 */
data class CorpseArea(
    val id: String,
    val label: String,
    val rootPath: String,
    val requiresRoot: Boolean
)

object CorpseAreas {

    /** Enumerate the scan areas for a storage root (e.g. /storage/emulated/0). */
    fun areas(storageRoot: File): List<CorpseArea> = listOf(
        CorpseArea("data_data", "Private app data (/data/data)", "/data/data", true),
        CorpseArea("app_lib", "Native app libraries (/data/app-lib)", "/data/app-lib", true),
        CorpseArea(
            "android_data", "External app data (Android/data)",
            File(storageRoot, "Android/data").absolutePath, false
        ),
        CorpseArea(
            "android_obb", "Expansion files (Android/obb)",
            File(storageRoot, "Android/obb").absolutePath, false
        ),
        CorpseArea(
            "android_media", "External app media (Android/media)",
            File(storageRoot, "Android/media").absolutePath, false
        ),
    )

    /**
     * The path an area is actually read through: root areas and FUSE-blocked
     * external areas use the /data/media/0 backing store when root is
     * available (user 0), everything else keeps its FUSE path.
     */
    fun accessPath(area: CorpseArea, storageRootPath: String, viaRoot: Boolean): String {
        if (area.requiresRoot || !viaRoot) return area.rootPath
        if (area.rootPath.startsWith(storageRootPath)) {
            return "/data/media/0" + area.rootPath.removePrefix(storageRootPath)
        }
        return area.rootPath
    }
}

data class Corpse(val area: CorpseArea, val name: String, val sizeBytes: Long, val path: String)

class CorpseScanner(private val shell: RootShell?) {

    data class ScanResult(
        val corpses: List<Corpse>,
        val installedCount: Int,
        /** Human-readable problems per area (e.g. root needed, path missing). */
        val notes: List<String>
    )

    /** Pure classification — the heart of the finder, unit-tested. */
    fun isCorpseCandidate(entryName: String, installedPackages: Set<String>): Boolean =
        PackageNameValidator.isValid(entryName) && entryName !in installedPackages

    suspend fun scan(context: Context, includeRootAreas: Boolean): ScanResult {
        val pm = context.packageManager
        val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet() +
            setOf("android", context.packageName)
        val notes = mutableListOf<String>()
        val corpses = mutableListOf<Corpse>()

        val storageRoot = File(Environment.getExternalStorageDirectory().absolutePath)
        val viaRoot = shell != null

        for (area in CorpseAreas.areas(storageRoot)) {
            if (area.requiresRoot && (!includeRootAreas || shell == null)) {
                if (includeRootAreas) notes.add("${area.label}: needs root")
                continue
            }
            val accessPath = CorpseAreas.accessPath(area, storageRoot.absolutePath, viaRoot)
            val entries = listEntries(accessPath, needsSu = area.requiresRoot || viaRoot)
            for (name in entries) {
                if (!isCorpseCandidate(name, installed)) continue
                corpses += Corpse(area, name, sizeOf(accessPath, name, needsSu = area.requiresRoot || viaRoot), "$accessPath/$name")
            }
        }
        return ScanResult(corpses, installed.size, notes)
    }

    /**
     * Delete [corpses]. [installedNow] is re-queried before deleting so an
     * app installed between scan and sweep is never nuked. Returns the number
     * of corpses actually removed.
     */
    suspend fun delete(corpses: List<Corpse>, installedNow: Set<String>): Int {
        var removed = 0
        for (corpse in corpses) {
            if (!PackageNameValidator.isValid(corpse.name)) continue
            if (corpse.name in installedNow) continue // raced into installation — skip
            val ok = if (corpse.area.requiresRoot || corpse.path.startsWith("/data/media/0")) {
                shell?.exec("rm -rf " + PackageNameValidator.shellQuote(corpse.path))?.exitCode == 0
            } else {
                File(corpse.path).deleteRecursively()
            }
            if (ok) removed++
        }
        return removed
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun listEntries(path: String, needsSu: Boolean): List<String> =
        if (needsSu) {
            val result = shell?.exec("ls -1 " + PackageNameValidator.shellQuote(path))
                ?: return emptyList()
            if (result.exitCode != 0) return emptyList()
            result.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        } else {
            File(path).list()?.toList() ?: emptyList()
        }

    private suspend fun sizeOf(path: String, name: String, needsSu: Boolean): Long {
        val target = "$path/$name"
        return if (needsSu) {
            val result = shell?.exec("du -sk " + PackageNameValidator.shellQuote(target)) ?: return -1L
            val line = result.stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: return -1L
            ShellProtocol.parseDuLine(line)?.first?.times(1024L) ?: -1L
        } else {
            FsWalk.sizeOfDir(File(target), deadlineMs = 30_000)
        }
    }
}
