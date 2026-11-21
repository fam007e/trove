package com.trove.feature.cleaner

import com.trove.feature.cleaner.corpse.CorpseAreas
import com.trove.feature.cleaner.corpse.CorpseScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorpseScannerTest {

    private val scanner = CorpseScanner(shell = null)

    private val installed = setOf(
        "com.android.systemui", "com.example.installed", "android", "com.trove.app"
    )

    @Test
    fun `uninstalled valid package dirs are corpse candidates`() {
        assertTrue(scanner.isCorpseCandidate("com.example.gone", installed))
        assertTrue(scanner.isCorpseCandidate("org.leftover.app", installed))
    }

    @Test
    fun `installed packages are never corpses`() {
        assertFalse(scanner.isCorpseCandidate("com.example.installed", installed))
        assertFalse(scanner.isCorpseCandidate("android", installed))
        assertFalse(scanner.isCorpseCandidate("com.trove.app", installed))
    }

    @Test
    fun `non-package directory names are skipped outright`() {
        // Any name that couldn't be a package is ignored even when unknown:
        assertFalse(scanner.isCorpseCandidate(".cache", installed))
        assertFalse(scanner.isCorpseCandidate("lost+found", installed))
        assertFalse(scanner.isCorpseCandidate("..", installed))
        assertFalse(scanner.isCorpseCandidate("com.foo;rm -rf /", installed))
        assertFalse(scanner.isCorpseCandidate("", installed))
    }

    @Test
    fun `areas cover private and external homes`() {
        val areas = CorpseAreas.areas(File("/storage/emulated/0"))
        assertEquals("/data/data", areas.first { it.id == "data_data" }.rootPath)
        assertEquals("/data/app-lib", areas.first { it.id == "app_lib" }.rootPath)
        assertEquals("/storage/emulated/0/Android/data", areas.first { it.id == "android_data" }.rootPath)
        assertEquals("/storage/emulated/0/Android/obb", areas.first { it.id == "android_obb" }.rootPath)
        assertEquals("/storage/emulated/0/Android/media", areas.first { it.id == "android_media" }.rootPath)

        // Exactly the private ones need root.
        assertEquals(
            listOf(true, true, false, false, false),
            areas.map { it.requiresRoot }
        )
    }
}
