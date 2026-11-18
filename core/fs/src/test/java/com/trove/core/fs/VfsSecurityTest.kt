package com.trove.core.fs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * CVE & Security Test: VFS Path Traversal & Injection Defenses.
 *
 * Verifies that the VFS navigation layer rejects:
 * 1. Null-byte injection (\0) used to truncate paths in native layers (CWE-626).
 * 2. Directory traversal past jailRoot bounds (CWE-22).
 * 3. Deeply nested parent traversal above root.
 */
class VfsSecurityTest {

    private class SecurityMockItem(
        override val name: String,
        override val isDirectory: Boolean,
        private val children: Map<String, FileItem> = emptyMap(),
        private val parent: FileItem? = null
    ) : FileItem {
        override val path: String = parent?.let { "${it.path}/$name".replace("//", "/") } ?: "/$name"
        override val size: Long = 0L
        override val lastModified: Long = 0L
        override val permissions: PosixPermissions = PosixPermissions.DEFAULT
        override val mimeType: String = if (isDirectory) "inode/directory" else "text/plain"

        override suspend fun listFiles(): Result<List<FileItem>> = Result.Success(children.values.toList())
        override suspend fun openInputStream(): Result<InputStream> = Result.Failure(UnsupportedOperationException())
        override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = Result.Failure(UnsupportedOperationException())
        override suspend fun createFile(name: String): Result<FileItem> = Result.Failure(UnsupportedOperationException())
        override suspend fun createDirectory(name: String): Result<FileItem> = Result.Failure(UnsupportedOperationException())
        override suspend fun delete(): Result<Unit> = Result.Failure(UnsupportedOperationException())
        override suspend fun rename(newName: String): Result<FileItem> = Result.Failure(UnsupportedOperationException())
        override suspend fun copyTo(dest: FileItem): Result<FileItem> = Result.Failure(UnsupportedOperationException())
        override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = Result.Failure(UnsupportedOperationException())
        override suspend fun exists(): Boolean = true

        override suspend fun findChild(name: String): Result<FileItem> {
            val child = children[name]
            return if (child != null) Result.Success(child) else Result.Failure(Exception("Not found: $name"))
        }

        override suspend fun getParent(): Result<FileItem?> = Result.Success(parent)
    }

    @Test
    fun testNullByteInjectionRejected() = runBlocking {
        val root = SecurityMockItem("root", true)

        val maliciousPath = "secret.txt\u0000.png"
        val result = root.resolve(maliciousPath)

        assertTrue("Null byte injection must be rejected", result is Result.Failure)
        assertTrue(
            "Failure message must explicitly identify null byte injection",
            (result as Result.Failure).exception.message?.contains("Null byte") == true
        )
    }

    @Test
    fun testJailRootTraversalBlocked() = runBlocking {
        val parentOfJail = SecurityMockItem("system", true)
        val jailRoot = SecurityMockItem("jail", true, parent = parentOfJail)
        val child = SecurityMockItem("child", true, parent = jailRoot)

        // Attempting to resolve '../../system' from child through jailRoot
        val result = child.resolve("../../system", jailRoot = jailRoot)

        // Must either fail or stay clamped inside jailRoot
        if (result is Result.Success) {
            val resolved = result.value
            assertTrue(
                "Resolved item must remain inside jail root: ${resolved.path}",
                resolved.path.startsWith(jailRoot.path)
            )
        } else {
            assertTrue("Traversal past jail root failed cleanly", true)
        }
    }

    @Test
    fun testDeeplyNestedParentTraversalAboveRoot() = runBlocking {
        val root = SecurityMockItem("root", true, parent = null)

        val traversal = "../../../../../../../../../etc/passwd"
        val result = root.resolve(traversal, jailRoot = root)

        if (result is Result.Success) {
            // Must stay clamped at root
            assertTrue(result.value.path == root.path)
        } else {
            assertTrue(result is Result.Failure)
        }
    }
}
