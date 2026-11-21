package com.trove.feature.cleaner.core

import java.io.File

/**
 * Bounded filesystem walk helpers shared by the Cleaner engines. Every walk
 * carries a file-count cap and a wall-clock deadline — storage roots can be
 * enormous and a cleaner that never finishes is worse than none.
 */
object FsWalk {

    /**
     * Recursive size of [dir] in bytes. Returns -1 if the walk hit its cap
     * (size unknown, better than a lie). Symlinks are not followed.
     */
    fun sizeOfDir(dir: File, maxFiles: Int = 200_000, deadlineMs: Long = 60_000): Long {
        val deadline = System.currentTimeMillis() + deadlineMs
        var total = 0L
        var files = 0
        var truncated = false

        fun walk(f: File) {
            if (truncated) return
            if (System.currentTimeMillis() > deadline || ++files > maxFiles) {
                truncated = true
                return
            }
            if (!f.isDirectory) {
                if (f.isFile) total += f.length()
                return
            }
            val children = try {
                f.listFiles() ?: return
            } catch (_: SecurityException) {
                return
            }
            for (child in children) {
                // Don't follow symlinks into other trees.
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                walk(child)
                if (truncated) return
            }
        }

        walk(dir)
        return if (truncated) -1L else total
    }

    /**
     * Delete the CONTENTS of [dir], keeping the directory itself — the shape
     * an app's cache directory must keep. Returns bytes reclaimed (best
     * effort estimate from sizes collected before deletion).
     */
    fun clearDirContents(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val children = dir.listFiles() ?: return 0L
        var freed = 0L
        for (child in children) {
            if (child.isDirectory) freed += sizeOfDir(child, deadlineMs = 30_000).coerceAtLeast(0)
            else freed += child.length()
            child.deleteRecursively()
        }
        return freed
    }
}
