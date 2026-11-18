package com.trove.server.ftp.commands

import com.trove.server.ftp.FtpSession
import java.io.Writer

class QuitCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        writer.writeResponse(221, "Goodbye.")
        session.controlSocket.close()
    }
}
