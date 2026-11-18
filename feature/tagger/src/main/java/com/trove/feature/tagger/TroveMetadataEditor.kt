package com.trove.feature.tagger

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result

/**
 * Sealed interface for metadata engines. Each implementation covers a format
 * family and operates entirely through [FileItem] streams — never java.io.File.
 *
 * The companion [forItem] factory inspects the file extension to pick the right
 * editor, mirroring MiX's `Tagger$FileInfoListener` dispatch in [Commands.java]
 * but without the obfuscated reflective loading.
 */
sealed interface TroveMetadataEditor {

    /** Read all available metadata from [item]. */
    suspend fun readMetadata(item: FileItem): Result<MetadataBundle>

    /**
     * Write [bundle] back to [item]. Returns [Result.Failure] if the format
     * is read-only or the write fails.
     */
    suspend fun writeMetadata(item: FileItem, bundle: MetadataBundle): Result<Unit>

    /** True if this editor can modify the given item's tags (not just read). */
    fun canEdit(item: FileItem): Boolean

    companion object {
        // ---- Extension sets mirroring MiX's Commands.getExifSupportedExtensions() ----

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "m4p",
            "mp4", "aac", "wma", "wav", "aiff", "aif", "dsf", "dff",
            "ape", "wv", "mpc", "tta"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "png", "tiff", "tif", "webp", "jxl",
            "heif", "heic", "avif", "bmp", "gif", "ico",
            // RAW formats
            "arw", "cr2", "cr3", "nef", "orf", "rw2", "dng", "raf", "pef", "srw"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "3gp", "3g2", "avi", "mkv", "webm",
            "wmv", "flv", "ts", "mts", "m2ts"
        )

        private val PDF_EXTENSIONS = setOf("pdf")

        private val EPUB_EXTENSIONS = setOf("epub")

        private val MOBI_EXTENSIONS = setOf("mobi", "azw", "azw3", "prc")

        /**
         * Pick the right [TroveMetadataEditor] for a given [FileItem], or null
         * if no editor supports the format.
         *
         * Priority order matches MiX: audio → image → video → pdf → ebook.
         * When an extension like "mp4" is ambiguous (audio *or* video), we try
         * audio first because audio metadata is richer and editable.
         */
        fun forItem(item: FileItem): TroveMetadataEditor? {
            val ext = item.name.substringAfterLast('.', "").lowercase()
            return when {
                ext in AUDIO_EXTENSIONS -> AudioTagEditor
                ext in IMAGE_EXTENSIONS -> ImageMetadataEditor
                ext in VIDEO_EXTENSIONS -> VideoMetadataReader
                ext in PDF_EXTENSIONS   -> BookMetadataReader
                ext in EPUB_EXTENSIONS  -> BookMetadataReader
                ext in MOBI_EXTENSIONS  -> BookMetadataReader
                else -> null
            }
        }

        /** Quick check without instantiating an editor. */
        fun isTaggable(item: FileItem): Boolean = forItem(item) != null

        /**
         * File extensions where EXIF write-back is supported.
         * Directly from MiX: Commands.getExifSupportedExtensions().
         */
        val EXIF_WRITABLE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "png", "tiff", "tif",
            "arw", "cr2", "nef", "orf", "rw2", "jxl", "webp"
        )
    }
}
