package com.trove.feature.cleaner

import com.trove.feature.cleaner.systemcleaner.DefaultFilters
import com.trove.feature.cleaner.systemcleaner.FilterKind
import com.trove.feature.cleaner.systemcleaner.FilterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

class SystemCleanerTest {

    // ---- matching rules ------------------------------------------------------

    @Test
    fun `thumbnails match in thumbnail dirs and thumbdata files`() {
        assertTrue(FilterRules.matches(FilterKind.THUMBNAILS, "img.jpg", ".thumbnails"))
        assertTrue(FilterRules.matches(FilterKind.THUMBNAILS, "thumbdata3--1967290250", "DCIM"))
        assertTrue(FilterRules.matches(FilterKind.THUMBNAILS, ".thumbnails", "DCIM"))
        assertFalse(FilterRules.matches(FilterKind.THUMBNAILS, "img.jpg", "DCIM"))
    }

    @Test
    fun `log temp backup match by extension`() {
        assertTrue(FilterRules.matches(FilterKind.LOG_FILES, "crash.LOG", null))
        assertTrue(FilterRules.matches(FilterKind.LOG_FILES, "debug.log", null))
        assertFalse(FilterRules.matches(FilterKind.LOG_FILES, "log.txt", null))
        assertTrue(FilterRules.matches(FilterKind.TEMP_FILES, "download.tmp", null))
        assertTrue(FilterRules.matches(FilterKind.TEMP_FILES, "partial.TEMP", null))
        assertFalse(FilterRules.matches(FilterKind.TEMP_FILES, "template", null))
        assertTrue(FilterRules.matches(FilterKind.BACKUP_FILES, "notes.bak", null))
        assertFalse(FilterRules.matches(FilterKind.BACKUP_FILES, "backups.txt", null))
    }

    @Test
    fun `protected dirs are never descended`() {
        assertTrue(FilterRules.isProtectedDirName("Android"))
        assertTrue(FilterRules.isProtectedDirName(".TroveTrash"))
        assertTrue(FilterRules.isProtectedDirName("LOST.DIR"))
        assertFalse(FilterRules.isProtectedDirName("DCIM"))
    }

    // ---- filter set ----------------------------------------------------------

    @Test
    fun `default filters are complete and ids unique`() {
        val filters = DefaultFilters.all()
        assertEquals(
            filters.map { it.id }.size,
            filters.map { it.id }.distinct().size
        )
        assertEquals(
            setOf("thumbnails", "log_files", "temp_files", "backup_files", "empty_dirs", "dropbox", "tombstones", "anr"),
            filters.map { it.id }.toSet()
        )
        // Exactly the root ones carry a root path.
        filters.filter { it.requiresRoot }.forEach {
            assertTrue("Root filter ${it.id} must declare its path", it.rootPath != null)
        }
        filters.filter { !it.requiresRoot }.forEach {
            assertEquals("User filter ${it.id} must not declare a path", null, it.rootPath)
        }
    }

    // ---- the real walk on a scratch tree --------------------------------------

    private fun filter(id: String) = DefaultFilters.all().first { it.id == id }

    @Test
    fun `walk finds empties, protects trees and respects filters`() {
        val tmp = tempDir("trove-cleaner-test")
        try {
            // Empty chain: media/camera/2020 has nothing anywhere below.
            File(tmp, "media/camera/2020").mkdirs()
            // A keeper file the log/temp filters must not flag.
            File(tmp, "media/notes.txt").writeText("x")
            // A log file to find.
            File(tmp, "logs/old.log").let { File(tmp, "logs").mkdirs(); it.writeText("log") }
            // A thumbnails dir to find as a directory.
            File(tmp, "DCIM/.thumbnails").mkdirs()
            // A thumbdata file outside .thumbnails.
            File(tmp, "DCIM/thumbdata3-123").writeText("t")
            // Protected tree: never reported, and never makes its parent "empty".
            File(tmp, "Android/data/com.example").mkdirs()

            val scanner = com.trove.feature.cleaner.systemcleaner.SystemCleanerScanner(shell = null)

            val empties = scanner.scanUserStorage(filter("empty_dirs"), tmp)!!
            assertTrue(
                "deepest empty dir must be reported",
                empties.any { it.path == File(tmp, "media/camera/2020").absolutePath }
            )
            assertFalse(
                "media has a keeper child, so it is not empty",
                empties.any { it.path == File(tmp, "media").absolutePath }
            )
            assertFalse(
                "Android tree is protected",
                empties.any { it.path.startsWith(File(tmp, "Android").absolutePath) }
            )
            assertFalse(
                "the storage root itself is never a finding",
                empties.any { it.path == tmp.absolutePath }
            )

            val logs = scanner.scanUserStorage(filter("log_files"), tmp)!!
            assertEquals(listOf(File(tmp, "logs/old.log").absolutePath), logs.map { it.path })
            assertFalse("notes.txt must not be flagged", logs.any { it.path.contains("notes") })

            val thumbs = scanner.scanUserStorage(filter("thumbnails"), tmp)!!
            assertTrue(
                thumbs.any { it.path == File(tmp, "DCIM/.thumbnails").absolutePath }
            )
            assertTrue(
                thumbs.any { it.path == File(tmp, "DCIM/thumbdata3-123").absolutePath }
            )

            // The empty-dir walk never descended into Android: its contents
            // survive a scan untouched.
            assertTrue(File(tmp, "Android/data/com.example").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `empty dir walk does not count protected children as emptiness`() {
        val tmp = tempDir("trove-cleaner-test-2")
        try {
            // Parent has ONLY the protected child → still not empty by our rule.
            File(tmp, "only-android/Android").mkdirs()
            val scanner = com.trove.feature.cleaner.systemcleaner.SystemCleanerScanner(shell = null)
            val empties = scanner.scanUserStorage(filter("empty_dirs"), tmp)!!
            assertFalse(
                empties.any { it.path == File(tmp, "only-android").absolutePath }
            )
            assertTrue(
                empties.any { it.path == File(tmp, "only-android/Android").absolutePath } == false
            )
        } finally {
            tmp.deleteRecursively()
        }
    }
}
