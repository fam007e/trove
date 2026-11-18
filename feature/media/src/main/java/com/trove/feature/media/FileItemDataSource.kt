package com.trove.feature.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Media3 [androidx.media3.datasource.DataSource] that bridges Trove's [FileItem] into ExoPlayer.
 * 
 * For local files, it provides true random access using [RandomAccessFile].
 * For remote/virtual files (SFTP, SMB, FTP, WebDAV, Archive), it opens [FileItem.openInputStream]
 * and handles seek offsets via streaming skip.
 */
class FileItemDataSource(
    private val itemProvider: (Uri) -> FileItem? = { TroveMediaRegistry.get(it) }
) : BaseDataSource(/* isNetwork = */ false) {

    private var dataSpec: DataSpec? = null
    private var uri: Uri? = null
    private var inputStream: InputStream? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)

        val item = itemProvider(dataSpec.uri)
            ?: throw IOException("No FileItem found for URI: ${dataSpec.uri}")

        // Fast path: if the path points directly to an existing local file
        val localFile = File(item.path)
        if (localFile.exists() && localFile.isFile) {
            val raf = RandomAccessFile(localFile, "r")
            this.randomAccessFile = raf
            raf.seek(dataSpec.position)
            
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                val rem = raf.length() - dataSpec.position
                if (rem < 0) throw EOFException("Position past end of file")
                rem
            }
        } else {
            // General VFS stream path
            val streamResult = runBlocking(Dispatchers.IO) {
                item.openInputStream()
            }

            val stream = when (streamResult) {
                is Result.Success -> streamResult.value
                is Result.Failure -> throw IOException("Failed to open stream for ${item.path}", streamResult.exception)
            }
            this.inputStream = stream

            // Handle seek via skip if position > 0
            if (dataSpec.position > 0) {
                var skippedTotal = 0L
                while (skippedTotal < dataSpec.position) {
                    val skipped = stream.skip(dataSpec.position - skippedTotal)
                    if (skipped <= 0) {
                        // Fallback read byte
                        if (stream.read() == -1) {
                            throw EOFException("Reached EOF while seeking to ${dataSpec.position}")
                        }
                        skippedTotal += 1
                    } else {
                        skippedTotal += skipped
                    }
                }
            }

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else if (item.size > 0 && item.size >= dataSpec.position) {
                item.size - dataSpec.position
            } else {
                C.LENGTH_UNSET.toLong()
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            Math.min(bytesRemaining, length.toLong()).toInt()
        }

        val bytesRead = randomAccessFile?.read(buffer, offset, bytesToRead)
            ?: inputStream?.read(buffer, offset, bytesToRead)
            ?: return C.RESULT_END_OF_INPUT

        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                throw EOFException("Unexpected EOF with $bytesRemaining bytes remaining")
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }

        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            randomAccessFile?.close()
        } catch (_: IOException) {
        } finally {
            randomAccessFile = null
        }

        try {
            inputStream?.close()
        } catch (_: IOException) {
        } finally {
            inputStream = null
        }

        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
