package com.trove.feature.archive

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

object ArchiveExtractor {

    interface ProgressListener {
        fun onProgress(bytesExtracted: Long, totalBytes: Long, currentEntry: String)
    }

    suspend fun extractAll(
        archiveItem: ArchiveFileItem,
        destinationDir: FileItem,
        listener: ProgressListener? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!destinationDir.isDirectory) {
                return@withContext Result.Failure(IllegalArgumentException("Destination must be a directory"))
            }

            val totalBytes = calculateTotalSize(archiveItem.node)
            var bytesExtracted = 0L

            extractNodeRecursive(
                archiveItem = archiveItem,
                currentNode = archiveItem.node,
                currentDest = destinationDir,
                totalBytes = totalBytes,
                bytesTracker = { added ->
                    bytesExtracted += added
                },
                getBytesExtracted = { bytesExtracted },
                listener = listener
            )

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private suspend fun extractNodeRecursive(
        archiveItem: ArchiveFileItem,
        currentNode: ArchiveEntryNode,
        currentDest: FileItem,
        totalBytes: Long,
        bytesTracker: (Long) -> Unit,
        getBytesExtracted: () -> Long,
        listener: ProgressListener?
    ) {
        coroutineContext.ensureActive()

        for (child in currentNode.children.values) {
            coroutineContext.ensureActive()

            if (child.isDirectory) {
                val newDir = when (val res = currentDest.createDirectory(child.name)) {
                    is Result.Success -> res.value
                    is Result.Failure -> {
                        // Might already exist
                        when (val find = currentDest.findChild(child.name)) {
                            is Result.Success -> find.value
                            is Result.Failure -> throw res.exception
                        }
                    }
                }
                extractNodeRecursive(
                    archiveItem = archiveItem,
                    currentNode = child,
                    currentDest = newDir,
                    totalBytes = totalBytes,
                    bytesTracker = bytesTracker,
                    getBytesExtracted = getBytesExtracted,
                    listener = listener
                )
            } else {
                listener?.onProgress(getBytesExtracted(), totalBytes, child.name)

                val destFile = when (val res = currentDest.createFile(child.name)) {
                    is Result.Success -> res.value
                    is Result.Failure -> throw res.exception
                }

                val entryStreamResult = archiveItem.tree.openEntryStream(child.entryPath)
                if (entryStreamResult is Result.Failure) throw entryStreamResult.exception
                val inputStream: InputStream = (entryStreamResult as Result.Success).value

                val outStreamResult = destFile.openOutputStream(append = false)
                if (outStreamResult is Result.Failure) {
                    inputStream.close()
                    throw outStreamResult.exception
                }
                val outputStream = (outStreamResult as Result.Success).value

                try {
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    inputStream.use { inStream ->
                        outputStream.use { outStream ->
                            while (inStream.read(buffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                outStream.write(buffer, 0, bytesRead)
                                bytesTracker(bytesRead.toLong())
                                listener?.onProgress(getBytesExtracted(), totalBytes, child.name)
                            }
                        }
                    }
                } finally {
                    inputStream.close()
                }
            }
        }
    }

    private fun calculateTotalSize(node: ArchiveEntryNode): Long {
        var sum = if (node.isDirectory) 0L else node.size
        for (child in node.children.values) {
            sum += calculateTotalSize(child)
        }
        return sum
    }
}
