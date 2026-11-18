package com.trove.server.ftp.commands

import com.trove.server.ftp.FtpSession
import java.io.Writer
import java.net.ServerSocket
import java.net.InetAddress

class PasvCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        try {
            val server = ServerSocket(0) // Bind to any free port
            session.passiveServer = server
            
            val address = session.controlSocket.localAddress
            val port = server.localPort
            
            val addrBytes = address.address
            val p1 = port shr 8
            val p2 = port and 0xFF
            
            val response = String.format(
                "%d,%d,%d,%d,%d,%d",
                addrBytes[0].toInt() and 0xFF,
                addrBytes[1].toInt() and 0xFF,
                addrBytes[2].toInt() and 0xFF,
                addrBytes[3].toInt() and 0xFF,
                p1, p2
            )
            
            writer.writeResponse(227, "Entering Passive Mode ($response).")
        } catch (e: Exception) {
            writer.writeResponse(425, "Can't open data connection.")
        }
    }
}
