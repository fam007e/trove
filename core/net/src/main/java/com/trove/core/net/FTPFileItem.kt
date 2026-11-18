package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class FTPFileItem(
    private val session: NetworkSession<FTPClient>,
    private val ftpFile: FTPFile,
    private val parentPath: String
) : FileItem {
    override val name: String get() = ftpFile.name
    override val path: String get() = "$parentPath/${ftpFile.name}".replace("//", "/")
    override val size: Long get() = ftpFile.size
    override val lastModified: Long get() = ftpFile.timestamp.timeInMillis
    override val isDirectory: Boolean get() = ftpFile.isDirectory
    override val permissions: PosixPermissions
        get() = PosixPermissions(
            ownerRead = ftpFile.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
            ownerWrite = ftpFile.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
            ownerExecute = ftpFile.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION),
            groupRead = ftpFile.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION),
            groupWrite = ftpFile.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION),
            groupExecute = ftpFile.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION),
            otherRead = ftpFile.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION),
            otherWrite = ftpFile.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION),
            otherExecute = ftpFile.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
        )
    override val mimeType: String get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val files = client.listFiles(path)
                Result.Success(files.map { FTPFileItem(session, it, path) })
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val inputStream = session.openLockingInputStream { client ->
                client.retrieveFileStream(path)
            }
            if (inputStream != null) {
                Result.Success(inputStream)
            } else {
                Result.Failure(IOException("Failed to open input stream"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            val outputStream = session.openLockingOutputStream { client ->
                if (append) client.appendFileStream(path) else client.storeFileStream(path)
            }
            if (outputStream != null) {
                Result.Success(outputStream)
            } else {
                Result.Failure(IOException("Failed to open output stream"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val newPath = "$path/$name".replace("//", "/")
                if (client.storeFile(newPath, "".byteInputStream())) {
                    val newFile = client.mlistFile(newPath)
                    Result.Success(FTPFileItem(session, newFile, path))
                } else {
                    Result.Failure(IOException("Failed to create file: ${client.replyString}"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val newPath = "$path/$name".replace("//", "/")
                if (client.makeDirectory(newPath)) {
                    val newFile = client.mlistFile(newPath)
                    Result.Success(FTPFileItem(session, newFile, path))
                } else {
                    Result.Failure(IOException("Failed to create directory: ${client.replyString}"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (isDirectory) {
                deleteRecursive()
                Result.Success(Unit)
            } else {
                session.useClient { client ->
                    if (client.deleteFile(path)) Result.Success(Unit) 
                    else Result.Failure(IOException("Failed to delete file: ${client.replyString}"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private suspend fun deleteRecursive() {
        val filesResult = listFiles()
        if (filesResult is Result.Success) {
            for (file in filesResult.value) {
                val result = file.delete()
                if (result is Result.Failure) {
                    throw IOException("Failed to delete child ${file.path}: ${result.exception.message}", result.exception)
                }
            }
        } else if (filesResult is Result.Failure) {
            throw IOException("Failed to list directory ${path} for deletion", filesResult.exception)
        }
        
        session.useClient { client ->
            if (!client.removeDirectory(path)) {
                throw IOException("Failed to remove directory: ${client.replyString}")
            }
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val parent = path.substringBeforeLast("/", "/")
                val newPath = "$parent/$newName".replace("//", "/")
                if (client.rename(path, newPath)) {
                    val newFile = client.mlistFile(newPath)
                    Result.Success(FTPFileItem(session, newFile, parent))
                } else {
                    Result.Failure(IOException("Failed to rename: ${client.replyString}"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        // NOTE: Same-session reentrancy is handled by the SessionManager via CoroutineContext tokens.
        session.useClient { // Establish reentrancy scope
            try {
                val targetFileResult = dest.createFile(name)
                if (targetFileResult is Result.Failure) return@useClient targetFileResult as Result<FileItem>
                val targetFile = (targetFileResult as Result.Success).value

                openInputStream().flatMap { input ->
                    targetFile.openOutputStream(false).flatMap { output ->
                        try {
                            input.use { i -> output.use { o -> i.copyTo(o) } }
                            // Complete the FTP transfer
                            if (it.completePendingCommand()) {
                                Result.Success(targetFile)
                            } else {
                                Result.Failure(IOException("Failed to complete FTP transfer"))
                            }
                        } catch (e: Exception) {
                            Result.Failure(e)
                        }
                    }
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (destDir is FTPFileItem && destDir.session === session) {
            session.useClient { client ->
                try {
                    val newPath = "${destDir.path}/$newName".replace("//", "/")
                    if (client.rename(path, newPath)) {
                        val newFile = client.mlistFile(newPath)
                        Result.Success(FTPFileItem(session, newFile, destDir.path))
                    } else {
                        Result.Failure(IOException("Failed to move: ${client.replyString}"))
                    }
                } catch (e: Exception) {
                    Result.Failure(e)
                }
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
            session.useClient { client ->
                val childPath = "$path/$name".replace("//", "/")
                val file = client.mlistFile(childPath)
                if (file != null) {
                    Result.Success(FTPFileItem(session, file, path))
                } else {
                    Result.Failure(IOException("Child not found: $name"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.IO) {
        try {
            if (path == "/" || path == "") return@withContext Result.Success(null)
            session.useClient { client ->
                val parentPath = path.substringBeforeLast("/", "/")
                val file = client.mlistFile(parentPath)
                if (file != null) {
                    Result.Success(FTPFileItem(session, file, parentPath.substringBeforeLast("/", "/")))
                } else {
                    Result.Failure(IOException("Parent not found for $path"))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                client.mlistFile(path) != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
