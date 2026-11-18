package com.trove.feature.archive

import android.webkit.MimeTypeMap
import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ArchiveFileItem private constructor(
    val sourceArchive: FileItem,
    val tree: ArchiveTree,
    val node: ArchiveEntryNode
) : FileItem {

    override val name: String
        get() = if (node.entryPath.isEmpty()) sourceArchive.name else node.name

    override val path: String
        get() = if (node.entryPath.isEmpty()) {
            "${sourceArchive.path}!/"
        } else {
            "${sourceArchive.path}!/${node.entryPath}"
        }

    override val size: Long
        get() = node.size

    override val lastModified: Long
        get() = if (node.lastModified > 0L) node.lastModified else sourceArchive.lastModified

    override val isDirectory: Boolean
        get() = node.isDirectory

    override val permissions: PosixPermissions
        get() = if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    override val mimeType: String
        get() {
            if (isDirectory) return "inode/directory"
            val extension = MimeTypeMap.getFileExtensionFromUrl(name)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.Default) {
        if (!isDirectory) {
            return@withContext Result.Failure(IllegalStateException("$name is not a directory"))
        }
        val items = node.children.values.map { childNode ->
            ArchiveFileItem(sourceArchive, tree, childNode)
        }
        Result.Success(items)
    }

    override suspend fun openInputStream(): Result<InputStream> {
        if (isDirectory) {
            return Result.Failure(IllegalStateException("Cannot open InputStream for directory: $name"))
        }
        return tree.openEntryStream(node.entryPath)
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun createFile(name: String): Result<FileItem> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun createDirectory(name: String): Result<FileItem> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun delete(): Result<Unit> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun rename(newName: String): Result<FileItem> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> {
        return Result.Failure(UnsupportedOperationException("Direct archive item copy not supported. Use ArchiveExtractor."))
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> {
        return Result.Failure(UnsupportedOperationException("Archive virtual filesystem is read-only"))
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.Default) {
        val child = node.findChild(name)
            ?: return@withContext Result.Failure(NoSuchElementException("Child '$name' not found in $path"))
        Result.Success(ArchiveFileItem(sourceArchive, tree, child))
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.Default) {
        val p = node.parent
        when {
            p == null || node == tree.rootNode -> {
                // Parent of the root inside the archive is the directory containing the archive
                sourceArchive.getParent()
            }
            p == tree.rootNode -> {
                // Parent of top-level entries is the root ArchiveFileItem
                Result.Success(ArchiveFileItem(sourceArchive, tree, p))
            }
            else -> {
                Result.Success(ArchiveFileItem(sourceArchive, tree, p))
            }
        }
    }

    override suspend fun exists(): Boolean = true

    companion object {
        suspend fun open(sourceArchive: FileItem, password: String? = null): Result<ArchiveFileItem> {
            return when (val treeResult = ArchiveTree.build(sourceArchive, password)) {
                is Result.Failure -> Result.Failure(treeResult.exception)
                is Result.Success -> Result.Success(
                    ArchiveFileItem(
                        sourceArchive = sourceArchive,
                        tree = treeResult.value,
                        node = treeResult.value.rootNode
                    )
                )
            }
        }
    }
}
