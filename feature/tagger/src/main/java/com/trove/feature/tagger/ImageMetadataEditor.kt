package com.trove.feature.tagger

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.iptc.IptcDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.xmp.XmpDirectory
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Image metadata editor.
 *
 * **Read path:** Drew Noakes' metadata-extractor (Apache-2.0). It reads
 * EXIF, XMP, IPTC, ICC, and format-specific directories from JPEG, PNG,
 * TIFF, WebP, HEIF, and many RAW formats — richer than ExifInterface.
 *
 * **Write path:** AndroidX ExifInterface (Apache-2.0). It's the
 * Android-blessed way to modify EXIF in-place for the subset of formats
 * it supports (JPEG, PNG, WebP, DNG). MiX uses a similar split:
 * metadata-extractor for display, its own EXIF patcher for writes.
 *
 * The same stage-for-write pattern from [AudioTagEditor] applies: stream
 * to temp → modify → copy back.
 */
object ImageMetadataEditor : TroveMetadataEditor {

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "png", "tiff", "tif", "webp", "jxl",
        "heif", "heic", "avif", "bmp", "gif", "ico",
        "arw", "cr2", "cr3", "nef", "orf", "rw2", "dng", "raf", "pef", "srw"
    )

    override fun canEdit(item: FileItem): Boolean {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return ext in TroveMetadataEditor.EXIF_WRITABLE_EXTENSIONS
    }

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

                // Collect all directories into extra first
                for (directory in metadata.directories) {
                    val dirName = directory.name
                    for (tag in directory.tags) {
                        val key = "$dirName/${tag.tagName}"
                        val value = tag.description ?: continue
                        extra[key] = value
                    }
                }

                // Pull out named fields from known directories
                val exifIfd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                val exifSub  = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                val gps      = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
                val iptc     = metadata.getFirstDirectoryOfType(IptcDirectory::class.java)
                val jpeg     = metadata.getFirstDirectoryOfType(JpegDirectory::class.java)
                val png      = metadata.getFirstDirectoryOfType(PngDirectory::class.java)

                // Dimensions
                val width = jpeg?.imageWidth
                    ?: png?.safeInt(PngDirectory.TAG_IMAGE_WIDTH)
                    ?: exifIfd0?.safeInt(ExifIFD0Directory.TAG_IMAGE_WIDTH)
                val height = jpeg?.imageHeight
                    ?: png?.safeInt(PngDirectory.TAG_IMAGE_HEIGHT)
                    ?: exifIfd0?.safeInt(ExifIFD0Directory.TAG_IMAGE_HEIGHT)

                // Date
                val dateOriginal = exifSub?.safeString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                    ?: exifIfd0?.safeString(ExifIFD0Directory.TAG_DATETIME)

                // GPS → extra
                gps?.geoLocation?.let { loc ->
                    extra["GPS/Latitude"] = loc.latitude.toString()
                    extra["GPS/Longitude"] = loc.longitude.toString()
                }

                val bundle = MetadataBundle(
                    title      = iptc?.safeString(IptcDirectory.TAG_HEADLINE)
                                 ?: exifIfd0?.safeString(ExifIFD0Directory.TAG_IMAGE_DESCRIPTION),
                    artists    = iptc?.safeString(IptcDirectory.TAG_BY_LINE)?.let { listOf(it) }
                                 ?: exifIfd0?.safeString(ExifIFD0Directory.TAG_ARTIST)?.let { listOf(it) }
                                 ?: emptyList(),
                    comment    = exifIfd0?.safeString(ExifIFD0Directory.TAG_WIN_COMMENT),
                    width      = width,
                    height     = height,
                    creationDate     = dateOriginal,
                    modificationDate = exifIfd0?.safeString(ExifIFD0Directory.TAG_DATETIME),
                    extra      = extra
                )
                bundle.source = MetadataBundle.FormatFamily.IMAGE
                Result.Success(bundle)
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

    override suspend fun writeMetadata(
        item: FileItem,
        bundle: MetadataBundle
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!canEdit(item)) {
            return@withContext Result.Failure(
                UnsupportedOperationException(
                    "EXIF write not supported for ${item.name}"
                )
            )
        }

        try {
            // Stage to temp
            val tempFile = File.createTempFile("trove_exif_", "_${item.name}")
            try {
                when (val r = item.openInputStream()) {
                    is Result.Success -> r.value.use { src ->
                        tempFile.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    is Result.Failure -> return@withContext Result.Failure(r.exception)
                }

                // Write via ExifInterface
                val exif = ExifInterface(tempFile.absolutePath)

                bundle.title?.let {
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, it)
                }
                bundle.artists.firstOrNull()?.let {
                    exif.setAttribute(ExifInterface.TAG_ARTIST, it)
                }
                bundle.comment?.let {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, it)
                }
                bundle.creationDate?.let {
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it)
                }

                // Write extra EXIF attributes that the user may have edited
                bundle.extra.forEach { (key, value) ->
                    // Only write back keys that look like EXIF tag names
                    if (key.startsWith("Exif/") || key.startsWith("GPS/")) {
                        // Map to ExifInterface constants if possible
                        mapExifKey(key)?.let { exifTag ->
                            exif.setAttribute(exifTag, value)
                        }
                    }
                }

                exif.saveAttributes()

                // Copy back
                tempFile.inputStream().use { src ->
                    when (val r = item.openOutputStream(append = false)) {
                        is Result.Success -> r.value.use { dst ->
                            src.copyTo(dst)
                        }
                        is Result.Failure -> return@withContext Result.Failure(r.exception)
                    }
                }

                Result.Success(Unit)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    // ---- Helpers ----

    private fun Directory.safeString(tagType: Int): String? = try {
        getString(tagType)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun Directory.safeInt(tagType: Int): Int? = try {
        getInt(tagType)
    } catch (_: Exception) { null }

    /**
     * Map our internal key format ("Exif/Tag Name") back to an ExifInterface
     * constant. This covers the most commonly edited tags.
     */
    private fun mapExifKey(key: String): String? {
        val tagName = key.substringAfter('/')
        return EXIF_TAG_MAP[tagName]
    }

    private val EXIF_TAG_MAP = mapOf(
        "Make" to ExifInterface.TAG_MAKE,
        "Model" to ExifInterface.TAG_MODEL,
        "Software" to ExifInterface.TAG_SOFTWARE,
        "Copyright" to ExifInterface.TAG_COPYRIGHT,
        "Image Description" to ExifInterface.TAG_IMAGE_DESCRIPTION,
        "Artist" to ExifInterface.TAG_ARTIST,
        "User Comment" to ExifInterface.TAG_USER_COMMENT,
        "Date/Time Original" to ExifInterface.TAG_DATETIME_ORIGINAL,
        "Date/Time" to ExifInterface.TAG_DATETIME,
        "GPS Latitude" to ExifInterface.TAG_GPS_LATITUDE,
        "GPS Longitude" to ExifInterface.TAG_GPS_LONGITUDE,
        "GPS Altitude" to ExifInterface.TAG_GPS_ALTITUDE,
    )
}
