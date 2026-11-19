package com.trove.core.net.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for cloud file item models and 2FA/MFA challenge state machines.
 */
class CloudFileItemTest {

    @Test
    fun `TeraBoxFileItem root initialization has correct paths and permissions`() {
        val root = TeraBoxFileItem.root("fake_ndus_token_123")
        assertEquals("TeraBox", root.name)
        assertEquals("/", root.path)
        assertTrue(root.isDirectory)
        assertTrue(root.permissions.ownerRead)
        assertTrue(root.permissions.ownerExecute)
        assertEquals("inode/directory", root.mimeType)
    }

    @Test
    fun `TeraBoxFileItem child item retains correct file metadata`() {
        val file = TeraBoxFileItem(
            ndusToken = "test_token",
            fsId = 1001L,
            name = "document.pdf",
            path = "/Documents/document.pdf",
            size = 1048576L,
            lastModified = 1700000000000L,
            isDirectory = false,
            mimeType = "application/pdf"
        )
        assertEquals("document.pdf", file.name)
        assertEquals("/Documents/document.pdf", file.path)
        assertEquals(1048576L, file.size)
        assertFalse(file.isDirectory)
        assertEquals("application/pdf", file.mimeType)
    }

    @Test
    fun `MediaFireFileItem path and directory attributes are consistent`() {
        val folder = MediaFireFileItem(
            sessionToken = "session_abc",
            folderOrQuickKey = "fld123",
            name = "Music",
            path = "/Music/",
            size = 0L,
            lastModified = 1700000000000L,
            isDirectory = true
        )
        assertTrue(folder.isDirectory)
        assertEquals("inode/directory", folder.mimeType)
        assertTrue(folder.permissions.ownerExecute)

        val file = MediaFireFileItem(
            sessionToken = "session_abc",
            folderOrQuickKey = "qk456",
            name = "song.mp3",
            path = "/Music/song.mp3",
            size = 5242880L,
            lastModified = 1700000000000L,
            isDirectory = false,
            mimeType = "audio/mpeg"
        )
        assertFalse(file.isDirectory)
        assertEquals("audio/mpeg", file.mimeType)
    }

    @Test
    fun `MfaResult sealed class correctly represents 2FA challenge workflow`() {
        val success = MfaResult.Success("session_token_ok")
        assertEquals("session_token_ok", success.value)

        val requiresTotp = MfaResult.RequiresTotp("MEGA.nz", "user@example.com")
        assertEquals("MEGA.nz", requiresTotp.serviceName)
        assertEquals("user@example.com", requiresTotp.accountHint)

        val failure = MfaResult.Failure(IllegalArgumentException("Invalid code"))
        assertEquals("Invalid code", failure.exception.message)
    }
}
