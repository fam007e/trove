package com.trove.feature.tagger

/**
 * Universal metadata container. Normalises tags from audio (ID3/Vorbis/MP4),
 * image (EXIF/XMP/IPTC), video (MP4 boxes), PDF (DocumentInfo + XMP), and
 * ebook (EPUB OPF / MOBI EXTH) into one structure.
 *
 * Named fields cover the tags every format family shares; `extra` captures
 * format-specific keys the UI may still want to display (camera model,
 * GPS coordinates, PDF creator tool, ISBN, etc.).
 */
data class MetadataBundle(
    // ---- Common across most format families ----
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val comment: String? = null,

    // ---- Audio-specific ----
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val composer: String? = null,
    val lyrics: String? = null,
    val coverArt: ByteArray? = null,
    val coverArtMimeType: String? = null,

    // ---- Audio technical ----
    val durationMs: Long? = null,
    val bitRate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val encodingType: String? = null,

    // ---- Image / Video dimensions ----
    val width: Int? = null,
    val height: Int? = null,

    // ---- Document ----
    val author: String? = null,
    val subject: String? = null,
    val keywords: List<String> = emptyList(),
    val creator: String? = null,       // PDF creator tool, EPUB publisher
    val producer: String? = null,      // PDF producer
    val creationDate: String? = null,  // ISO-8601 string
    val modificationDate: String? = null,
    val pageCount: Int? = null,

    // ---- Catch-all for format-specific tags ----
    val extra: Map<String, String> = emptyMap()
) {
    /** The format family this bundle was read from. */
    var source: FormatFamily = FormatFamily.UNKNOWN

    enum class FormatFamily {
        AUDIO, IMAGE, VIDEO, PDF, EPUB, MOBI, UNKNOWN
    }

    // coverArt is a ByteArray, so we need explicit equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetadataBundle) return false
        return title == other.title &&
            artists == other.artists &&
            album == other.album &&
            genre == other.genre &&
            year == other.year &&
            trackNumber == other.trackNumber &&
            extra == other.extra &&
            coverArt.contentEquals(other.coverArt)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artists.hashCode()
        result = 31 * result + (coverArt?.contentHashCode() ?: 0)
        return result
    }
}
