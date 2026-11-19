package com.trove.core.net.cloud

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * TeraBox file/folder implementation backed by TeraBox Web API.
 * Uses secure session cookie (`ndus`) authentication over TLS.
 */
class TeraBoxFileItem(
    private val ndusToken: String,
    val fsId: Long,
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream"
) : FileItem {

    companion object {
        private const val API_BASE = "https://www.terabox.com/api"
        private const val APP_ID = "250528"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun root(ndusToken: String): TeraBoxFileItem {
            return TeraBoxFileItem(
                ndusToken = ndusToken,
                fsId = 0L,
                name = "TeraBox",
                path = "/",
                size = 0L,
                lastModified = System.currentTimeMillis(),
                isDirectory = true
            )
        }
    }

    private val httpClient = HttpClient(OkHttp)

    override val permissions: PosixPermissions =
        if (isDirectory) PosixPermissions.DIRECTORY_DEFAULT else PosixPermissions.DEFAULT

    private fun cookieHeader(): String = "ndus=$ndusToken"

    override suspend fun listFiles(): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            if (!isDirectory) {
                return@withContext Result.Failure(IllegalStateException("Cannot list a file"))
            }

            val targetDir = if (path.isEmpty()) "/" else path
            val encodedDir = java.net.URLEncoder.encode(targetDir, "UTF-8")
            val url = "$API_BASE/list?dir=$encodedDir&order=time&desc=1&clienttype=0&app_id=$APP_ID&web=1&page=1&num=100"

            val response = httpClient.get(url) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                return@withContext Result.Failure(Exception("TeraBox API returned errno: $errno (Invalid or expired ndus cookie)"))
            }

            val list = json.optJSONArray("list") ?: JSONArray()
            val items = mutableListOf<FileItem>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val id = item.optLong("fs_id", 0L)
                val itemName = item.getString("server_filename")
                val isDir = item.optInt("isdir", 0) == 1
                val itemSize = if (isDir) 0L else item.optLong("size", 0L)
                val mtime = item.optLong("server_mtime", 0L) * 1000L
                val itemPath = item.optString("path", "$targetDir/$itemName")

                items.add(
                    TeraBoxFileItem(
                        ndusToken = ndusToken,
                        fsId = id,
                        name = itemName,
                        path = itemPath,
                        size = itemSize,
                        lastModified = mtime,
                        isDirectory = isDir
                    )
                )
            }

            Result.Success(items)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openInputStream(): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            if (isDirectory) {
                return@withContext Result.Failure(IllegalStateException("Cannot download a directory"))
            }

            // Query dlink for direct download
            val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
            val url = "$API_BASE/download?path=$encodedPath&app_id=$APP_ID"
            val metaResponse = httpClient.get(url) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(metaResponse.bodyAsText())
            val dlink = json.optString("dlink", "")
            if (dlink.isEmpty()) {
                return@withContext Result.Failure(Exception("No download link provided by TeraBox"))
            }

            val downloadResp = httpClient.get(dlink) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            Result.Success(downloadResp.bodyAsChannel().toInputStream())
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        return Result.Failure(UnsupportedOperationException("TeraBox uses chunked HTTP upload API — use createFile()"))
    }

    override suspend fun createFile(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        Result.Failure(UnsupportedOperationException("TeraBox file creation requires slicing upload API"))
    }

    override suspend fun createDirectory(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val targetPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
            val response = httpClient.submitForm(
                url = "$API_BASE/create",
                formParameters = Parameters.build {
                    append("path", targetPath)
                    append("isdir", "1")
                    append("size", "0")
                    append("block_list", "[]")
                }
            ) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            val errno = json.optInt("errno", -1)
            if (errno == 0) {
                Result.Success(
                    TeraBoxFileItem(
                        ndusToken = ndusToken,
                        fsId = json.optLong("fs_id", 0L),
                        name = name,
                        path = targetPath,
                        size = 0L,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = true
                    )
                )
            } else {
                Result.Failure(Exception("Failed to create folder on TeraBox: errno $errno"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val filelist = JSONArray().put(path).toString()
            val response = httpClient.submitForm(
                url = "$API_BASE/filemanager?opera=delete",
                formParameters = Parameters.build {
                    append("filelist", filelist)
                }
            ) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            if (json.optInt("errno", -1) == 0) {
                Result.Success(Unit)
            } else {
                Result.Failure(Exception("Delete failed on TeraBox: ${json.optInt("errno")}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun rename(newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val filelist = JSONArray().put(
                JSONObject().apply {
                    put("path", path)
                    put("newname", newName)
                }
            ).toString()

            val response = httpClient.submitForm(
                url = "$API_BASE/filemanager?opera=rename",
                formParameters = Parameters.build {
                    append("filelist", filelist)
                }
            ) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            if (json.optInt("errno", -1) == 0) {
                val parent = path.substringBeforeLast('/', "")
                val newPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
                Result.Success(
                    TeraBoxFileItem(
                        ndusToken = ndusToken,
                        fsId = fsId,
                        name = newName,
                        path = newPath,
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = isDirectory,
                        mimeType = mimeType
                    )
                )
            } else {
                Result.Failure(Exception("Rename failed on TeraBox: ${json.optInt("errno")}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyTo(dest: FileItem): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (dest !is TeraBoxFileItem) {
                val input = openInputStream()
                if (input is Result.Failure) return@withContext Result.Failure(input.exception)
                val created = dest.createFile(name)
                if (created is Result.Failure) return@withContext Result.Failure(created.exception)
                val os = (created as Result.Success).value.openOutputStream()
                if (os is Result.Failure) return@withContext Result.Failure(os.exception)
                (input as Result.Success).value.use { i ->
                    (os as Result.Success).value.use { o -> i.copyTo(o) }
                }
                return@withContext created
            }

            val filelist = JSONArray().put(
                JSONObject().apply {
                    put("path", path)
                    put("dest", dest.path)
                    put("newname", name)
                }
            ).toString()

            val response = httpClient.submitForm(
                url = "$API_BASE/filemanager?opera=copy",
                formParameters = Parameters.build {
                    append("filelist", filelist)
                }
            ) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            if (json.optInt("errno", -1) == 0) {
                Result.Success(
                    TeraBoxFileItem(
                        ndusToken = ndusToken,
                        fsId = fsId,
                        name = name,
                        path = "${dest.path.trimEnd('/')}/$name",
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = isDirectory,
                        mimeType = mimeType
                    )
                )
            } else {
                Result.Failure(Exception("Copy failed on TeraBox: ${json.optInt("errno")}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (destDir !is TeraBoxFileItem) {
                val copied = copyTo(destDir)
                if (copied is Result.Success) delete()
                return@withContext copied
            }

            val filelist = JSONArray().put(
                JSONObject().apply {
                    put("path", path)
                    put("dest", destDir.path)
                    put("newname", newName)
                }
            ).toString()

            val response = httpClient.submitForm(
                url = "$API_BASE/filemanager?opera=move",
                formParameters = Parameters.build {
                    append("filelist", filelist)
                }
            ) {
                header("Cookie", cookieHeader())
                header("User-Agent", USER_AGENT)
            }
            val json = JSONObject(response.bodyAsText())
            if (json.optInt("errno", -1) == 0) {
                Result.Success(
                    TeraBoxFileItem(
                        ndusToken = ndusToken,
                        fsId = fsId,
                        name = newName,
                        path = "${destDir.path.trimEnd('/')}/$newName",
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        isDirectory = isDirectory,
                        mimeType = mimeType
                    )
                )
            } else {
                Result.Failure(Exception("Move failed on TeraBox: ${json.optInt("errno")}"))
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun findChild(name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        val list = listFiles()
        if (list is Result.Success) {
            val child = list.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (child != null) Result.Success(child) else Result.Failure(java.io.FileNotFoundException("Child not found: $name"))
        } else {
            Result.Failure((list as Result.Failure).exception)
        }
    }

    override suspend fun getParent(): Result<FileItem?> = Result.Success(null)

    override suspend fun exists(): Boolean = true
}
