package com.trove.feature.cleaner

import com.trove.feature.cleaner.dedupe.DupeFile
import com.trove.feature.cleaner.dedupe.DuplicateRules
import com.trove.feature.cleaner.dedupe.DuplicateScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DedupeTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun write(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    // ---- the scanner on a real scratch tree ----------------------------------

    @Test
    fun `identical content groups regardless of location`() = runBlocking {
        val tmp = tempDir("trove-dupe-1")
        try {
            write(File(tmp, "a/one.dat"), "trove dupe payload 123")
            write(File(tmp, "b/deep/one.dat"), "trove dupe payload 123")

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            assertEquals(1, result.groups.size)
            val group = result.groups.first()
            assertEquals(2, group.files.size)
            assertEquals("trove dupe payload 123".length.toLong(), group.sizeBytes)
            assertEquals("trove dupe payload 123".length.toLong(), group.wastedBytes)
            val paths = group.files.map { it.path }.toSet()
            assertTrue(paths.contains(File(tmp, "a/one.dat").absolutePath))
            assertTrue(paths.contains(File(tmp, "b/deep/one.dat").absolutePath))
            assertFalse(result.truncated)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `byte-identical files with different names stay hidden unless name matching is off`() = runBlocking {
        val tmp = tempDir("trove-dupe-names")
        try {
            // The Kahon autobackup shape: date-stamped names, unchanged bytes.
            write(File(tmp, "backups/app_2026-11-20.tachibk"), "identical backup bytes")
            write(File(tmp, "backups/app_2026-11-21.tachibk"), "identical backup bytes")

            val byName = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp, matchByName = true)
            assertTrue(
                "metadata-differing files must not group by default: ${byName.groups}",
                byName.groups.isEmpty()
            )

            val byContent = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp, matchByName = false)
            assertEquals(1, byContent.groups.size)
            assertEquals(2, byContent.groups.first().files.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `name matching splits one hash group into per-name groups`() = runBlocking {
        val tmp = tempDir("trove-dupe-split")
        try {
            // Four byte-identical files, two names.
            write(File(tmp, "d1/x.dat"), "shared content")
            write(File(tmp, "d2/x.dat"), "shared content")
            write(File(tmp, "d1/y.dat"), "shared content")
            write(File(tmp, "d2/y.dat"), "shared content")

            val byName = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp, matchByName = true)
            assertEquals(2, byName.groups.size)
            assertTrue(byName.groups.all { it.files.size == 2 })

            val byContent = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp, matchByName = false)
            assertEquals(1, byContent.groups.size)
            assertEquals(4, byContent.groups.first().files.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `same size different content is not a duplicate`() = runBlocking {
        val tmp = tempDir("trove-dupe-2")
        try {
            write(File(tmp, "x.dat"), "hello world")
            write(File(tmp, "y.dat"), "hello worlD") // same length, last byte differs

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            assertTrue(
                "differing files must not group: ${result.groups}",
                result.groups.isEmpty()
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `different sizes are never compared`() = runBlocking {
        val tmp = tempDir("trove-dupe-3")
        try {
            write(File(tmp, "a.dat"), "short")
            write(File(tmp, "b.dat"), "shortbutlonger")

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            assertTrue(result.groups.isEmpty())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `protected trees and empty files are skipped`() = runBlocking {
        val tmp = tempDir("trove-dupe-4")
        try {
            write(File(tmp, "keep1.dat"), "payload")
            write(File(tmp, "Android/data/keep2.dat"), "payload") // protected tree
            write(File(tmp, "empty.dat"), "")                     // zero-byte files
            write(File(tmp, "empty2.dat"), "")                    // never group

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            assertEquals(0, result.groups.size)
            val scannedNames = File(tmp, ".").walk().count() // sanity: files exist
            assertTrue(scannedNames > 1)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `three identical copies form one group with two deletable`() = runBlocking {
        val tmp = tempDir("trove-dupe-5")
        try {
            val content = "same content across three files"
            write(File(tmp, "one/dup.dat"), content)
            write(File(tmp, "two/dup.dat"), content)
            write(File(tmp, "three/dup.dat"), content)

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            assertEquals(1, result.groups.size)
            assertEquals(3, result.groups.first().files.size)
            assertEquals(content.length * 2L, result.groups.first().wastedBytes)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `delete removes the selected copies and keeps the original`() = runBlocking {
        val tmp = tempDir("trove-dupe-6")
        try {
            val content = "delete me twice"
            write(File(tmp, "orig.dat"), content)
            write(File(tmp, "sub/orig.dat"), content)

            val result = DuplicateScanner(timeBudgetMs = 30_000).scan(tmp)
            val group = result.groups.first()
            val keep = File(tmp, "orig.dat").absolutePath
            val targets = group.files.filter { it.path != keep }
            assertEquals(1, targets.size)

            val removed = DuplicateScanner().delete(targets)
            assertEquals(1, removed)
            assertTrue(File(tmp, "orig.dat").exists())
            assertFalse(File(tmp, "sub/orig.dat").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ---- keep/delete rules (pure) ---------------------------------------------

    @Test
    fun `keep rule picks the oldest file`() {
        val files = listOf(
            DupeFile("/b/newer", 10, lastModified = 2_000L),
            DupeFile("/a/oldest", 10, lastModified = 1_000L),
            DupeFile("/c/newest", 10, lastModified = 3_000L)
        )
        assertEquals(1, DuplicateRules.keepIndex(files))
        assertEquals(setOf(0, 2), DuplicateRules.deleteIndexes(files))
    }

    @Test
    fun `keep rule breaks modification ties by path`() {
        val files = listOf(
            DupeFile("/z/twin", 10, lastModified = 1_000L),
            DupeFile("/a/twin", 10, lastModified = 1_000L)
        )
        assertEquals(1, DuplicateRules.keepIndex(files)) // /a/twin sorts first
    }
}
