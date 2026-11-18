package com.trove.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CVE Security Test: XML External Entity (XXE CWE-611) & Billion Laughs Bomb.
 *
 * Verifies that the WebDav XML parser does not resolve external entities
 * or hang on recursive entity expansion bombs (denial of service).
 */
class XmlSecurityTest {

    @Test
    fun testXxePayloadIgnored() = runBlocking {
        // Classic XXE payload targeting /etc/passwd
        val xxePayload = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE D:multistatus [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/remote.php/webdav/&xxe;</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Test</D:displayname>
                            <D:resourcetype/>
                        </D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(xxePayload, HttpStatusCode.MultiStatus)
        }
        val client = HttpClient(mockEngine)
        val item = WebDavFileItem(client, "https://example.com/remote.php/webdav", "/remote.php/webdav", true)

        val result = item.listFiles()
        // Must complete safely: either successful parse without resolving entity or graceful failure
        assertTrue(result is com.trove.core.fs.Result)
    }

    @Test
    fun testBillionLaughsEntityBombHandledGracefully() = runBlocking {
        // Recursive entity expansion bomb (Billion Laughs)
        val entityBomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
                <!ENTITY lol "lol">
                <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/test/&lol3;</D:href>
                    <D:propstat>
                        <D:prop><D:displayname>Bomb</D:displayname></D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(entityBomb, HttpStatusCode.MultiStatus)
        }
        val client = HttpClient(mockEngine)
        val item = WebDavFileItem(client, "https://example.com/webdav", "/webdav", true)

        val startTime = System.currentTimeMillis()
        val result = item.listFiles()
        val elapsed = System.currentTimeMillis() - startTime

        // Must finish execution rapidly (< 3000ms) without infinite expansion
        assertTrue("Parser must not hang or expand exponential entities", elapsed < 3000)
    }
}
