package com.trove.server.ftp.commands

import com.trove.server.ftp.FtpSession
import java.io.Writer
import org.mindrot.jbcrypt.BCrypt

class UserCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        session.user = argument
        writer.writeResponse(331, "User name okay, need password.")
    }
}

class PassCommand : FtpCommand {
    override suspend fun execute(session: FtpSession, argument: String, writer: Writer) {
        val user = session.user
        val expectedHash = session.credentials[user]
        
        try {
            if (user != null && expectedHash != null && BCrypt.checkpw(argument, expectedHash)) {
                session.isAuthenticated = true
                writer.writeResponse(230, "User logged in, proceed.")
            } else {
                writer.writeResponse(530, "Login incorrect.")
            }
        } catch (e: Exception) {
            // Handle cases where the stored hash is malformed
            writer.writeResponse(530, "Login error.")
        }
    }
}
