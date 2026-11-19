package com.trove.core.net

import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Connection factories turning host/port/credentials into a browsable root
 * [FileItem] for each protocol. Clean-room composition of the existing
 * session/FileItem classes; sessions stay open for the tab's lifetime.
 */
object NetworkConnectionFactory {

    enum class Protocol(val defaultPort: Int, val label: String) {
        FTP(21, "FTP"),
        SFTP(22, "SFTP"),
        SMB(445, "SMB"),
        WEBDAV(80, "WebDAV")
    }

    data class ConnectionSpec(
        val protocol: Protocol,
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        /** Path component: remote path (FTP/SFTP), share (SMB: "share"), base path (WebDAV). */
        val path: String
    )

    suspend fun connect(spec: ConnectionSpec): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            when (spec.protocol) {
                Protocol.FTP -> connectFtp(spec)
                Protocol.SFTP -> connectSftp(spec)
                Protocol.SMB -> connectSmb(spec)
                Protocol.WEBDAV -> connectWebDav(spec)
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private fun connectFtp(spec: ConnectionSpec): Result<FileItem> {
        val client = FTPClient()
        client.connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
        client.connect(spec.host, spec.port)
        client.login(spec.user, spec.password)
        client.enterLocalPassiveMode()
        client.setControlEncoding("UTF-8")

        val session = SessionManager(client)
        val rootPath = spec.path.ifBlank { "/" }
        val rootFile = FTPFile().apply {
            name = ""
            type = FTPFile.DIRECTORY_TYPE
        }
        return Result.Success(FTPFileItem(session, rootFile, rootPath))
    }

    private fun connectSftp(spec: ConnectionSpec): Result<FileItem> {
        val ssh = net.schmizz.sshj.SSHClient()
        ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
        ssh.connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
        ssh.connect(spec.host, spec.port)
        ssh.authPassword(spec.user, spec.password)
        val sftp = ssh.newSFTPClient()

        val session = SessionManager(sftp)
        val rootPath = spec.path.ifBlank { "." }
        val attrs = sftp.stat(rootPath)
        if (attrs.type != net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            sftp.close()
            ssh.disconnect()
            return Result.Failure(IllegalArgumentException("Remote path is not a directory"))
        }
        val name = rootPath.substringAfterLast('/').ifEmpty { rootPath }
        val parent = rootPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        val components = net.schmizz.sshj.sftp.PathComponents(parent, name, rootPath)
        val rootInfo = net.schmizz.sshj.sftp.RemoteResourceInfo(components, attrs)
        return Result.Success(SFTPFileItem(session, rootInfo, attrs))
    }

    private fun connectSmb(spec: ConnectionSpec): Result<FileItem> {
        val base = jcifs.context.BaseContext(jcifs.config.BaseConfiguration(true))
        val ctx = base.withCredentials(
            jcifs.smb.NtlmPasswordAuthenticator(null, spec.user, spec.password)
        )
        val session = SessionManager(ctx)
        val share = spec.path.trim('/')
        val url = "smb://${spec.host}:${spec.port}/" + if (share.isEmpty()) "" else "$share/"
        val root = jcifs.smb.SmbFile(url, ctx)
        if (!root.exists()) {
            return Result.Failure(java.io.IOException("SMB path not found: $url"))
        }
        return Result.Success(SMBFileItem(session, root))
    }

    private fun connectWebDav(spec: ConnectionSpec): Result<FileItem> {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            install(io.ktor.client.plugins.auth.Auth) {
                providers.add(
                    io.ktor.client.plugins.auth.providers.BasicAuthProvider(
                        username = spec.user,
                        password = spec.password,
                        sendWithoutRequest = true
                    )
                )
            }
        }
        val base = buildString {
            append(if (spec.port == 443) "https://" else "http://")
            append(spec.host)
            if (spec.port != 80 && spec.port != 443) append(":").append(spec.port)
        }
        val path = "/" + spec.path.trim('/')
        val rootName = path.trim('/').substringAfterLast('/').ifEmpty { spec.host }
        return Result.Success(WebDavFileItem(client, base, path, rootName, _isDirectory = true))
    }
}
