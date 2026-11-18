package com.trove.server.ftp

import com.trove.core.fs.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket

class FtpSession(
    val rootDirectory: FileItem,
    var currentDirectory: FileItem,
    val controlSocket: Socket,
    val credentials: Map<String, String> = emptyMap()
) {
    var user: String? = null
    var isAuthenticated: Boolean = false
    var isBinaryMode: Boolean = true
    var renameFrom: FileItem? = null
    
    var passiveServer: ServerSocket? = null
    var activeDataAddress: String? = null
    var activeDataPort: Int = -1

    suspend fun getDataSocket(): Socket? = withContext(Dispatchers.IO) {
        return@withContext when {
            passiveServer != null -> {
                try {
                    val socket = passiveServer?.accept()
                    passiveServer?.close()
                    passiveServer = null
                    socket
                } catch (e: Exception) {
                    null
                }
            }
            activeDataAddress != null -> {
                try {
                    Socket(activeDataAddress, activeDataPort)
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }
}
