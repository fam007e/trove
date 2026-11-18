package com.trove.privilege.fs

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import com.trove.core.fs.resolve
import com.trove.privilege.ITrovePrivileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FileItem implementation that proxies operations over a Shizuku Binder.
 */
class PrivilegedFileItem(
    private val service: ITrovePrivileged,
    private val itemBundle: Bundle,
    override val path: String
) : FileItem {

    override val name: String get() = itemBundle.getString("name") ?: ""
    override val size: Long get() = itemBundle.getLong("size")
    override val lastModified: Long get() = itemBundle.getLong("lastModified")
    override val isDirectory: Boolean get() = itemBundle.getBoolean("isDirectory")
    
    override val permissions: PosixPermissions
        get() = if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    override val mimeType: String
        get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val bundles = service.listFiles(path)
            Result.Success(bundles.map { 
                PrivilegedFileItem(service, it, "$path/${it.getString("name")}".replace("//", "/")) 
            })
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val pfd = service.openFile(path, "r")
            if (pfd != null) {
                Result.Success(ParcelFileDescriptor.AutoCloseInputStream(pfd))
            } else {
                Result.Failure(IOException("Privileged service failed to open input stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            val pfd = service.openFile(path, if (append) "rw" else "w")
            if (pfd != null) {
                Result.Success(ParcelFileDescriptor.AutoCloseOutputStream(pfd))
            } else {
                Result.Failure(IOException("Privileged service failed to open output stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newPath = "$path/$name".replace("//", "/")
            val pfd = service.openFile(newPath, "w")
            if (pfd != null) {
                pfd.close()
                val stat = service.stat(newPath)
                Result.Success(PrivilegedFileItem(service, stat, newPath))
            } else {
                Result.Failure(IOException("Privileged service failed to create file: $newPath"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newPath = "$path/$name".replace("//", "/")
            if (service.mkdir(newPath)) {
                val stat = service.stat(newPath)
                Result.Success(PrivilegedFileItem(service, stat, newPath))
            } else {
                Result.Failure(IOException("Privileged service failed to create directory: $newPath"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (service.delete(path)) {
                Result.Success(Unit)
            } else {
                Result.Failure(IOException("Privileged service failed to delete: $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = path.substringBeforeLast("/", "/")
            val newPath = "$parent/$newName".replace("//", "/")
            if (service.rename(path, newPath)) {
                val stat = service.stat(newPath)
                Result.Success(PrivilegedFileItem(service, stat, newPath))
            } else {
                Result.Failure(IOException("Privileged service failed to rename to: $newPath"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
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
        if (destDir is PrivilegedFileItem && destDir.service == service) {
            try {
                val newPath = "${destDir.path}/$newName".replace("//", "/")
                if (service.rename(path, newPath)) {
                    val stat = service.stat(newPath)
                    Result.Success(PrivilegedFileItem(service, stat, newPath))
                } else {
                    Result.Failure(IOException("Privileged service failed to move to: $newPath"))
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        } else {
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
            val childPath = "$path/$name".replace("//", "/")
            val stat = service.stat(childPath)
            if (stat.containsKey("name")) {
                Result.Success(PrivilegedFileItem(service, stat, childPath))
            } else {
                Result.Failure(IOException("Child not found via privileged service: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.IO) {
        try {
            if (path == "/" || path == "") return@withContext Result.Success(null)
            val parentPath = path.substringBeforeLast("/", "/")
            val finalParentPath = if (parentPath.isEmpty()) "/" else parentPath
            val stat = service.stat(finalParentPath)
            if (stat.containsKey("name")) {
                Result.Success(PrivilegedFileItem(service, stat, finalParentPath))
            } else {
                Result.Success(null)
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            service.stat(path).containsKey("name")
        } catch (e: Exception) {
            false
        }
    }
}
