package com.trove.feature.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFileItemTest {

    @Test
    fun testArchiveTypeDetection() {
        assertEquals(ArchiveType.ZIP, ArchiveType.fromFileName("backup.zip"))
        assertEquals(ArchiveType.ZIP, ArchiveType.fromFileName("app.apk"))
        assertEquals(ArchiveType.SEVEN_Z, ArchiveType.fromFileName("data.7z"))
        assertEquals(ArchiveType.TAR_GZ, ArchiveType.fromFileName("archive.tar.gz"))
        assertEquals(ArchiveType.TAR_GZ, ArchiveType.fromFileName("archive.tgz"))
        assertEquals(ArchiveType.TAR_BZ2, ArchiveType.fromFileName("archive.tar.bz2"))
        assertEquals(ArchiveType.TAR_XZ, ArchiveType.fromFileName("archive.tar.xz"))
        assertEquals(ArchiveType.RAR, ArchiveType.fromFileName("files.rar"))
        assertNull(ArchiveType.fromFileName("document.pdf"))
    }

    @Test
    fun testArchiveEntryNodeTreeBuilding() {
        val root = ArchiveEntryNode.createRoot()

        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = "folder1/subfolder2/file.txt",
            isDirectory = false,
            size = 1024L,
            lastModified = 1700000000L
        )

        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = "folder1/image.png",
            isDirectory = false,
            size = 2048L,
            lastModified = 1700000000L
        )

        // Verify root children
        val folder1 = root.findChild("folder1")
        assertNotNull(folder1)
        assertTrue(folder1!!.isDirectory)
        assertEquals(root, folder1.parent)

        // Verify folder1 children
        val subfolder2 = folder1.findChild("subfolder2")
        assertNotNull(subfolder2)
        assertTrue(subfolder2!!.isDirectory)
        assertEquals(folder1, subfolder2.parent)

        val image = folder1.findChild("image.png")
        assertNotNull(image)
        assertFalse(image!!.isDirectory)
        assertEquals(2048L, image.size)

        // Verify subfolder2 children
        val file = subfolder2.findChild("file.txt")
        assertNotNull(file)
        assertFalse(file!!.isDirectory)
        assertEquals(1024L, file.size)
        assertEquals("folder1/subfolder2/file.txt", file.entryPath)
    }

    @Test
    fun testTrailingSlashAndBackslashNormalization() {
        val root = ArchiveEntryNode.createRoot()

        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = "windows\\style\\path\\file.dat",
            isDirectory = false,
            size = 500L,
            lastModified = 0L
        )

        val windowsNode = root.findChild("windows")
        assertNotNull(windowsNode)
        val styleNode = windowsNode!!.findChild("style")
        assertNotNull(styleNode)
        val pathNode = styleNode!!.findChild("path")
        assertNotNull(pathNode)
        val fileNode = pathNode!!.findChild("file.dat")
        assertNotNull(fileNode)
        assertEquals("windows/style/path/file.dat", fileNode.entryPath)
    }
}
