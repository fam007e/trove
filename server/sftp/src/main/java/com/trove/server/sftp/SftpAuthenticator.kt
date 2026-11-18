package com.trove.server.sftp

import com.trove.core.crypto.auth.CredentialManager
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.mindrot.jbcrypt.BCrypt
import java.security.PublicKey

class SftpPasswordAuthenticator(
    private val credentialManager: CredentialManager
) : PasswordAuthenticator {

    override fun authenticate(
        username: String,
        password: String,
        session: ServerSession
    ): Boolean {
        val expectedHash = credentialManager.getHash(username) ?: return false
        return try {
            BCrypt.checkpw(password, expectedHash)
        } catch (_: Exception) {
            false
        }
    }
}

class SftpPublicKeyAuthenticator(
    private val authorizedKeys: Map<String, List<PublicKey>> = emptyMap()
) : PublickeyAuthenticator {

    override fun authenticate(
        username: String,
        key: PublicKey,
        session: ServerSession
    ): Boolean {
        val allowedKeys = authorizedKeys[username] ?: return false
        return allowedKeys.any { it == key }
    }
}
