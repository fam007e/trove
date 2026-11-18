package com.trove.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlParserTest {

    @Test
    fun `test WebDav multistatus parsing with namespaces and self-closing collection`() = runBlocking {
        val xmlResponse = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/remote.php/webdav/Documents/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Documents</D:displayname>
                            <D:resourcetype>
                                <D:collection/>
                            </D:resourcetype>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
                <D:response>
                    <D:href>/remote.php/webdav/report.pdf</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Quarterly Report</D:displayname>
                            <D:getcontentlength>1048576</D:getcontentlength>
                            <D:resourcetype/>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(xmlResponse, HttpStatusCode.MultiStatus)
        }
        val client = HttpClient(mockEngine)

        val root = WebDavFileItem(client, "https://example.com/remote.php/webdav", "/", "webdav", _isDirectory = true)
        val listResult = root.listFiles()
        assertTrue(listResult is com.trove.core.fs.Result.Success)

        val items = (listResult as com.trove.core.fs.Result.Success).value
        assertEquals(2, items.size)

        val dirItem = items[0]
        assertEquals("Documents", dirItem.name)
        assertTrue(dirItem.isDirectory)

        val fileItem = items[1]
        assertEquals("Quarterly Report", fileItem.name)
        assertEquals(1048576L, fileItem.size)
        assertFalse(fileItem.isDirectory)
    }

    @Test
    fun `test WebDav multistatus parsing with default namespace and CDATA`() = runBlocking {
        val xmlResponse = """
            <?xml version="1.0" encoding="utf-8"?>
            <multistatus xmlns="DAV:">
                <response>
                    <href>/webdav/special&amp;file.txt</href>
                    <propstat>
                        <prop>
                            <displayname><![CDATA[Special & File]]></displayname>
                            <getcontentlength>42</getcontentlength>
                            <resourcetype></resourcetype>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(xmlResponse, HttpStatusCode.MultiStatus)
        }
        val client = HttpClient(mockEngine)

        val root = WebDavFileItem(client, "https://example.com/webdav", "/", "webdav", _isDirectory = true)
        val listResult = root.listFiles()
        assertTrue(listResult is com.trove.core.fs.Result.Success)

        val items = (listResult as com.trove.core.fs.Result.Success).value
        assertEquals(1, items.size)

        val item = items[0]
        assertEquals("Special & File", item.name)
        assertEquals(42L, item.size)
        assertFalse(item.isDirectory)
    }
}
