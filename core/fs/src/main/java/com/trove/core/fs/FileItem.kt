package com.trove.core.fs

import java.io.InputStream
import java.io.OutputStream

interface FileItem {
    val name: String
    val path: String
    val size: Long
    val lastModified: Long
    val isDirectory: Boolean
    val permissions: PosixPermissions
    val mimeType: String

    suspend fun listFiles(): Result<List<FileItem>>
    suspend fun openInputStream(): Result<InputStream>
    suspend fun openOutputStream(append: Boolean = false): Result<OutputStream>
    suspend fun createFile(name: String): Result<FileItem>
    suspend fun createDirectory(name: String): Result<FileItem>
    suspend fun delete(): Result<Unit>
    suspend fun rename(newName: String): Result<FileItem>
    suspend fun copyTo(dest: FileItem): Result<FileItem>
    
    /**
     * Move this item to a new directory with a new name.
     * Implementations should try to use atomic rename if possible.
     */
    suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem>

    /**
     * Efficiently find a child by name without listing the entire directory.
     */
    suspend fun findChild(name: String): Result<FileItem>

    /**
     * Get the parent directory of this item.
     */
    suspend fun getParent(): Result<FileItem?>
    
    /**
     * Optional: Check if the file exists.
     */
    suspend fun exists(): Boolean
}
