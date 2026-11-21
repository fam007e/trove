package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import io.mockk.*
import kotlinx.coroutines.*
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import io.ktor.client.engine.mock.*
import io.ktor.http.*

/**
 * Integrated test verifying that FTPFileItem.copyTo works with the reentrant SessionManager.
 */
class IntegratedVfsTest {

    @Test
    fun testFtpCopyToSameSessionReentrancy(): Unit = runBlocking {
        // Generous timeout: the assertion checks reentrancy CORRECTNESS, not
        // speed. Under full-project builds other modules compile concurrently
        // and a 5s budget made this fail spuriously (timing, not deadlock).
        withTimeout(60000) {
            val mockClient = mockk<FTPClient>(relaxed = true)
            val sessionManager = SessionManager(mockClient)
            
            val srcFtpFile = mockk<org.apache.commons.net.ftp.FTPFile>()
            every { srcFtpFile.name } returns "src.txt"
            every { srcFtpFile.size } returns 10L
            every { srcFtpFile.timestamp } returns java.util.Calendar.getInstance()
            every { srcFtpFile.isDirectory } returns false
            every { srcFtpFile.hasPermission(any(), any()) } returns true
            
            val srcItem = FTPFileItem(sessionManager, srcFtpFile, "/src")
            val destDir = FTPFileItem(sessionManager, mockk(relaxed = true), "/dest")

            val destFtpFile = mockk<org.apache.commons.net.ftp.FTPFile>()
            every { destFtpFile.name } returns "src.txt"

            // Setup the sequence with explicit success codes
            every { mockClient.enterLocalPassiveMode() } just runs
            every { mockClient.replyCode } returns 226
            every { mockClient.replyString } returns "226 Success"
            
            // Stub mlistFile to return success for existence checks
            every { mockClient.mlistFile(any()) } returns destFtpFile
            
            // Stub streaming methods
            val inputStream = ByteArrayInputStream("1234567890".toByteArray())
            val outputStream = ByteArrayOutputStream()
            every { mockClient.retrieveFileStream(any()) } returns inputStream
            every { mockClient.storeFileStream(any()) } returns outputStream
            
            // storeFile is used for createFile
            every { mockClient.storeFile(any(), any()) } returns true
            every { mockClient.completePendingCommand() } returns true

            val result = srcItem.copyTo(destDir)
            
            assertTrue("Integrated copyTo should succeed", result is Result.Success)
            
            verify { 
                mockClient.retrieveFileStream("/src/src.txt")
                mockClient.storeFileStream("/dest/src.txt")
                mockClient.completePendingCommand()
            }
        }
    }
}

class WebDavVfsTest {
    @Test
    fun testWebDavListFilesParsing(): Unit = runBlocking {
        val mockEngine = io.ktor.client.engine.mock.MockEngine { request ->
            respond(
                content = """
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/remote/file.txt</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:displayname>file.txt</d:displayname>
                                    <d:getcontentlength>123</d:getcontentlength>
                                    <d:resourcetype/>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent(),
                status = io.ktor.http.HttpStatusCode.MultiStatus,
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/xml")
            )
        }
        val client = io.ktor.client.HttpClient(mockEngine)
        val item = WebDavFileItem(client, "http://localhost", "/remote", "remote", _isDirectory = true)
        
        val result = item.listFiles()
        assertTrue(result is Result.Success)
        val files = (result as Result.Success).value
        assertEquals(1, files.size)
        assertEquals("file.txt", files[0].name)
        assertEquals(123L, files[0].size)
    }
}
