package com.trove.server.sftp

import com.trove.core.crypto.auth.CredentialManager
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.mindrot.jbcrypt.BCrypt
import java.security.PublicKey

/**
 * Validates passwords against BCrypt hashes supplied by [hashLookup].
 *
 * The lookup is a plain function so the authenticator is unit-testable on
 * the JVM without an Android Context; [CredentialManager] is adapted through
 * the secondary constructor in production.
 */
class SftpPasswordAuthenticator(
    private val hashLookup: (String) -> String?
) : PasswordAuthenticator {

    constructor(credentialManager: CredentialManager) : this(
        { user -> credentialManager.getHash(user) }
    )

    override fun authenticate(
        username: String,
        password: String,
        session: ServerSession
    ): Boolean {
        val expectedHash = hashLookup(username) ?: return false
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
