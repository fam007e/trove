package com.trove.feature.tagger

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the tagger module.
 *
 * These test the pure-logic parts that don't need Android or real files:
 * - MetadataBundle equality / hashCode (ByteArray-aware)
 * - Format detection via TroveMetadataEditor.forItem()
 * - Extension classification coverage
 * - MetadataBundle builder patterns
 *
 * Integration tests (actual file read/write) require instrumented tests
 * with real audio/image/video files on a device or emulator.
 */
class TroveMetadataEditorTest {

    // ---- MetadataBundle tests ----

    @Test
    fun `MetadataBundle equals ignores reference identity for coverArt`() {
        val art = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val a = MetadataBundle(title = "Song", coverArt = art.copyOf())
        val b = MetadataBundle(title = "Song", coverArt = art.copyOf())
        assertEquals(a, b)
    }

    @Test
    fun `MetadataBundle not equal when coverArt differs`() {
        val a = MetadataBundle(title = "Song", coverArt = byteArrayOf(1, 2, 3))
        val b = MetadataBundle(title = "Song", coverArt = byteArrayOf(4, 5, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `MetadataBundle equals with null coverArt`() {
        val a = MetadataBundle(title = "Song", coverArt = null)
        val b = MetadataBundle(title = "Song", coverArt = null)
        assertEquals(a, b)
    }

    @Test
    fun `MetadataBundle hashCode consistent with equals`() {
        val art = byteArrayOf(1, 2, 3, 4)
        val a = MetadataBundle(title = "Test", artists = listOf("A"), coverArt = art.copyOf())
        val b = MetadataBundle(title = "Test", artists = listOf("A"), coverArt = art.copyOf())
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `source defaults to UNKNOWN`() {
        val bundle = MetadataBundle()
        assertEquals(MetadataBundle.FormatFamily.UNKNOWN, bundle.source)
    }

    // ---- Format detection tests ----

    @Test
    fun `forItem returns AudioTagEditor for mp3`() {
        val item = stubFileItem("track.mp3")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected AudioTagEditor", editor is AudioTagEditor)
    }

    @Test
    fun `forItem returns AudioTagEditor for flac`() {
        val item = stubFileItem("album.flac")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected AudioTagEditor", editor is AudioTagEditor)
    }

    @Test
    fun `forItem returns AudioTagEditor for m4a`() {
        val item = stubFileItem("podcast.m4a")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected AudioTagEditor", editor is AudioTagEditor)
    }

    @Test
    fun `forItem returns ImageMetadataEditor for jpg`() {
        val item = stubFileItem("photo.jpg")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected ImageMetadataEditor", editor is ImageMetadataEditor)
    }

    @Test
    fun `forItem returns ImageMetadataEditor for heic`() {
        val item = stubFileItem("IMG_0001.heic")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected ImageMetadataEditor", editor is ImageMetadataEditor)
    }

    @Test
    fun `forItem returns ImageMetadataEditor for arw raw`() {
        val item = stubFileItem("DSC00123.arw")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected ImageMetadataEditor", editor is ImageMetadataEditor)
    }

    @Test
    fun `forItem returns VideoMetadataReader for mkv`() {
        val item = stubFileItem("movie.mkv")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected VideoMetadataReader", editor is VideoMetadataReader)
    }

    @Test
    fun `forItem returns BookMetadataReader for pdf`() {
        val item = stubFileItem("document.pdf")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected BookMetadataReader", editor is BookMetadataReader)
    }

    @Test
    fun `forItem returns BookMetadataReader for epub`() {
        val item = stubFileItem("novel.epub")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected BookMetadataReader", editor is BookMetadataReader)
    }

    @Test
    fun `forItem returns BookMetadataReader for mobi`() {
        val item = stubFileItem("kindle.mobi")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected BookMetadataReader", editor is BookMetadataReader)
    }

    @Test
    fun `forItem returns null for unknown extension`() {
        val item = stubFileItem("readme.txt")
        assertNull(TroveMetadataEditor.forItem(item))
    }

    @Test
    fun `forItem is case-insensitive`() {
        val item = stubFileItem("TRACK.MP3")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue("Expected AudioTagEditor for uppercase", editor is AudioTagEditor)
    }

    @Test
    fun `isTaggable returns true for supported formats`() {
        assertTrue(TroveMetadataEditor.isTaggable(stubFileItem("a.mp3")))
        assertTrue(TroveMetadataEditor.isTaggable(stubFileItem("b.jpg")))
        assertTrue(TroveMetadataEditor.isTaggable(stubFileItem("c.pdf")))
        assertTrue(TroveMetadataEditor.isTaggable(stubFileItem("d.epub")))
    }

    @Test
    fun `isTaggable returns false for unsupported formats`() {
        assertFalse(TroveMetadataEditor.isTaggable(stubFileItem("readme.txt")))
        assertFalse(TroveMetadataEditor.isTaggable(stubFileItem("app.apk")))
        assertFalse(TroveMetadataEditor.isTaggable(stubFileItem("data.json")))
    }

    // ---- canEdit tests ----

    @Test
    fun `AudioTagEditor canEdit for all audio formats`() {
        assertTrue(AudioTagEditor.canEdit(stubFileItem("x.mp3")))
        assertTrue(AudioTagEditor.canEdit(stubFileItem("x.flac")))
        assertTrue(AudioTagEditor.canEdit(stubFileItem("x.ogg")))
        assertTrue(AudioTagEditor.canEdit(stubFileItem("x.opus")))
    }

    @Test
    fun `ImageMetadataEditor canEdit only for EXIF-writable formats`() {
        assertTrue(ImageMetadataEditor.canEdit(stubFileItem("x.jpg")))
        assertTrue(ImageMetadataEditor.canEdit(stubFileItem("x.png")))
        assertTrue(ImageMetadataEditor.canEdit(stubFileItem("x.webp")))
        // HEIC/AVIF are readable but not writable via ExifInterface
        assertFalse(ImageMetadataEditor.canEdit(stubFileItem("x.heic")))
        assertFalse(ImageMetadataEditor.canEdit(stubFileItem("x.avif")))
    }

    @Test
    fun `VideoMetadataReader canEdit is always false`() {
        assertFalse(VideoMetadataReader.canEdit(stubFileItem("x.mp4")))
        assertFalse(VideoMetadataReader.canEdit(stubFileItem("x.mkv")))
    }

    @Test
    fun `BookMetadataReader canEdit is always false`() {
        assertFalse(BookMetadataReader.canEdit(stubFileItem("x.pdf")))
        assertFalse(BookMetadataReader.canEdit(stubFileItem("x.epub")))
    }

    // ---- mp4 ambiguity: audio takes priority ----

    @Test
    fun `mp4 extension dispatches to AudioTagEditor (audio priority)`() {
        val item = stubFileItem("file.mp4")
        val editor = TroveMetadataEditor.forItem(item)
        assertTrue(
            "MP4 should go to AudioTagEditor first (editable audio atoms)",
            editor is AudioTagEditor
        )
    }

    // ---- Helper ----

    /** Minimal FileItem stub for extension-based tests. */
    private fun stubFileItem(name: String): FileItem = object : FileItem {
        override val name = name
        override val path = "/storage/$name"
        override val size = 0L
        override val lastModified = 0L
        override val isDirectory = false
        override val permissions = PosixPermissions.DEFAULT
        override val mimeType = ""
        override suspend fun listFiles(): Result<List<FileItem>> = Result.Success(emptyList())
        override suspend fun openInputStream(): Result<java.io.InputStream> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun openOutputStream(append: Boolean): Result<java.io.OutputStream> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun createFile(name: String): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun createDirectory(name: String): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun delete(): Result<Unit> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun rename(newName: String): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun copyTo(dest: FileItem): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun findChild(name: String): Result<FileItem> =
            Result.Failure(UnsupportedOperationException())
        override suspend fun getParent(): Result<FileItem?> =
            Result.Success(null)
        override suspend fun exists() = false
    }
}
