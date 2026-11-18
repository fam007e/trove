package com.trove.feature.archive

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry

class ArchiveTree private constructor(
    val sourceItem: FileItem,
    val archiveType: ArchiveType,
    val rootNode: ArchiveEntryNode,
    val password: String? = null
) {
    suspend fun openEntryStream(entryPath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            when (archiveType) {
                ArchiveType.ZIP -> openZipEntryStream(entryPath)
                ArchiveType.SEVEN_Z -> open7zEntryStream(entryPath)
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> openTarEntryStream(entryPath)
                else -> Result.Failure(UnsupportedOperationException("Stream extraction not supported for $archiveType"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private suspend fun openZipEntryStream(entryPath: String): Result<InputStream> {
        val channelResult = SeekableFileItemByteChannel.open(sourceItem)
        if (channelResult is Result.Failure) return Result.Failure(channelResult.exception)
        val channel = (channelResult as Result.Success).value

        return try {
            val commonsZip = CommonsZipFile(channel)
            val entry = commonsZip.getEntry(entryPath)
                ?: return Result.Failure(NoSuchElementException("Entry $entryPath not found in archive"))

            val stream = commonsZip.getInputStream(entry)
            val baos = ByteArrayOutputStream()
            stream.use { it.copyTo(baos) }
            commonsZip.close()
            channel.close()
            Result.Success(ByteArrayInputStream(baos.toByteArray()))
        } catch (e: Exception) {
            channel.close()
            Result.Failure(e)
        }
    }

    private suspend fun open7zEntryStream(entryPath: String): Result<InputStream> {
        val channelResult = SeekableFileItemByteChannel.open(sourceItem)
        if (channelResult is Result.Failure) return Result.Failure(channelResult.exception)
        val channel = (channelResult as Result.Success).value

        return try {
            val builder = SevenZFile.builder().setSeekableByteChannel(channel)
            if (!password.isNullOrEmpty()) {
                builder.setPassword(password.toCharArray())
            }
            val sevenZFile = builder.get()
            var matched = false
            val baos = ByteArrayOutputStream()

            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val normalized = entry.name.trim().replace('\\', '/').trim('/')
                if (normalized == entryPath) {
                    matched = true
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (sevenZFile.read(buffer).also { read = it } != -1) {
                        baos.write(buffer, 0, read)
                    }
                    break
                }
                entry = sevenZFile.nextEntry
            }

            sevenZFile.close()
            channel.close()

            if (matched) {
                Result.Success(ByteArrayInputStream(baos.toByteArray()))
            } else {
                Result.Failure(NoSuchElementException("Entry $entryPath not found in 7z archive"))
            }
        } catch (e: Exception) {
            channel.close()
            Result.Failure(e)
        }
    }

    private suspend fun openTarEntryStream(entryPath: String): Result<InputStream> {
        val streamResult = sourceItem.openInputStream()
        if (streamResult is Result.Failure) return Result.Failure(streamResult.exception)
        val baseIn = (streamResult as Result.Success).value

        return try {
            val decompressedIn = wrapDecompressor(baseIn, archiveType)
            val tarIn = TarArchiveInputStream(decompressedIn)
            val baos = ByteArrayOutputStream()
            var matched = false

            var entry: TarArchiveEntry? = tarIn.nextTarEntry
            while (entry != null) {
                val normalized = entry.name.trim().replace('\\', '/').trim('/')
                if (normalized == entryPath) {
                    matched = true
                    tarIn.copyTo(baos)
                    break
                }
                entry = tarIn.nextTarEntry
            }

            tarIn.close()
            baseIn.close()

            if (matched) {
                Result.Success(ByteArrayInputStream(baos.toByteArray()))
            } else {
                Result.Failure(NoSuchElementException("Entry $entryPath not found in tar archive"))
            }
        } catch (e: Exception) {
            baseIn.close()
            Result.Failure(e)
        }
    }

    companion object {
        suspend fun build(sourceItem: FileItem, password: String? = null): Result<ArchiveTree> = withContext(Dispatchers.IO) {
            val archiveType = ArchiveType.fromFileItem(sourceItem)
                ?: return@withContext Result.Failure(IllegalArgumentException("Unsupported archive format for ${sourceItem.name}"))

            val root = ArchiveEntryNode.createRoot()

            try {
                when (archiveType) {
                    ArchiveType.ZIP -> indexZip(sourceItem, root)
                    ArchiveType.SEVEN_Z -> index7z(sourceItem, root, password)
                    ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> indexTar(sourceItem, root, archiveType)
                    else -> return@withContext Result.Failure(UnsupportedOperationException("Archive indexer for $archiveType not implemented"))
                }
                Result.Success(ArchiveTree(sourceItem, archiveType, root, password))
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

        private suspend fun indexZip(sourceItem: FileItem, root: ArchiveEntryNode) {
            val channelResult = SeekableFileItemByteChannel.open(sourceItem)
            if (channelResult is Result.Failure) throw channelResult.exception
            val channel = (channelResult as Result.Success).value

            try {
                val zip = CommonsZipFile(channel)
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry: ZipArchiveEntry = entries.nextElement()
                    ArchiveEntryNode.insertPath(
                        root = root,
                        rawPath = entry.name,
                        isDirectory = entry.isDirectory,
                        size = if (entry.isDirectory) 0L else entry.size,
                        lastModified = entry.time
                    )
                }
                zip.close()
            } finally {
                channel.close()
            }
        }

        private suspend fun index7z(sourceItem: FileItem, root: ArchiveEntryNode, password: String?) {
            val channelResult = SeekableFileItemByteChannel.open(sourceItem)
            if (channelResult is Result.Failure) throw channelResult.exception
            val channel = (channelResult as Result.Success).value

            try {
                val builder = SevenZFile.builder().setSeekableByteChannel(channel)
                if (!password.isNullOrEmpty()) {
                    builder.setPassword(password.toCharArray())
                }
                val sevenZFile = builder.get()
                for (entry in sevenZFile.entries) {
                    ArchiveEntryNode.insertPath(
                        root = root,
                        rawPath = entry.name,
                        isDirectory = entry.isDirectory,
                        size = if (entry.isDirectory) 0L else entry.size,
                        lastModified = entry.lastModifiedDate?.time ?: 0L
                    )
                }
                sevenZFile.close()
            } finally {
                channel.close()
            }
        }

        private suspend fun indexTar(sourceItem: FileItem, root: ArchiveEntryNode, type: ArchiveType) {
            val inResult = sourceItem.openInputStream()
            if (inResult is Result.Failure) throw inResult.exception
            val baseIn = (inResult as Result.Success).value

            try {
                val decompressed = wrapDecompressor(baseIn, type)
                val tarIn = TarArchiveInputStream(decompressed)
                var entry: TarArchiveEntry? = tarIn.nextTarEntry
                while (entry != null) {
                    ArchiveEntryNode.insertPath(
                        root = root,
                        rawPath = entry.name,
                        isDirectory = entry.isDirectory,
                        size = if (entry.isDirectory) 0L else entry.size,
                        lastModified = entry.modTime.time
                    )
                    entry = tarIn.nextTarEntry
                }
                tarIn.close()
            } finally {
                baseIn.close()
            }
        }

        private fun wrapDecompressor(base: InputStream, type: ArchiveType): InputStream {
            return when (type) {
                ArchiveType.TAR_GZ -> GzipCompressorInputStream(base)
                ArchiveType.TAR_BZ2 -> BZip2CompressorInputStream(base)
                ArchiveType.TAR_XZ -> XZCompressorInputStream(base)
                else -> base
            }
        }
    }
}
