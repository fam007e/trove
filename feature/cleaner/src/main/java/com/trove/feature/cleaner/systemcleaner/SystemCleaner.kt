package com.trove.feature.cleaner.systemcleaner

import android.content.Context
import android.os.Environment
import com.trove.feature.cleaner.core.PackageNameValidator
import com.trove.feature.cleaner.core.RootShell
import com.trove.feature.cleaner.core.ShellProtocol
import java.io.File

/**
 * System Cleaner (clean-room behavior): filter-based search for expendable
 * junk across well-known locations. Filters are explicit and toggleable —
 * nothing is deleted that the user hasn't previewed first.
 */
enum class FilterKind {
    EMPTY_DIRECTORIES,
    THUMBNAILS,
    LOG_FILES,
    TEMP_FILES,
    BACKUP_FILES,
    /** /data/system/dropbox — crash/ANR reports. Root only. */
    DROPBOX,
    /** /data/tombstones — native crash dumps. Root only. */
    TOMBSTONES,
    /** /data/anr — traces. Root only. */
    ANR
}

data class SystemFilter(
    val id: String,
    val label: String,
    val description: String,
    val kind: FilterKind,
    val requiresRoot: Boolean,
    /** Root filters sweep this exact directory. */
    val rootPath: String? = null
)

object DefaultFilters {

    /** The filter set; toggleable in the UI, previewed before any deletion. */
    fun all(): List<SystemFilter> = listOf(
        SystemFilter(
            "thumbnails", "Thumbnail caches",
            "Image preview caches (.thumbnails dirs, thumbdata files) that media apps rebuild on demand",
            FilterKind.THUMBNAILS, false
        ),
        SystemFilter(
            "log_files", "Log files",
            "Leftover *.log files from apps that forgot to clean up",
            FilterKind.LOG_FILES, false
        ),
        SystemFilter(
            "temp_files", "Temporary files",
            "Interrupted downloads and temp files (*.tmp, *.temp)",
            FilterKind.TEMP_FILES, false
        ),
        SystemFilter(
            "backup_files", "Backup leftovers",
            "Editor backups (*.bak) from incomplete saves",
            FilterKind.BACKUP_FILES, false
        ),
        SystemFilter(
            "empty_dirs", "Empty directories",
            "Directories left behind after files moved on (deepest-first, protected trees excluded)",
            FilterKind.EMPTY_DIRECTORIES, false
        ),
        SystemFilter(
            "dropbox", "Crash reports (root)",
            "/data/system/dropbox — historical app crash/ANR reports",
            FilterKind.DROPBOX, true, rootPath = "/data/system/dropbox"
        ),
        SystemFilter(
            "tombstones", "Tombstones (root)",
            "/data/tombstones — native crash dumps",
            FilterKind.TOMBSTONES, true, rootPath = "/data/tombstones"
        ),
        SystemFilter(
            "anr", "ANR traces (root)",
            "/data/anr — application-not-responding trace dumps",
            FilterKind.ANR, true, rootPath = "/data/anr"
        ),
    )
}

/** Pure matching rules — unit-tested, no filesystem dependency. */
object FilterRules {

    /** Trees never touched by the user-storage filters. */
    val PROTECTED_DIR_NAMES = setOf(
        "Android",       // app-owned data/obb/media — app cleaner's territory
        ".TroveTrash",   // Trove's own recycle bin
        "LOST.DIR",      // fsck recovery area — never gamble with it
    )

    fun isProtectedDirName(name: String): Boolean = name in PROTECTED_DIR_NAMES

    /** Does [fileName] in [parentDirName] match [kind]? */
    fun matches(kind: FilterKind, fileName: String, parentDirName: String?): Boolean = when (kind) {
        FilterKind.THUMBNAILS ->
            parentDirName == ".thumbnails" ||
                fileName.startsWith("thumbdata") ||
                fileName == ".thumbnails"
        FilterKind.LOG_FILES -> fileName.endsWith(".log", ignoreCase = true)
        FilterKind.TEMP_FILES ->
            fileName.endsWith(".tmp", ignoreCase = true) ||
                fileName.endsWith(".temp", ignoreCase = true)
        FilterKind.BACKUP_FILES -> fileName.endsWith(".bak", ignoreCase = true)
        else -> false
    }
}

data class JunkItem(val filter: SystemFilter, val path: String, val sizeBytes: Long, val isDirectory: Boolean)

class SystemCleanerScanner(private val shell: RootShell?) {

    data class ScanResult(val items: List<JunkItem>, val notes: List<String>)

