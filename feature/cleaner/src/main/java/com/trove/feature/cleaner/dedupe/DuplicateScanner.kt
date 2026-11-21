package com.trove.feature.cleaner.dedupe

import com.trove.feature.cleaner.systemcleaner.FilterRules
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Deduplicator (clean-room behavior, after SD Maid SE's documented module):
 * find files with IDENTICAL content and delete the copies.
 *
 * Three-stage pipeline, cheapest test first:
 *   1. group by exact size (free),
 *   2. within a size group, hash the first 64 KiB (most differing same-size
 *      files fall out here without reading whole videos),
 *   3. full SHA-256 over the survivors (head hash doubles as full hash for
 *      files ≤ 64 KiB).
 *
 * Scope: the primary storage volume's user-visible tree — the same
 * protected-tree rules as the System Cleaner (Android/, .TroveTrash,
 * LOST.DIR are skipped). Root areas are deliberately NOT scanned: deleting
 * an app's "duplicate" private file can break the app.
 */
data class DupeFile(val path: String, val sizeBytes: Long, val lastModified: Long)

data class DuplicateGroup(val files: List<DupeFile>, val sizeBytes: Long) {
    /** Bytes reclaimable by keeping one copy. */
    val wastedBytes: Long get() = sizeBytes * (files.size - 1)
}

object DuplicateRules {

    /**
     * The copy to KEEP: the oldest file (the original), ties broken by path
     * for determinism. Unit-tested.
     */
    fun keepIndex(files: List<DupeFile>): Int {
        require(files.isNotEmpty()) { "empty group" }
        var best = 0
        for (i in 1 until files.size) {
            val candidate = files[i]
            val current = files[best]
            if (candidate.lastModified < current.lastModified ||
                (candidate.lastModified == current.lastModified && candidate.path < current.path)
            ) best = i
        }
        return best
    }

    /** Indexes suggested for deletion: everything except the kept original. */
    fun deleteIndexes(files: List<DupeFile>): Set<Int> =
        files.indices.toSet() - keepIndex(files)
}

class DuplicateScanner(
    /** Wall-clock budget for walk + hashing; trips → truncated result. */
    private val timeBudgetMs: Long = 150_000,
    private val maxFiles: Int = 80_000,
    private val headBytes: Int = 64 * 1024
) {

    data class ScanResult(
        val groups: List<DuplicateGroup>,
        val scannedFiles: Int,
        val notes: List<String>,
        val truncated: Boolean
    )

    private data class Candidate(val file: File, val size: Long, val headDigest: String)

    /**
     * [matchByName] = true (default) only groups files that are byte-identical
     * AND share the same file name. Content hashes can't see metadata: two
     * date-stamped app backups written on different days can be byte-identical
     * (nothing changed), but their names carry meaning — flagging them as
     * "duplicates" reads wrong to the user, so metadata breaks the tie.
     */
    suspend fun scan(root: File, matchByName: Boolean = true): ScanResult {
        val notes = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeBudgetMs
        var truncated = false

        // 1. Collect every regular file in the visible user tree.
        val files = ArrayList<File>()
        fun walk(dir: File) {
            if (truncated) return
            if (System.currentTimeMillis() > deadline) { truncated = true; return }
            if (files.size >= maxFiles) { truncated = true; return }
            val children = try { dir.listFiles() ?: return } catch (_: SecurityException) { return }
            for (child in children) {
                if (truncated) return
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    if (FilterRules.isProtectedDirName(child.name)) continue
                    walk(child)
                } else if (child.isFile && child.length() > 0) {
                    files += child
                }
            }
        }
        walk(root)

        if (truncated) notes.add("Scan budget reached — rerun for complete coverage")

        // 2. Only same-size files can be identical.
        val bySize = files.groupBy { it.length() }
            .filter { it.key > 0 }
            .filterValues { it.size >= 2 }

        // 3. Head-hash prefilter — survivors carry their head digest, which
        //    for small files already IS the full hash.
        val candidates = mutableListOf<Candidate>()
        for ((size, group) in bySize) {
            if (System.currentTimeMillis() > deadline) { truncated = true; break }
            val byHead = HashMap<String, MutableList<Candidate>>()
            for (f in group) {
                val digest = hashFile(f, size, headBytes) ?: continue
                byHead.getOrPut(digest) { mutableListOf() }.add(Candidate(f, size, digest))
            }
            for (sharing in byHead.values) {
                if (sharing.size >= 2) candidates.addAll(sharing)
            }
        }

        // 4. Full hash over the survivors.
        val full = HashMap<String, MutableList<Pair<File, Long>>>()
        for (c in candidates) {
            if (System.currentTimeMillis() > deadline) { truncated = true; break }
            val digest = if (c.size <= headBytes) c.headDigest
            else hashFile(c.file, c.size, Int.MAX_VALUE) ?: continue
            full.getOrPut(digest) { mutableListOf() }.add(c.file to c.size)
        }

        val groups = full.values
            .filter { it.size >= 2 }
            .flatMap { members ->
                // Byte-identical but differently NAMED files (date-stamped
                // backups, renamed copies) only group when the user opted
                // out of name matching.
                if (matchByName) members.groupBy { it.first.name }.values
                else listOf(members)
            }
            .filter { it.size >= 2 }
            .map { members ->
                DuplicateGroup(
                    files = members.map { (f, size) -> DupeFile(f.absolutePath, size, f.lastModified()) }
                        .sortedBy { it.lastModified },
                    sizeBytes = members.first().second
                )
            }
            .sortedByDescending { it.wastedBytes }

        return ScanResult(groups, files.size, notes, truncated)
    }

    /** Delete the given duplicates (plain files — no recursion needed). */
    suspend fun delete(files: List<DupeFile>): Int =
        files.count { File(it.path).delete() }

    // ---- internals -----------------------------------------------------------

    /**
     * Streamed SHA-256 of up to [limit] bytes of [file]; null on IO failure.
     * The caller bounds total work via the time budget, not per-file.
     */
    private fun hashFile(file: File, size: Long, limit: Int): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = minOf(size, limit.toLong())
                while (remaining > 0) {
                    val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, chunk)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                    remaining -= read
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
