package com.trove.feature.media

import android.net.Uri
import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class TroveMediaTest {

    private fun createStubFileItem(name: String, path: String, content: ByteArray = ByteArray(0)): FileItem {
        return object : FileItem {
            override val name: String = name
            override val path: String = path
            override val size: Long = content.size.toLong()
            override val lastModified: Long = System.currentTimeMillis()
            override val isDirectory: Boolean = false
            override val permissions: PosixPermissions = PosixPermissions.DEFAULT
            override val mimeType: String = ""

            override suspend fun listFiles(): Result<List<FileItem>> = Result.Success(emptyList())
            override suspend fun openInputStream(): Result<InputStream> = Result.Success(ByteArrayInputStream(content))
            override suspend fun openOutputStream(append: Boolean): Result<OutputStream> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun createFile(name: String): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun createDirectory(name: String): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun delete(): Result<Unit> = Result.Success(Unit)
            override suspend fun rename(newName: String): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun copyTo(dest: FileItem): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun findChild(name: String): Result<FileItem> =
                Result.Failure(UnsupportedOperationException())
            override suspend fun getParent(): Result<FileItem?> = Result.Success(null)
            override suspend fun exists(): Boolean = true
        }
    }

    @Test
    fun `test MediaHelper audio detection`() {
        assertTrue(MediaHelper.isAudio("song.mp3"))
        assertTrue(MediaHelper.isAudio("track.FLAC"))
        assertTrue(MediaHelper.isAudio("audio.ogg"))
        assertTrue(MediaHelper.isAudio("voice.m4a"))
        assertTrue(MediaHelper.isAudio("record.wav"))
        assertTrue(MediaHelper.isAudio("stream.opus"))
        assertFalse(MediaHelper.isAudio("video.mp4"))
        assertFalse(MediaHelper.isAudio("document.pdf"))
    }

    @Test
    fun `test MediaHelper video detection`() {
        assertTrue(MediaHelper.isVideo("movie.mp4"))
        assertTrue(MediaHelper.isVideo("clip.MKV"))
        assertTrue(MediaHelper.isVideo("web.webm"))
        assertTrue(MediaHelper.isVideo("old.avi"))
        assertTrue(MediaHelper.isVideo("camera.mov"))
        assertFalse(MediaHelper.isVideo("song.mp3"))
        assertFalse(MediaHelper.isVideo("image.jpg"))
    }

    @Test
    fun `test MediaHelper isMedia general classification`() {
        assertTrue(MediaHelper.isMedia("track.mp3"))
        assertTrue(MediaHelper.isMedia("movie.mkv"))
        assertFalse(MediaHelper.isMedia("archive.zip"))
        assertFalse(MediaHelper.isMedia("doc.pdf"))
        assertFalse(MediaHelper.isMedia("text.txt"))
    }

    @Test
    fun `test TroveMediaRegistry registration and retrieval`() {
        val item = createStubFileItem("track.mp3", "/storage/emulated/0/Music/track.mp3")
        TroveMediaRegistry.register(item)

        val retrieved = TroveMediaRegistry.get("/storage/emulated/0/Music/track.mp3")
        assertNotNull(retrieved)
        assertEquals(item.path, retrieved?.path)

        TroveMediaRegistry.unregister(item.path)
        assertNull(TroveMediaRegistry.get(item.path))
    }

    @Test
    fun `test TroveMediaRegistry clear`() {
        val item1 = createStubFileItem("1.mp3", "/path/1.mp3")
        val item2 = createStubFileItem("2.mp4", "/path/2.mp4")
        TroveMediaRegistry.register(item1)
        TroveMediaRegistry.register(item2)

        TroveMediaRegistry.clear()
        assertNull(TroveMediaRegistry.get("/path/1.mp3"))
        assertNull(TroveMediaRegistry.get("/path/2.mp4"))
    }
}
