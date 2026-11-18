package com.trove.feature.archive

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

data class ArchiveCreationParams(
    val format: ArchiveType = ArchiveType.ZIP,
    val compressionLevel: Int = 5, // 0..9
    val password: String? = null,
    val encryptEntryNames: Boolean = false
)

object ArchiveCreator {

    interface ProgressListener {
        fun onProgress(bytesProcessed: Long, currentFile: String)
    }

    suspend fun createArchive(
        sources: List<FileItem>,
        destinationFile: FileItem,
        params: ArchiveCreationParams,
        listener: ProgressListener? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val outResult = destinationFile.openOutputStream(append = false)
        if (outResult is Result.Failure) return@withContext Result.Failure(outResult.exception)
        val outStream = (outResult as Result.Success).value

        try {
            when (params.format) {
                ArchiveType.ZIP -> {
                    if (!params.password.isNullOrEmpty()) {
                        createEncryptedZip(sources, outStream, params, listener)
                    } else {
                        createCommonsZip(sources, outStream, params, listener)
                    }
                }
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> {
                    createTar(sources, outStream, params, listener)
                }
                else -> {
                    return@withContext Result.Failure(
                        UnsupportedOperationException("Creation of ${params.format} is not supported yet")
                    )
                }
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e)
        } finally {
            try {
                outStream.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun createEncryptedZip(
        sources: List<FileItem>,
        outStream: OutputStream,
        params: ArchiveCreationParams,
        listener: ProgressListener?
    ) {
        val zipOut = ZipOutputStream(outStream, params.password!!.toCharArray())
        var totalBytes = 0L

        for (item in sources) {
            coroutineContext.ensureActive()
            archiveFileItemWithZip4j(item, "", zipOut, params) { bytesRead, path ->
                totalBytes += bytesRead
                listener?.onProgress(totalBytes, path)
            }
        }
        zipOut.close()
    }

    private suspend fun archiveFileItemWithZip4j(
        item: FileItem,
        basePath: String,
        zipOut: ZipOutputStream,
        params: ArchiveCreationParams,
        onProgress: (Int, String) -> Unit
    ) {
        val entryName = if (basePath.isEmpty()) item.name else "$basePath/${item.name}"

        if (item.isDirectory) {
            val zipParams = ZipParameters().apply {
                fileNameInZip = "$entryName/"
            }
            zipOut.putNextEntry(zipParams)
            zipOut.closeEntry()

            val children = item.listFiles()
            if (children is Result.Success) {
                for (child in children.value) {
                    coroutineContext.ensureActive()
                    archiveFileItemWithZip4j(child, entryName, zipOut, params, onProgress)
                }
            }
        } else {
            val zipParams = ZipParameters().apply {
                fileNameInZip = entryName
                compressionMethod = CompressionMethod.DEFLATE
                compressionLevel = when (params.compressionLevel) {
                    0 -> CompressionLevel.NO_COMPRESSION
                    1, 2 -> CompressionLevel.FASTEST
                    3, 4 -> CompressionLevel.FAST
                    5, 6 -> CompressionLevel.NORMAL
                    7, 8 -> CompressionLevel.MAXIMUM
                    else -> CompressionLevel.ULTRA
                }
                if (!params.password.isNullOrEmpty()) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }

            zipOut.putNextEntry(zipParams)
            val inResult = item.openInputStream()
            if (inResult is Result.Success) {
                inResult.value.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        zipOut.write(buffer, 0, read)
                        onProgress(read, entryName)
                    }
                }
            }
            zipOut.closeEntry()
        }
    }

    private suspend fun createCommonsZip(
        sources: List<FileItem>,
        outStream: OutputStream,
        params: ArchiveCreationParams,
        listener: ProgressListener?
    ) {
        val zipOut = ZipArchiveOutputStream(outStream)
        zipOut.setLevel(params.compressionLevel)
        var totalBytes = 0L

        for (item in sources) {
            coroutineContext.ensureActive()
            archiveFileItemCommonsZip(item, "", zipOut) { bytesRead, path ->
                totalBytes += bytesRead
                listener?.onProgress(totalBytes, path)
            }
        }
        zipOut.finish()
    }

    private suspend fun archiveFileItemCommonsZip(
        item: FileItem,
        basePath: String,
        zipOut: ZipArchiveOutputStream,
        onProgress: (Int, String) -> Unit
    ) {
        val entryName = if (basePath.isEmpty()) item.name else "$basePath/${item.name}"

        if (item.isDirectory) {
            val entry = ZipArchiveEntry("$entryName/")
            zipOut.putArchiveEntry(entry)
            zipOut.closeArchiveEntry()

            val children = item.listFiles()
            if (children is Result.Success) {
                for (child in children.value) {
                    coroutineContext.ensureActive()
                    archiveFileItemCommonsZip(child, entryName, zipOut, onProgress)
                }
            }
        } else {
            val entry = ZipArchiveEntry(entryName)
            entry.size = item.size
            zipOut.putArchiveEntry(entry)

            val inResult = item.openInputStream()
            if (inResult is Result.Success) {
                inResult.value.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        zipOut.write(buffer, 0, read)
                        onProgress(read, entryName)
                    }
                }
            }
            zipOut.closeArchiveEntry()
        }
    }

    private suspend fun createTar(
        sources: List<FileItem>,
        outStream: OutputStream,
        params: ArchiveCreationParams,
        listener: ProgressListener?
    ) {
        val compressorStream: OutputStream = when (params.format) {
            ArchiveType.TAR_GZ -> GzipCompressorOutputStream(outStream)
            ArchiveType.TAR_BZ2 -> BZip2CompressorOutputStream(outStream)
            ArchiveType.TAR_XZ -> XZCompressorOutputStream(outStream)
            else -> outStream
        }

        val tarOut = TarArchiveOutputStream(compressorStream)
        var totalBytes = 0L

        for (item in sources) {
            coroutineContext.ensureActive()
            archiveFileItemTar(item, "", tarOut) { bytesRead, path ->
                totalBytes += bytesRead
                listener?.onProgress(totalBytes, path)
            }
        }
        tarOut.finish()
        if (compressorStream !== outStream) {
            compressorStream.flush()
        }
    }

    private suspend fun archiveFileItemTar(
        item: FileItem,
        basePath: String,
        tarOut: TarArchiveOutputStream,
        onProgress: (Int, String) -> Unit
    ) {
        val entryName = if (basePath.isEmpty()) item.name else "$basePath/${item.name}"

        if (item.isDirectory) {
            val entry = TarArchiveEntry("$entryName/")
            tarOut.putArchiveEntry(entry)
            tarOut.closeArchiveEntry()

            val children = item.listFiles()
            if (children is Result.Success) {
                for (child in children.value) {
                    coroutineContext.ensureActive()
                    archiveFileItemTar(child, entryName, tarOut, onProgress)
                }
            }
        } else {
            val entry = TarArchiveEntry(entryName)
            entry.size = item.size
            tarOut.putArchiveEntry(entry)

            val inResult = item.openInputStream()
            if (inResult is Result.Success) {
                inResult.value.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        tarOut.write(buffer, 0, read)
                        onProgress(read, entryName)
                    }
                }
            }
            tarOut.closeArchiveEntry()
        }
    }
}
