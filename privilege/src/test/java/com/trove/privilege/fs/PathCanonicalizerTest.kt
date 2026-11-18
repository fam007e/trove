package com.trove.privilege.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the privileged service's path canonicalization — the
 * "Absolute Pathing" defense against traversal attacks in privileged IPC.
 *
 * Note: the UID enforcement itself (Binder.getCallingUid) is not testable on
 * the JVM; it requires an on-device instrumentation test.
 */
class PathCanonicalizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `collapses dot-dot segments for existing paths`() {
        val dir = tmp.newFolder("real")
        val file = File(dir, "target.txt").apply { writeText("x") }

        val sneaky = file.absolutePath + "/../target.txt"
        assertEquals(file.canonicalPath, PathCanonicalizer.canonical(sneaky))
    }

    @Test
    fun `resolves through symlinks to the real target`() {
        val dir = tmp.newFolder("data")
        val file = File(dir, "secret.txt").apply { writeText("content") }
        val link = File(tmp.root, "innocent-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())

        val resolved = PathCanonicalizer.canonical(link.absolutePath)
        assertEquals(file.canonicalPath, resolved)
    }

    @Test
    fun `never returns a relative path`() {
        val result = PathCanonicalizer.canonical("relative/path/../thing")
        assertTrue("must be absolute: $result", result.startsWith(File.separator))
    }

    @Test
    fun `root traversal stays at filesystem root`() {
        assertEquals(
            File("/").canonicalPath,
            PathCanonicalizer.canonical("/../../../../..")
        )
    }

    @Test
    fun `nonexistent paths still normalize deterministically`() {
        val base = tmp.root.absolutePath
        val result = PathCanonicalizer.canonical(base + "/missing/../present")
        assertEquals(base + "/present", result)
    }
}