    suspend fun scan(context: Context, enabledFilters: List<SystemFilter>): ScanResult {
        val notes = mutableListOf<String>()
        val items = mutableListOf<JunkItem>()

        val storageRoot = File(Environment.getExternalStorageDirectory().absolutePath)

        for (filter in enabledFilters) {
            if (filter.requiresRoot) {
                if (shell == null) {
                    notes.add("${filter.label}: needs root")
                    continue
                }
                scanRootFilter(filter)?.let { items.addAll(it) } ?: run {
                    notes.add("${filter.label}: unreadable via su")
                }
            } else {
                scanUserStorage(filter, storageRoot)?.let { items.addAll(it) } ?: run {
                    notes.add("${filter.label}: scan budget exceeded — rerun for the rest")
                }
            }
        }
        return ScanResult(items, notes)
    }

    suspend fun delete(items: List<JunkItem>): Int {
        var removed = 0
        for (item in items) {
            val ok = if (item.filter.requiresRoot) {
                val f = item.filter
                val base = f.rootPath?.let { it + "/" } ?: ""
                // Root filter entries are ls-derived names under a fixed dir —
                // validate the name so nothing weird can ride into rm.
                val name = item.path.removePrefix(base)
                if (f.rootPath == null || name.isEmpty() || name.contains('/')) continue
                val r = shell?.exec("rm -rf " + PackageNameValidator.shellQuote(item.path))
                r?.exitCode == 0
            } else {
                val file = File(item.path)
                file.deleteRecursively()
            }
            if (ok) removed++
        }
        return removed
    }

    // ---- internals -----------------------------------------------------------

    /**
     * Walk the primary storage volume applying one filter. Protected trees
     * are skipped entirely; .thumbnails dirs are visited (their contents are
     * the target). Returns null when the scan budget tripped.
     * Internal (not private) so the walk rules stay unit-testable on a
     * scratch directory tree.
     */
    internal fun scanUserStorage(filter: SystemFilter, storageRoot: File): List<JunkItem>? {
        val items = mutableListOf<JunkItem>()
        val deadline = System.currentTimeMillis() + 45_000
        var truncated = false

        fun onMatch(file: File, parentDirName: String?) {
            if (filter.kind == FilterKind.EMPTY_DIRECTORIES) return
            if (!FilterRules.matches(filter.kind, file.name, parentDirName)) return
            items += JunkItem(filter, file.absolutePath, if (file.isDirectory) 0L else file.length(), file.isDirectory)
        }

        fun walk(dir: File, parentDirName: String?) {
            if (truncated) return
            if (System.currentTimeMillis() > deadline) { truncated = true; return }
            val children = try {
                dir.listFiles() ?: return
            } catch (_: SecurityException) { return }

            var hasVisibleChild = false
            for (child in children) {
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                hasVisibleChild = true

                if (child.isDirectory) {
                    if (FilterRules.isProtectedDirName(child.name)) continue
                    if (FilterRules.matches(filter.kind, child.name, dir.name)) {
                        // A .thumbnails directory itself: report it, don't descend.
                        items += JunkItem(filter, child.absolutePath, 0L, true)
                        continue
                    }
                    walk(child, dir.name)
                    if (truncated) return
                } else {
                    onMatch(child, dir.name)
                }
            }
            if (filter.kind == FilterKind.EMPTY_DIRECTORIES &&
                hasVisibleChild == false && dir != storageRoot
            ) {
                items += JunkItem(filter, dir.absolutePath, 0L, true)
            }
        }

        walk(storageRoot, null)
        return if (truncated) null else items
    }

    private suspend fun scanRootFilter(filter: SystemFilter): List<JunkItem>? {
        val path = filter.rootPath ?: return null
        // One round trip: for every direct child, "size name" via stat.
        val result = shell?.exec(
            "for f in " + PackageNameValidator.shellQuote(path) + "/*; do " +
                "[ -f \"\$f\" ] && stat -c \"%s %n\" \"\$f\" 2>/dev/null; done"
        ) ?: return null
        if (result.exitCode != 0) return null

        val items = mutableListOf<JunkItem>()
        for (line in result.stdout.lineSequence().filter { it.isNotBlank() }) {
            val parsed = ShellProtocol.parseDuLine(line) ?: continue
            val (size, filePath) = parsed
            val name = filePath.substringAfterLast('/')
            // Only conservative report-file names are accepted; anything else
            // in a system dir is left strictly alone.
            if (!Regex("^[A-Za-z0-9@._,+-]+$").matches(name)) continue
            if (filePath != "$path/$name") continue // e.g. spaces smuggled through
            items += JunkItem(filter, filePath, size, false)
        }
        return items
    }
}
