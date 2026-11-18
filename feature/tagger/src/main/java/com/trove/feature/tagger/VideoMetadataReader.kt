package com.trove.feature.tagger

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.mp4.Mp4Directory
import com.drew.metadata.mp4.media.Mp4VideoDirectory
import com.drew.metadata.mp4.media.Mp4SoundDirectory
import com.drew.metadata.avi.AviDirectory
import com.drew.metadata.mov.QuickTimeDirectory
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only video metadata reader backed by metadata-extractor.
 *
 * metadata-extractor parses MP4/MOV/AVI container boxes and extracts
 * technical metadata (resolution, duration, codec, frame rate, creation date)
 * plus embedded title/artist atoms when present.
 *
 * **Why read-only?** Writing to video containers requires re-muxing or
 * careful atom patching. MiX itself only reads video metadata through the
 * tagger engine — editable video tags go through the audio path (M4A atoms
 * are identical to MP4 atoms, and JAudioTagger can handle them). For true
 * MP4 video tag editing, a future iteration can add mp4parser/isoparser.
 */
object VideoMetadataReader : TroveMetadataEditor {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "3gp", "3g2", "avi", "mkv", "webm",
        "wmv", "flv", "ts", "mts", "m2ts"
    )

    override fun canEdit(item: FileItem): Boolean = false // read-only

    override suspend fun readMetadata(item: FileItem): Result<MetadataBundle> =
        withContext(Dispatchers.IO) {
            try {
                val stream = when (val r = item.openInputStream()) {
                    is Result.Success -> r.value
                    is Result.Failure -> return@withContext Result.Failure(r.exception)
                }

                val metadata = stream.buffered().use { buffered ->
                    ImageMetadataReader.readMetadata(buffered)
                }

                val extra = linkedMapOf<String, String>()

                // Collect everything into extra
                for (directory in metadata.directories) {
                    for (tag in directory.tags) {
                        val key = "${directory.name}/${tag.tagName}"
                        val value = tag.description ?: continue
                        extra[key] = value
                    }
                }

                // Pull out named fields from known MP4/MOV/AVI directories
                val mp4Dir   = metadata.getFirstDirectoryOfType(Mp4Directory::class.java)
                val mp4Video = metadata.getFirstDirectoryOfType(Mp4VideoDirectory::class.java)
                val mp4Sound = metadata.getFirstDirectoryOfType(Mp4SoundDirectory::class.java)
                val qtDir    = metadata.getFirstDirectoryOfType(QuickTimeDirectory::class.java)
                val aviDir   = metadata.getFirstDirectoryOfType(AviDirectory::class.java)

                // Duration (seconds → ms)
                val durationSec = mp4Dir?.safeDouble(Mp4Directory.TAG_DURATION)
                    ?: qtDir?.safeDouble(QuickTimeDirectory.TAG_DURATION)
                val durationMs = durationSec?.let { (it * 1000).toLong() }

                // Dimensions
                val width = mp4Video?.safeInt(Mp4VideoDirectory.TAG_WIDTH)
                    ?: aviDir?.safeInt(AviDirectory.TAG_WIDTH)
                val height = mp4Video?.safeInt(Mp4VideoDirectory.TAG_HEIGHT)
                    ?: aviDir?.safeInt(AviDirectory.TAG_HEIGHT)

                // Creation date
                val creationDate = mp4Dir?.safeString(Mp4Directory.TAG_CREATION_TIME)
                    ?: qtDir?.safeString(QuickTimeDirectory.TAG_CREATION_TIME)

                // Title — MP4 'titl' atom or AVI title
                val title = extra.entries.firstOrNull { (k, _) ->
                    k.contains("Title", ignoreCase = true)
                }?.value

                // Audio info from the MP4 sound track
                val sampleRate = mp4Sound?.safeInt(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE)
                val channels   = mp4Sound?.safeInt(Mp4SoundDirectory.TAG_NUMBER_OF_CHANNELS)

                val bundle = MetadataBundle(
                    title        = title,
                    width        = width,
                    height       = height,
                    durationMs   = durationMs,
                    sampleRate   = sampleRate,
                    channels     = channels,
                    creationDate = creationDate,
                    extra        = extra
                )
                bundle.source = MetadataBundle.FormatFamily.VIDEO
                Result.Success(bundle)
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

    override suspend fun writeMetadata(
        item: FileItem,
        bundle: MetadataBundle
    ): Result<Unit> = Result.Failure(
        UnsupportedOperationException(
            "Video metadata writing is not supported. " +
            "For MP4/M4V audio-atom editing, use AudioTagEditor on .m4a files."
        )
    )

    // ---- Helpers ----

    private fun com.drew.metadata.Directory.safeString(tagType: Int): String? = try {
        getString(tagType)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun com.drew.metadata.Directory.safeInt(tagType: Int): Int? = try {
        getInt(tagType)
    } catch (_: Exception) { null }

    private fun com.drew.metadata.Directory.safeDouble(tagType: Int): Double? = try {
        getDouble(tagType)
    } catch (_: Exception) { null }
}
