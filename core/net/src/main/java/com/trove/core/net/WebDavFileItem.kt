package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * FileItem implementation for WebDAV.
 * 
 * DESIGN NOTE:
 * Uses Ktor for HTTP/DAV methods and performs basic XML parsing of 
 * PROPFIND MultiStatus responses.
 */
class WebDavFileItem(
    private val client: HttpClient,
    private val baseUrl: String,
    override val path: String,
    private val _name: String,
    private val _size: Long = 0,
    private val _lastModified: Long = 0,
    private val _isDirectory: Boolean = false
) : FileItem {

    override val name: String get() = _name
    override val size: Long get() = _size
    override val lastModified: Long get() = _lastModified
    override val isDirectory: Boolean get() = _isDirectory
    override val permissions: PosixPermissions
        get() = if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT
    override val mimeType: String 
        get() = if (isDirectory) "inode/directory" else "application/octet-stream"

    private fun getFullUrl(): String = "$baseUrl$path".replace("//", "/")

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.request(getFullUrl()) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
            }
            
            if (response.status == HttpStatusCode.MultiStatus) {
                val body = response.bodyAsText()
                val files = parsePropFindResponse(body)
                val currentPathString: String = this@WebDavFileItem.path
                Result.Success(files.filter { (it as WebDavFileItem).path != currentPathString })
            } else {
                Result.Failure(IOException("WebDAV PROPFIND failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private fun parsePropFindResponse(xml: String): List<WebDavFileItem> {
        val items = mutableListOf<WebDavFileItem>()
        try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    // Security: Disable DOCTYPE and external entity processing (XXE CWE-611 / XML Entity Bomb defense)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (_: Exception) {
                }
            }
            val builder = dbf.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            
            val responseNodes = if (doc.getElementsByTagNameNS("*", "response").length > 0) {
                doc.getElementsByTagNameNS("*", "response")
            } else {
                doc.getElementsByTagName("response")
            }

            val prefixToRemove = if (baseUrl.contains("://")) {
                val pathPart = baseUrl.substringAfter("://").substringAfter("/", "")
                if (pathPart.isNotEmpty()) "/$pathPart" else ""
            } else ""

            for (i in 0 until responseNodes.length) {
                val respElem = responseNodes.item(i) as? Element ?: continue
                
                val hrefNodes = if (respElem.getElementsByTagNameNS("*", "href").length > 0) {
                    respElem.getElementsByTagNameNS("*", "href")
                } else {
                    respElem.getElementsByTagName("href")
                }
                val rawHref = hrefNodes.item(0)?.textContent?.trim() ?: continue

                val decodedPath = URLDecoder.decode(rawHref, "UTF-8")
                    .let { if (prefixToRemove.isNotEmpty()) it.removePrefix(prefixToRemove) else it }
                    .removePrefix(baseUrl)
                    .let { if (it.isEmpty()) "/" else it }

                val displayNodes = if (respElem.getElementsByTagNameNS("*", "displayname").length > 0) {
                    respElem.getElementsByTagNameNS("*", "displayname")
                } else {
                    respElem.getElementsByTagName("displayname")
                }
                val name = displayNodes.item(0)?.textContent?.trim()
                    ?: decodedPath.trimEnd('/').substringAfterLast('/')

                val hasCollection = (respElem.getElementsByTagNameNS("*", "collection").length > 0) ||
                                    (respElem.getElementsByTagName("collection").length > 0)
                val isDir = hasCollection || decodedPath.endsWith('/')

                val lengthNodes = if (respElem.getElementsByTagNameNS("*", "getcontentlength").length > 0) {
                    respElem.getElementsByTagNameNS("*", "getcontentlength")
                } else {
                    respElem.getElementsByTagName("getcontentlength")
                }
                val length = lengthNodes.item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L

                items.add(
                    WebDavFileItem(
                        client,
                        baseUrl,
                        decodedPath,
                        name,
                        length,
                        _isDirectory = isDir
                    )
                )
            }
        } catch (_: Exception) {
        }
        return items
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = client.get(getFullUrl())
            if (response.status == HttpStatusCode.OK) {
                Result.Success(response.bodyAsChannel().toInputStream())
            } else {
                Result.Failure(IOException("WebDAV GET failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> = withContext(Dispatchers.IO) {
        Result.Failure(UnsupportedOperationException("WebDAV doesn't support streaming output directly. Use copyTo."))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newPath = "${path.trimEnd('/')}/$name"
            val response = client.put("$baseUrl$newPath".replace("//", "/")) {
                setBody("")
            }
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent) {
                Result.Success(WebDavFileItem(client, baseUrl, newPath, name))
            } else {
                Result.Failure(IOException("WebDAV PUT failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val newPath = "${path.trimEnd('/')}/$name"
            val response = client.request("$baseUrl$newPath".replace("//", "/")) {
                method = HttpMethod("MKCOL")
            }
            if (response.status == HttpStatusCode.Created) {
                Result.Success(WebDavFileItem(client, baseUrl, newPath, name, _isDirectory = true))
            } else {
                Result.Failure(IOException("WebDAV MKCOL failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = client.delete(getFullUrl())
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
                Result.Success(Unit)
            } else {
                Result.Failure(IOException("WebDAV DELETE failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parentPath = path.substringBeforeLast("/", "/")
            val newPath = "$parentPath/$newName".replace("//", "/")
            val response = client.request(getFullUrl()) {
                method = HttpMethod("MOVE")
                header("Destination", "$baseUrl$newPath".replace("//", "/"))
            }
            if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent) {
                Result.Success(WebDavFileItem(client, baseUrl, newPath, newName, _isDirectory = isDirectory))
            } else {
                Result.Failure(IOException("WebDAV MOVE failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        if (dest is WebDavFileItem && dest.baseUrl == baseUrl) {
            try {
                val destPath = "${dest.path.trimEnd('/')}/$name"
                val response = client.request(getFullUrl()) {
                    method = HttpMethod("COPY")
                    header("Destination", "$baseUrl$destPath".replace("//", "/"))
                }
                if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent) {
                    Result.Success(WebDavFileItem(client, baseUrl, destPath, name, _isDirectory = isDirectory))
                } else {
                    Result.Failure(IOException("WebDAV COPY failed: ${response.status}"))
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        } else {
            // Re-implement the default copyTo logic directly since FileItem is an interface
            try {
                val targetFileResult = dest.createFile(name)
                if (targetFileResult is Result.Failure) return@withContext targetFileResult as Result<FileItem>
                val targetFile = (targetFileResult as Result.Success).value

                openInputStream().flatMap { input ->
                    targetFile.openOutputStream(false).map { output ->
                        input.use { i -> output.use { o -> i.copyTo(o) } }
                        targetFile
                    }
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        if (destDir is WebDavFileItem && destDir.baseUrl == baseUrl) {
            try {
                val destPath = "${destDir.path.trimEnd('/')}/$newName"
                val response = client.request(getFullUrl()) {
                    method = HttpMethod("MOVE")
                    header("Destination", "$baseUrl$destPath".replace("//", "/"))
                }
                if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.NoContent) {
                    Result.Success(WebDavFileItem(client, baseUrl, destPath, newName, _isDirectory = isDirectory))
                } else {
                    Result.Failure(IOException("WebDAV MOVE failed: ${response.status}"))
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        } else {
            // Re-implement the default moveTo logic directly since FileItem is an interface
            copyTo(destDir).flatMap { copiedItem ->
                if (copiedItem.name != newName) {
                    copiedItem.rename(newName).flatMap { renamed ->
                        delete().map { renamed }
                    }
                } else {
                    delete().map { copiedItem }
                }
            }
        }
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val childPath = "${path.trimEnd('/')}/$name"
            val response: HttpResponse = client.request("$baseUrl$childPath".replace("//", "/")) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
            }
            if (response.status == HttpStatusCode.MultiStatus || response.status == HttpStatusCode.OK) {
                Result.Success(WebDavFileItem(client, baseUrl, childPath, name))
            } else {
                Result.Failure(IOException("Child not found: $name"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = withContext(Dispatchers.IO) {
        if (path == "/" || path.isEmpty()) return@withContext Result.Success(null)
        val parentPath = path.substringBeforeLast("/", "")
        val finalParentPath = if (parentPath.isEmpty()) "/" else parentPath
        val name = finalParentPath.trimEnd('/').substringAfterLast('/', "Root")
        Result.Success(WebDavFileItem(client, baseUrl, finalParentPath, name, _isDirectory = true))
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = client.request(getFullUrl()) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
            }
            response.status == HttpStatusCode.MultiStatus || response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }
}
