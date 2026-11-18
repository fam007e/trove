package com.trove.core.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FileItem implementation for Android's Storage Access Framework (SAF).
 * Wraps a DocumentFile and provides access via URIs.
 */
class ScopedFileItem(
    private val context: Context,
    private val documentFile: DocumentFile,
    private val rootUri: Uri,
    private val virtualPath: String
) : FileItem {

    override val name: String get() = documentFile.name ?: ""
    override val path: String get() = virtualPath
    override val size: Long get() = documentFile.length()
    override val lastModified: Long get() = documentFile.lastModified()
    override val isDirectory: Boolean get() = documentFile.isDirectory
    override val permissions: PosixPermissions
        get() = PosixPermissions(
            ownerRead = documentFile.canRead(),
            ownerWrite = documentFile.canWrite(),
            ownerExecute = false, // SAF doesn't really have execute bits
            groupRead = false,
            groupWrite = false,
            groupExecute = false,
            otherRead = false,
            otherWrite = false,
            otherExecute = false
        )
    override val mimeType: String get() = documentFile.type ?: "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val files = documentFile.listFiles()
            Result.Success(files.map { 
                ScopedFileItem(
                    context, 
                    it, 
                    rootUri, 
                    "$virtualPath/${it.name}".replace("//", "/")
                ) 
            })
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(documentFile.uri)
            if (inputStream != null) {
                Result.Success(inputStream)
            } else {
                Result.Failure(IOException("Failed to open input stream for ${documentFile.uri}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            val mode = if (append) "wa" else "w"
            val outputStream = context.contentResolver.openOutputStream(documentFile.uri, mode)
            if (outputStream != null) {
                Result.Success(outputStream)
            } else {
                Result.Failure(IOException("Failed to open output stream for ${documentFile.uri}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newFile = documentFile.createFile("application/octet-stream", name)
            if (newFile != null) {
                Result.Success(ScopedFileItem(context, newFile, rootUri, "$virtualPath/$name".replace("//", "/")))
            } else {
                Result.Failure(IOException("Failed to create file: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newDir = documentFile.createDirectory(name)
            if (newDir != null) {
                Result.Success(ScopedFileItem(context, newDir, rootUri, "$virtualPath/$name".replace("//", "/")))
            } else {
                Result.Failure(IOException("Failed to create directory: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (documentFile.delete()) {
                Result.Success(Unit)
            } else {
                Result.Failure(IOException("Failed to delete ${documentFile.uri}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (documentFile.renameTo(newName)) {
                val parentPath = virtualPath.substringBeforeLast("/", "/")
                Result.Success(ScopedFileItem(context, documentFile, rootUri, "$parentPath/$newName".replace("//", "/")))
            } else {
                Result.Failure(IOException("Failed to rename to $newName"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        // Generic implementation using streams
        try {
            val targetFileResult = dest.createFile(name)
            if (targetFileResult is Result.Failure) return@withContext targetFileResult as Result<FileItem>
            val targetFile = (targetFileResult as Result.Success).value

            openInputStream().flatMap { input ->
                targetFile.openOutputStream(false).map { output ->
                    input.use { i -> output.use { o -> i.copyTo(o) } }
                    targetFile
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        // Atomic move for SAF is possible with DocumentsContract if both are in same provider,
        // but copy + delete is safer for generic FileItem.
        copyTo(destDir).flatMap { copiedItem ->
            if (copiedItem.name != newName) {
                copiedItem.rename(newName).flatMap { renamed ->
                    delete().map { renamed }
                }
            } else {
                delete().map { copiedItem }
            }
        }
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val child = documentFile.findFile(name)
            if (child != null) {
                Result.Success(ScopedFileItem(context, child, rootUri, "$virtualPath/$name".replace("//", "/")))
            } else {
                Result.Failure(IOException("Child not found: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.IO) {
        try {
            val parent = documentFile.parentFile
            if (parent != null) {
                val parentPath = virtualPath.substringBeforeLast("/", "/")
                Result.Success(ScopedFileItem(context, parent, rootUri, parentPath))
            } else {
                Result.Success(null)
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        documentFile.exists()
    }
}
