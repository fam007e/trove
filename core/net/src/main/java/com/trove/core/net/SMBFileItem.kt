package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FileItem implementation for SMB (Server Message Block) using jcifs-ng.
 */
class SMBFileItem(
    private val session: NetworkSession<CIFSContext>,
    internal val smbResource: SmbFile
) : FileItem {

    override val name: String get() = smbResource.name.removeSuffix("/")
    override val path: String get() = smbResource.canonicalPath
    override val size: Long get() = try { smbResource.length() } catch (e: Exception) { 0L }
    override val lastModified: Long get() = try { smbResource.lastModified() } catch (e: Exception) { 0L }
    override val isDirectory: Boolean get() = try { smbResource.isDirectory } catch (e: Exception) { false }
    
    override val permissions: PosixPermissions
        get() = if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    override val mimeType: String
        get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            session.useClient { _ ->
                val files = smbResource.listFiles()
                Result.Success(files.map { SMBFileItem(session, it as SmbFile) })
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val inputStream = session.openLockingInputStream { _ ->
                SmbFileInputStream(smbResource)
            }
            if (inputStream != null) {
                Result.Success(inputStream)
            } else {
                Result.Failure(IOException("Failed to open SMB input stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        try {
            val outputStream = session.openLockingOutputStream { _ ->
                SmbFileOutputStream(smbResource, append)
            }
            if (outputStream != null) {
                Result.Success(outputStream)
            } else {
                Result.Failure(IOException("Failed to open SMB output stream for $path"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { context ->
                val newFile = smbResource.resolve(name)
                newFile.createNewFile()
                Result.Success(SMBFileItem(session, newFile as SmbFile))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { context ->
                val newDir = smbResource.resolve("$name/")
                newDir.mkdir()
                Result.Success(SMBFileItem(session, newDir as SmbFile))
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
                session.useClient { _ ->
                    smbResource.delete()
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
            throw IOException("Failed to list directory $path for deletion", filesResult.exception)
        }
        
        session.useClient { _ ->
            smbResource.delete()
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            session.useClient { context ->
                val parentUrl = smbResource.parent
                val destFile = SmbFile(parentUrl + newName, context)
                smbResource.renameTo(destFile)
                Result.Success(SMBFileItem(session, destFile))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        // NOTE: Same-session deadlock constraint applies if 'dest' uses the same session.
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
        if (destDir is SMBFileItem && destDir.session === session) {
            try {
                session.useClient { context ->
                    val destParentUrl = destDir.smbResource.canonicalPath.let { if (it.endsWith("/")) it else "$it/" }
                    val destFile = SmbFile(destParentUrl + newName, context)
                    smbResource.renameTo(destFile)
                    Result.Success(SMBFileItem(session, destFile))
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
            session.useClient { context ->
                val child = smbResource.resolve(name)
                if (child.exists()) {
                    Result.Success(SMBFileItem(session, child as SmbFile))
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
            session.useClient { context ->
                val canonical = smbResource.canonicalPath
                val parentPath = canonical.trimEnd('/')
                    .substringBeforeLast('/', "")
                    .let { if (it.isEmpty() || it == "smb:/") null else if (it.endsWith('/')) it else "$it/" }

                if (parentPath != null && parentPath.startsWith("smb://")) {
                    val parentResource = SmbFile(parentPath, context)
                    Result.Success(SMBFileItem(session, parentResource))
                } else {
                    Result.Success(null)
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            session.useClient { _ ->
                smbResource.exists()
            }
        } catch (e: Exception) {
            false
        }
    }
}
