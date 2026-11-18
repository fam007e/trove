package com.trove.server.ftp

import android.util.Log
import com.trove.core.fs.FileItem
import com.trove.server.ftp.commands.FtpCommandFactory
import com.trove.server.ftp.commands.writeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class FtpConnectionHandler(
    private val socket: Socket,
    private val rootDirectory: FileItem,
    private val scope: CoroutineScope,
    private val credentials: Map<String, String> = emptyMap()
) {
    fun handle() {
        scope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream().writer()
            val session = FtpSession(rootDirectory, rootDirectory, socket, credentials)

            writer.writeResponse(220, "Trove FTP Server Ready")

            try {
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(" ", limit = 2)
                    val cmdName = parts[0].uppercase()
                    val argument = if (parts.size > 1) parts[1] else ""

                    val command = FtpCommandFactory.getCommand(cmdName)
                    if (command != null) {
                        if (!session.isAuthenticated && cmdName != "USER" && cmdName != "PASS" && cmdName != "QUIT") {
                            writer.writeResponse(530, "Not logged in.")
                        } else {
                            command.execute(session, argument, writer)
                        }
                    } else {
                        writer.writeResponse(502, "Command not implemented.")
                    }
                }
            } catch (e: Exception) {
                Log.e("FtpServer", "Connection error: ${e.message}", e)
            } finally {
                socket.close()
                session.passiveServer?.close()
            }
        }
    }
}
