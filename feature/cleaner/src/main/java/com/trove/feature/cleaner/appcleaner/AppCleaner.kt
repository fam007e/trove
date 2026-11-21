package com.trove.feature.cleaner.appcleaner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import com.trove.feature.cleaner.core.FsWalk
import com.trove.feature.cleaner.core.PackageNameValidator
import com.trove.feature.cleaner.core.RootShell
import com.trove.feature.cleaner.core.ShellProtocol
import java.io.File

/**
 * App Cleaner (clean-room behavior): deletes expendable files — caches —
 * of installed apps.
 *
 * Two access modes:
 *  - Root: everything goes through su. Public caches are read and cleared at
 *    the real backing store /data/media/0 (FUSE views of Android/data are
 *    empty or blocked on many builds, GSIs especially), private caches at
 *    /data/data.
 *  - No root: only public caches, via java.io with "All files access"; the
 *    private caches stay unknown and the accessibility automation is the
 *    fallback (see the automation package).
 */
data class AppCacheTarget(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** Public Android/data cache + code_cache bytes; -1 = unknown. */
    val publicCacheBytes: Long,
    /** Private /data/data cache + code_cache bytes; -1 = no root / unreadable. */
    val internalCacheBytes: Long
) {
    val totalKnownBytes: Long get() = publicCacheBytes.coerceAtLeast(0) + internalCacheBytes.coerceAtLeast(0)
}

class AppCleaner(private val context: Context, private val shell: RootShell?) {

    data class ScanResult(val targets: List<AppCacheTarget>, val notes: List<String>)

    data class CleanResult(val cleaned: Int, val bytesFreed: Long, val failed: List<String>)

    private val extRoot = File(Environment.getExternalStorageDirectory().absolutePath)

    /** FUSE-visible public app dir (non-root access path). */
    private fun publicAppDir(pkg: String) = File(File(extRoot, "Android/data"), pkg)

    /** Real backing store of the public app dir (root access path, user 0). */
    private fun mediaAppDir(pkg: String) = "/data/media/0/Android/data/$pkg"

    private fun privateCacheDir(pkg: String) = "/data/data/$pkg/cache"
    private fun privateCodeCacheDir(pkg: String) = "/data/data/$pkg/code_cache"

    suspend fun scan(includeSystemApps: Boolean): ScanResult {
        val notes = mutableListOf<String>()
        val targets = mutableListOf<AppCacheTarget>()

        val viaRoot = shell != null
        var internalSizes: Map<String, Long> = emptyMap()
        var publicSizes: Map<String, Long> = emptyMap()

        if (viaRoot) {
            try {
                internalSizes = batchDu(
                    "/data/data/*/cache /data/data/*/code_cache"
                ) { path -> path.removePrefix("/data/data/").substringBefore('/') }
                publicSizes = batchDu(
                    "/data/media/0/Android/data/*/cache /data/media/0/Android/data/*/code_cache"
                ) { path ->
                    path.removePrefix("/data/media/0/Android/data/").substringBefore('/')
                }
            } catch (e: Exception) {
                notes.add("Cache size scan failed: ${e.message}")
            }
        } else {
            notes.add("No root — private caches need the accessibility automation")
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledPackages(0) // List<PackageInfo>
        val ownPkg = context.packageName

        for (app in apps) {
            if (app.packageName == ownPkg) continue
            val ai = app.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystemApps) continue

            val pkg = app.packageName
            val publicBytes = if (viaRoot) {
                publicSizes[pkg] ?: 0L
            } else {
                publicSize(pkg)
            }
            val internalBytes = if (viaRoot) internalSizes[pkg] ?: 0L else -1L

            // Nothing to clean: don't pad the list with zero rows.
            if (publicBytes <= 0 && internalBytes <= 0) continue

            targets += AppCacheTarget(
                packageName = pkg,
                label = ai.loadLabel(pm).toString(),
                isSystem = isSystem,
                publicCacheBytes = publicBytes,
                internalCacheBytes = internalBytes
            )
        }
        return ScanResult(targets.sortedByDescending { it.totalKnownBytes }, notes)
    }

    /**
     * Clean the selected targets. With root, public and private caches are
     * swept through su; without it only the FUSE-visible public caches.
     */
    suspend fun clean(targets: List<AppCacheTarget>): CleanResult {
        var cleaned = 0
        var freed = 0L
        val failed = mutableListOf<String>()

        for (target in targets) {
            var ok = true
            val quoted = PackageNameValidator::shellQuote

            if (shell != null) {
                // Guard each dir with [ -d ]: a missing code_cache must not
                // fail the whole sweep (find exits 1 on ENOENT), while real
                // su failures still surface through the root timeout/died
                // exceptions.
                val publicResult = try {
                    val base = quoted(mediaAppDir(target.packageName))
                    shell.exec(
                        "for d in cache code_cache; do p=$base/\$d; [ -d \"\$p\" ] && " +
                            "find \"\$p\" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null; done; true"
                    )
                } catch (e: Exception) {
                    ok = false
                    failed.add("${target.packageName} (public): ${e.message}")
                    null
                }
                if (publicResult != null && publicResult.exitCode != 0) {
                    ok = false
                    failed.add("${target.packageName} (public): su exit ${publicResult.exitCode}")
                }

                val privateResult = try {
                    val base = quoted(privateCacheDir(target.packageName))
                    shell.exec(
                        "for d in cache code_cache; do p=$base/\$d; [ -d \"\$p\" ] && " +
                            "find \"\$p\" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null; done; true"
                    )
                } catch (e: Exception) {
                    ok = false
                    failed.add("${target.packageName} (private): ${e.message}")
                    null
                }
                if (privateResult != null && privateResult.exitCode != 0) {
                    ok = false
                    failed.add("${target.packageName} (private): su exit ${privateResult.exitCode}")
                }

                freed += target.totalKnownBytes
            } else {
                try {
                    File(publicAppDir(target.packageName), "cache").let {
                        if (it.isDirectory) freed += FsWalk.clearDirContents(it)
                    }
                    File(publicAppDir(target.packageName), "code_cache").let {
                        if (it.isDirectory) freed += FsWalk.clearDirContents(it)
                    }
                } catch (e: Exception) {
                    ok = false
                    failed.add("${target.packageName} (public): ${e.message}")
                }
            }
            if (ok) cleaned++
        }
        return CleanResult(cleaned, freed, failed)
    }

    // ---- internals -----------------------------------------------------------

    /**
     * One round trip for a whole glob family: shell loop feeding `du -sk`,
     * parsed into package → bytes (cache + code_cache summed).
     */
    private suspend fun batchDu(globs: String, keyOf: (String) -> String): Map<String, Long> {
        val sh = shell ?: return emptyMap()
        val result = sh.exec(
            "for d in $globs; do " +
                "[ -d \"\$d\" ] && du -sk \"\$d\" 2>/dev/null; done"
        )
        val byPkg = HashMap<String, Long>()
        for ((path, bytes) in ShellProtocol.parseDuListing(result.stdout)) {
            val pkg = keyOf(path)
            if (pkg.isNotEmpty()) byPkg[pkg] = (byPkg[pkg] ?: 0L) + bytes
        }
        return byPkg
    }

    /** java.io walk of the FUSE-visible public caches (non-root path). */
    private fun publicSize(pkg: String): Long {
        if (!PackageNameValidator.isValid(pkg)) return 0L
        var total = 0L
        for (name in listOf("cache", "code_cache")) {
            val size = FsWalk.sizeOfDir(File(publicAppDir(pkg), name), deadlineMs = 15_000)
            if (size > 0) total += size
        }
        return total
    }
}
