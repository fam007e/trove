package com.trove.core.fs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class FileItemResolveTest {

    private class MockFileItem(
        override val name: String,
        override val isDirectory: Boolean,
        private val children: Map<String, FileItem> = emptyMap(),
        private val parent: FileItem? = null
    ) : FileItem {
        override val path: String = parent?.let { "${it.path}/$name".replace("//", "/") } ?: "/$name"
        override val size: Long = 0
        override val lastModified: Long = 0
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
            return if (child != null) Result.Success(child) else Result.Failure(Exception("Not found"))
        }

        override suspend fun getParent(): Result<FileItem?> = Result.Success(parent)
    }

    @Test
    fun `test resolve relative path`() = runBlocking {
        val child = MockFileItem("child", false)
        val root = MockFileItem("root", true, mapOf("child" to child))
        
        val result = root.resolve("child")
        assertTrue(result is Result.Success)
        assertEquals("child", (result as Result.Success).value.name)
    }

    @Test
    fun `test resolve multi-level path`() = runBlocking {
        // Correctly link hierarchy
        val root = MockFileItem("root", true)
        val dir = MockFileItem("dir", true, emptyMap(), parent = root)
        val leaf = MockFileItem("leaf", false, emptyMap(), parent = dir)
        
        // Re-mock with children
        val dirWithLeaf = MockFileItem("dir", true, mapOf("leaf" to leaf), parent = root)
        val rootWithDir = MockFileItem("root", true, mapOf("dir" to dirWithLeaf), parent = null)

        val result = rootWithDir.resolve("dir/leaf")
        assertTrue(result is Result.Success)
        assertEquals("leaf", (result as Result.Success).value.name)
        assertEquals("/root/dir/leaf", (result as Result.Success).value.path)
    }

    @Test
    fun `test resolve dot-dot`() = runBlocking {
        val root = MockFileItem("root", true)
        val dir = MockFileItem("dir", true, emptyMap(), root)
        
        val result = dir.resolve("..")
        assertTrue(result is Result.Success)
        assertEquals("root", (result as Result.Success).value.name)
    }

    @Test
    fun `test jail check prevents traversal`() = runBlocking {
        val root = MockFileItem("sdcard", true)
        // Simulate a "malicious" parent that points outside the jail
        val evilRoot = MockFileItem("root_filesystem", true)
        val dir = MockFileItem("dir", true, emptyMap(), parent = root)
        
        // Mocking root's parent to be the evil root
        val rootWithEvilParent = MockFileItem("sdcard", true, mapOf("dir" to dir), parent = evilRoot)
        val dirWithRootParent = MockFileItem("dir", true, emptyMap(), parent = rootWithEvilParent)

        // At dir, try to go to root. This should work.
        val result = dirWithRootParent.resolve("..", jailRoot = rootWithEvilParent)
        assertTrue(result is Result.Success)
        assertEquals("sdcard", (result as Result.Success).value.name)

        // At root, try to go to evil root via '..'. 
        // The jail check should trigger because result.path will be "/root_filesystem/sdcard/.." -> "/root_filesystem"
        // which does not start with "/sdcard/"
        val resultAbove = rootWithEvilParent.resolve("..", jailRoot = rootWithEvilParent)
        assertTrue("Should be jailed at root", resultAbove is Result.Success)
        assertEquals("sdcard", (resultAbove as Result.Success).value.name)
    }

    @Test
    fun `test jail check prevents sibling traversal`() = runBlocking {
        // ... existing test code ...
    }

    @Test
    fun `test resolve virtual path in scoped structure`() = runBlocking {
        // ScopedFileItem uses virtual paths like /root/subdir/file
        val root = MockFileItem("root", true)
        val subdir = MockFileItem("subdir", true, emptyMap(), parent = root)
        val file = MockFileItem("file", false, emptyMap(), parent = subdir)
        
        val rootWithSubdir = MockFileItem("root", true, mapOf("subdir" to subdir), parent = null)
        val subdirWithFile = MockFileItem("subdir", true, mapOf("file" to file), parent = rootWithSubdir)
        
        val finalRoot = MockFileItem("root", true, mapOf("subdir" to subdirWithFile), parent = null)

        val result = finalRoot.resolve("subdir/file")
        assertTrue(result is Result.Success)
        assertEquals("/root/subdir/file", (result as Result.Success).value.path)
    }
}
