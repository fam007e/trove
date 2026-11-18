package com.trove.feature.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CVE Security Test: Zip Slip Vulnerability (CVE-2018-1002201).
 *
 * Verifies that ArchiveEntryNode and ArchiveExtractor reject, sanitize,
 * or neutralize malicious directory traversal sequences in archive entry paths,
 * preventing arbitrary file overwrite outside the destination directory.
 */
class ZipSlipSecurityTest {

    @Test
    fun testZipSlipTraversalStripped() {
        val root = ArchiveEntryNode.createRoot()

        // Malicious entry attempting to escape to /etc/shadow
        val maliciousPath = "../../../../etc/shadow"
        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = maliciousPath,
            isDirectory = false,
            size = 100L,
            lastModified = System.currentTimeMillis()
        )

        // Traversal tokens '..' must be stripped, creating a safe relative subpath
        assertFalse("Root must not have '..' child", root.children.containsKey(".."))
        val etcNode = root.findChild("etc")
        assertTrue("Subfolder 'etc' must be safely rooted", etcNode != null)
        val shadowNode = etcNode?.findChild("shadow")
        assertTrue("File must be safely sandboxed under /etc/shadow within archive root", shadowNode != null)
        assertEquals("Safe entry path must not contain '..'", "etc/shadow", shadowNode?.entryPath)
    }

    @Test
    fun testWindowsStyleBackslashTraversalStripped() {
        val root = ArchiveEntryNode.createRoot()

        val windowsTraversal = "..\\..\\..\\Windows\\System32\\cmd.exe"
        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = windowsTraversal,
            isDirectory = false,
            size = 200L,
            lastModified = System.currentTimeMillis()
        )

        assertFalse("Backslash traversal must not escape root", root.children.containsKey(".."))
        val winNode = root.findChild("Windows")
        assertTrue("Windows folder must be safely rooted", winNode != null)
    }

    @Test
    fun testPureTraversalEntriesNeutralized() {
        val root = ArchiveEntryNode.createRoot()

        // Pure traversal attempts
        ArchiveEntryNode.insertPath(root, "..", isDirectory = true, size = 0L, lastModified = 0L)
        ArchiveEntryNode.insertPath(root, "../..", isDirectory = true, size = 0L, lastModified = 0L)
        ArchiveEntryNode.insertPath(root, ".", isDirectory = true, size = 0L, lastModified = 0L)

        // Children map must remain empty
        assertTrue("Traversal-only entries must produce no child nodes", root.children.isEmpty())
    }

    @Test
    fun testMixedTraversalInMiddleOfPath() {
        val root = ArchiveEntryNode.createRoot()

        val mixedPath = "documents/confidential/../../evil.sh"
        ArchiveEntryNode.insertPath(
            root = root,
            rawPath = mixedPath,
            isDirectory = false,
            size = 50L,
            lastModified = 0L
        )

        val docs = root.findChild("documents")
        assertTrue(docs != null)
        val conf = docs?.findChild("confidential")
        assertTrue(conf != null)
        val evil = conf?.findChild("evil.sh")
        assertTrue(evil != null)
        assertEquals("documents/confidential/evil.sh", evil?.entryPath)
    }
}
