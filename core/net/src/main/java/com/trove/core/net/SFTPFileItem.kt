package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.PathComponents
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class SFTPFileItem(
    private val session: NetworkSession<SFTPClient>,
    private val info: RemoteResourceInfo,
    private val attributes: FileAttributes
) : FileItem {
    override val name: String get() = info.name
    override val path: String get() = info.path
    override val size: Long get() = attributes.size
    override val lastModified: Long get() = attributes.mtime * 1000L
    override val isDirectory: Boolean get() = info.isDirectory
    override val permissions: PosixPermissions
        get() = PosixPermissions(
            ownerRead = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.USR_R),
            ownerWrite = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.USR_W),
            ownerExecute = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.USR_X),
            groupRead = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.GRP_R),
            groupWrite = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.GRP_W),
            groupExecute = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.GRP_X),
            otherRead = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.OTH_R),
            otherWrite = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.OTH_W),
            otherExecute = attributes.permissions.contains(net.schmizz.sshj.xfer.FilePermission.OTH_X)
        )
    override val mimeType: String get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val files = client.ls(path)
                Result.Success(files.map { SFTPFileItem(session, it, it.attributes) })
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val inputStream = session.openLockingInputStream { client ->
                client.open(path).RemoteFileInputStream()
            }
            if (inputStream != null) {
                Result.Success(inputStream)
            } else {
                Result.Failure(IOException("Failed to open SFTP input stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            val outputStream = session.openLockingOutputStream { client ->
                val mode = if (append) {
                    setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.APPEND)
                } else {
                    setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC)
                }
                client.open(path, mode).RemoteFileOutputStream()
            }
            if (outputStream != null) {
                Result.Success(outputStream)
            } else {
                Result.Failure(IOException("Failed to open SFTP output stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val newPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                client.open(newPath, setOf(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.WRITE)).close()
                val attributes = client.stat(newPath)
                val components = PathComponents(path, name, "/")
                val info = RemoteResourceInfo(components, attributes)
                Result.Success(SFTPFileItem(session, info, attributes))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val newPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                client.mkdir(newPath)
                val attributes = client.stat(newPath)
                val components = PathComponents(path, name, "/")
                val info = RemoteResourceInfo(components, attributes)
                Result.Success(SFTPFileItem(session, info, attributes))
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
                    client.rm(path)
                    Result.Success(Unit)
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
            client.rmdir(path)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                val parent = path.substringBeforeLast("/", "/")
                val newPath = if (parent.endsWith("/")) "$parent$newName" else "$parent/$newName"
                client.rename(path, newPath)
                val attributes = client.stat(newPath)
                val components = PathComponents(parent, newName, "/")
                val info = RemoteResourceInfo(components, attributes)
                Result.Success(SFTPFileItem(session, info, attributes))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        // NOTE: Same-session reentrancy is handled by the SessionManager via Job identity.
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
        if (destDir is SFTPFileItem && destDir.session === session) {
            try {
                session.useClient { client ->
                    val newPath = if (destDir.path.endsWith("/")) "${destDir.path}$newName" else "${destDir.path}/$newName"
                    client.rename(path, newPath)
                    val attributes = client.stat(newPath)
                    val components = PathComponents(destDir.path, newName, "/")
                    val info = RemoteResourceInfo(components, attributes)
                    Result.Success(SFTPFileItem(session, info, attributes))
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
            session.useClient { client ->
                val childPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                val attributes = client.stat(childPath)
                val components = PathComponents(path, name, "/")
                val info = RemoteResourceInfo(components, attributes)
                Result.Success(SFTPFileItem(session, info, attributes))
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
                val finalParentPath = if (parentPath.isEmpty()) "/" else parentPath
                val attributes = client.stat(finalParentPath)
                val parentDir = finalParentPath.substringBeforeLast("/", "/")
                val name = finalParentPath.substringAfterLast("/")
                val components = PathComponents(parentDir, name, "/")
                val info = RemoteResourceInfo(components, attributes)
                Result.Success(SFTPFileItem(session, info, attributes))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            session.useClient { client ->
                client.stat(path) != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
