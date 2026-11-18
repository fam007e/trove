package com.trove.core.fs

import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LocalFileItem(private val file: File) : FileItem {
    override val name: String get() = file.name
    override val path: String get() = file.absolutePath
    override val size: Long get() = if (file.isDirectory) 0 else file.length()
    override val lastModified: Long get() = file.lastModified()
    override val isDirectory: Boolean get() = file.isDirectory
    override val permissions: PosixPermissions
        get() = try {
            val stat = Os.stat(file.absolutePath)
            val mode = stat.st_mode
            PosixPermissions(
                ownerRead = (mode and OsConstants.S_IRUSR) != 0,
                ownerWrite = (mode and OsConstants.S_IWUSR) != 0,
                ownerExecute = (mode and OsConstants.S_IXUSR) != 0,
                groupRead = (mode and OsConstants.S_IRGRP) != 0,
                groupWrite = (mode and OsConstants.S_IWGRP) != 0,
                groupExecute = (mode and OsConstants.S_IXGRP) != 0,
                otherRead = (mode and OsConstants.S_IROTH) != 0,
                otherWrite = (mode and OsConstants.S_IWOTH) != 0,
                otherExecute = (mode and OsConstants.S_IXOTH) != 0
            )
        } catch (e: Exception) {
            // Fallback to java.io.File basic checks
            PosixPermissions(
                ownerRead = file.canRead(),
                ownerWrite = file.canWrite(),
                ownerExecute = file.canExecute(),
                groupRead = file.canRead(),
                groupWrite = false,
                groupExecute = false,
                otherRead = file.canRead(),
                otherWrite = false,
                otherExecute = false
            )
        }
    override val mimeType: String
        get() {
            if (file.isDirectory) return "inode/directory"
            val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val files = file.listFiles()
            if (files != null) {
                Result.Success(files.map { LocalFileItem(it) })
            } else {
                Result.Failure(IOException("Failed to list files or not a directory"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            Result.Success(FileInputStream(file))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            Result.Success(FileOutputStream(file, append))
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newFile = File(file, name)
            if (newFile.createNewFile()) {
                Result.Success(LocalFileItem(newFile))
            } else {
                Result.Failure(IOException("File already exists or could not be created"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newDir = File(file, name)
            if (newDir.mkdir()) {
                Result.Success(LocalFileItem(newDir))
            } else {
                Result.Failure(IOException("Directory already exists or could not be created"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (file.deleteRecursively()) {
                Result.Success(Unit)
            } else {
                Result.Failure(IOException("Failed to delete file/directory"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val dest = File(file.parentFile, newName)
            if (file.renameTo(dest)) {
                Result.Success(LocalFileItem(dest))
            } else {
                Result.Failure(IOException("Failed to rename file"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        // Basic implementation for local to local
        if (dest is LocalFileItem) {
            try {
                file.copyRecursively(File(dest.path, file.name), overwrite = true)
                Result.Success(LocalFileItem(File(dest.path, file.name)))
            } catch (e: Exception) {
                Result.Failure(e)
            }
        } else {
            // Generic implementation using streams
            try {
                val targetFileResult = dest.createFile(file.name)
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
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (destDir is LocalFileItem) {
            try {
                val destFile = File(destDir.path, newName)
                if (file.renameTo(destFile)) {
                    Result.Success(LocalFileItem(destFile))
                } else {
                    // Fallback to copy + delete
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
            } catch (e: Exception) {
                Result.Failure(e)
            }
        } else {
            // Cross-provider move
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
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val child = File(file, name)
            if (child.exists()) {
                Result.Success(LocalFileItem(child))
            } else {
                Result.Failure(IOException("Child not found: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.IO) {
        try {
            val parent = file.parentFile
            Result.Success(parent?.let { LocalFileItem(it) })
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        file.exists()
    }
}
