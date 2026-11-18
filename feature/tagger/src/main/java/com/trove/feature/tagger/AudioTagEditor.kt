package com.trove.feature.tagger

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.AudioHeader
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * Audio metadata editor backed by JAudioTagger.
 *
 * ## Stage-for-write pattern (from MiX reverse-engineering)
 *
 * JAudioTagger requires a [java.io.File] with random access. FileItem only
 * exposes streams. So we:
 *
 * 1. Stream the FileItem into a temp file on internal storage.
 * 2. Let JAudioTagger read/write the temp file (ID3, Vorbis, MP4 atoms, etc.).
 * 3. On write: copy the modified temp file back to the original FileItem
 *    via [FileItem.copyTo], then delete the temp.
 *
 * This is exactly what MiX does via `Tagger$FileInfoListener.createTempFile()`
 * and `commit()`. The 256 KB mark/reset buffer MiX uses is unnecessary here
 * because we stage the whole file — simpler and equally correct for files that
 * fit in device storage.
 */
object AudioTagEditor : TroveMetadataEditor {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "m4p",
        "mp4", "aac", "wma", "wav", "aiff", "aif", "dsf", "dff",
        "ape", "wv", "mpc", "tta"
    )

    override fun canEdit(item: FileItem): Boolean {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    override suspend fun readMetadata(item: FileItem): Result<MetadataBundle> =
        withContext(Dispatchers.IO) {
            try {
                val tempFile = stageToTemp(item)
                try {
                    val audioFile = AudioFileIO.read(tempFile)
                    val header = audioFile.audioHeader
                    val tag = audioFile.tag // may be null for untagged files

                    Result.Success(buildBundle(header, tag))
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

    override suspend fun writeMetadata(
        item: FileItem,
        bundle: MetadataBundle
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempFile = stageToTemp(item)
            try {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                applyBundle(tag, bundle)
                audioFile.commit()

                // Copy the modified temp file back to the original location.
                // This is the "commit → rename" dance from MiX's Tagger engine.
                val tempItem = object : FileItem {
                    override val name = tempFile.name
                    override val path = tempFile.absolutePath
                    override val size = tempFile.length()
                    override val lastModified = tempFile.lastModified()
                    override val isDirectory = false
                    override val permissions = item.permissions
                    override val mimeType = item.mimeType

                    override suspend fun listFiles() = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun openInputStream() =
                        Result.Success(tempFile.inputStream() as java.io.InputStream)
                    override suspend fun openOutputStream(append: Boolean) =
                        Result.Success(tempFile.outputStream() as java.io.OutputStream)
                    override suspend fun createFile(name: String) = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun createDirectory(name: String) = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun delete() = Result.Success(Unit)
                    override suspend fun rename(newName: String) = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun copyTo(dest: FileItem) = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun moveTo(destDir: FileItem, newName: String) =
                        Result.Failure(UnsupportedOperationException("temp file"))
                    override suspend fun findChild(name: String) = Result.Failure(
                        UnsupportedOperationException("temp file")
                    )
                    override suspend fun getParent() = Result.Success<FileItem?>(null)
                    override suspend fun exists() = tempFile.exists()
                }

                // Write the modified content back through FileItem's stream API
                val srcStream = tempItem.openInputStream()
                val dstStream = item.openOutputStream(append = false)
                when (dstStream) {
                    is Result.Success -> {
                        srcStream.value.use { src ->
                            dstStream.value.use { dst ->
                                src.copyTo(dst, bufferSize = 8192)
                            }
                        }
                        Result.Success(Unit)
                    }
                    is Result.Failure -> Result.Failure(dstStream.exception)
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    // ---- Internal helpers ----

    /**
     * Stage a [FileItem] to a local temp file. Mirrors MiX's
     * `Tagger$FileInfoListener.createTempFile()`.
     */
    private suspend fun stageToTemp(item: FileItem): File {
        val tempFile = File.createTempFile("trove_tag_", "_${item.name}")
        when (val stream = item.openInputStream()) {
            is Result.Success -> stream.value.use { src ->
                tempFile.outputStream().use { dst ->
                    src.copyTo(dst, bufferSize = 8192)
                }
            }
            is Result.Failure -> throw stream.exception
        }
        return tempFile
    }

    private fun buildBundle(header: AudioHeader, tag: Tag?): MetadataBundle {
        val bundle = MetadataBundle(
            title       = tag?.safeGet(FieldKey.TITLE),
            artists     = tag?.safeGet(FieldKey.ARTIST)?.split("; ", ", ")
                            ?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: emptyList(),
            album       = tag?.safeGet(FieldKey.ALBUM),
            albumArtist = tag?.safeGet(FieldKey.ALBUM_ARTIST),
            genre       = tag?.safeGet(FieldKey.GENRE),
            year        = tag?.safeGet(FieldKey.YEAR)?.toIntOrNull(),
            comment     = tag?.safeGet(FieldKey.COMMENT),
            trackNumber = tag?.safeGet(FieldKey.TRACK)?.toIntOrNull(),
            trackTotal  = tag?.safeGet(FieldKey.TRACK_TOTAL)?.toIntOrNull(),
            discNumber  = tag?.safeGet(FieldKey.DISC_NO)?.toIntOrNull(),
            discTotal   = tag?.safeGet(FieldKey.DISC_TOTAL)?.toIntOrNull(),
            composer    = tag?.safeGet(FieldKey.COMPOSER),
            lyrics      = tag?.safeGet(FieldKey.LYRICS),
            coverArt    = tag?.firstArtwork?.binaryData,
            coverArtMimeType = tag?.firstArtwork?.mimeType,

            // Audio technical info from the header
            durationMs   = (header.trackLength * 1000).toLong(),
            bitRate      = header.bitRateAsNumber.toInt(),
            sampleRate   = header.sampleRateAsNumber,
            channels     = parseChannelCount(header.channels),
            encodingType = header.encodingType
        )
        bundle.source = MetadataBundle.FormatFamily.AUDIO
        return bundle
    }

    private fun applyBundle(tag: Tag, bundle: MetadataBundle) {
        bundle.title?.let { tag.setField(FieldKey.TITLE, it) }
        if (bundle.artists.isNotEmpty()) {
            tag.setField(FieldKey.ARTIST, bundle.artists.joinToString("; "))
        }
        bundle.album?.let { tag.setField(FieldKey.ALBUM, it) }
        bundle.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        bundle.genre?.let { tag.setField(FieldKey.GENRE, it) }
        bundle.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
        bundle.comment?.let { tag.setField(FieldKey.COMMENT, it) }
        bundle.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
        bundle.trackTotal?.let { tag.setField(FieldKey.TRACK_TOTAL, it.toString()) }
        bundle.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
        bundle.discTotal?.let { tag.setField(FieldKey.DISC_TOTAL, it.toString()) }
        bundle.composer?.let { tag.setField(FieldKey.COMPOSER, it) }
        bundle.lyrics?.let { tag.setField(FieldKey.LYRICS, it) }

        // Cover art
        bundle.coverArt?.let { bytes ->
            tag.deleteArtworkField()
            val artwork: Artwork = ArtworkFactory.getNew()
            artwork.binaryData = bytes
            artwork.mimeType = bundle.coverArtMimeType ?: "image/jpeg"
            tag.setField(artwork)
        }
    }

    /** Safely get a tag field, returning null instead of throwing. */
    private fun Tag.safeGet(key: FieldKey): String? = try {
        getFirst(key).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Parse channel description like "Stereo", "Mono", "5.1" to an int. */
    private fun parseChannelCount(desc: String): Int = when {
        desc.equals("Mono", ignoreCase = true) -> 1
        desc.equals("Stereo", ignoreCase = true) -> 2
        desc.equals("Joint Stereo", ignoreCase = true) -> 2
        desc.contains("5.1", ignoreCase = true) -> 6
        desc.contains("7.1", ignoreCase = true) -> 8
        else -> desc.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}
