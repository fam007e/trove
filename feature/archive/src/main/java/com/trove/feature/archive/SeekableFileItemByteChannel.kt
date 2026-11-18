package com.trove.feature.archive

import com.trove.core.fs.FileItem
import com.trove.core.fs.LocalFileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A [SeekableByteChannel] backed by a [FileItem].
 *
 * For local files, this delegates directly to a read-only [FileChannel].
 * For remote/stream-backed items, this buffers into a temporary file for random-access
 * operations required by formats like 7z and central-directory ZIP parsing.
 */
class SeekableFileItemByteChannel private constructor(
    private val delegate: SeekableByteChannel,
    private val tempFileToCleanup: File? = null
) : SeekableByteChannel {

    override fun isOpen(): Boolean = delegate.isOpen

    override fun close() {
        try {
            delegate.close()
        } finally {
            tempFileToCleanup?.delete()
        }
    }

    override fun read(dst: ByteBuffer?): Int = delegate.read(dst)

    override fun write(src: ByteBuffer?): Int {
        throw NonWritableChannelException()
    }

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): SeekableByteChannel {
        delegate.position(newPosition)
        return this
    }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel {
        throw NonWritableChannelException()
    }

    companion object {
        suspend fun open(fileItem: FileItem): Result<SeekableFileItemByteChannel> {
            return try {
                if (fileItem is LocalFileItem) {
                    val file = File(fileItem.path)
                    if (file.exists() && file.isFile) {
                        val raf = RandomAccessFile(file, "r")
                        return Result.Success(SeekableFileItemByteChannel(raf.channel))
                    }
                }

                // If remote or SAF, stream into temporary cache file for seekable access
                when (val inResult = fileItem.openInputStream()) {
                    is Result.Failure -> Result.Failure(inResult.exception)
                    is Result.Success -> {
                        val tempFile = File.createTempFile("trove_archive_", ".tmp")
                        tempFile.deleteOnExit()
                        inResult.value.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val raf = RandomAccessFile(tempFile, "r")
                        Result.Success(SeekableFileItemByteChannel(raf.channel, tempFile))
                    }
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }
}
