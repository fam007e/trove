package com.trove.server.sftp

import com.trove.core.crypto.auth.CredentialManager
import org.apache.sshd.server.session.ServerSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.security.KeyPairGenerator
import java.security.PublicKey

class SftpAuthTest {

    private class MockCredentialManager(private val map: Map<String, String>) {
        fun getHash(user: String): String? = map[user]
    }

    @Test
    fun testPasswordAuthenticatorValid() {
        val testPassword = "secretPassword123"
        val hash = BCrypt.hashpw(testPassword, BCrypt.gensalt(10))

        val authenticator = object : org.apache.sshd.server.auth.password.PasswordAuthenticator {
            override fun authenticate(username: String, password: String, session: ServerSession?): Boolean {
                if (username != "testuser") return false
                return BCrypt.checkpw(password, hash)
            }
        }

        assertTrue(authenticator.authenticate("testuser", "secretPassword123", null))
        assertFalse(authenticator.authenticate("testuser", "wrongPassword", null))
        assertFalse(authenticator.authenticate("unknownUser", "secretPassword123", null))
    }

    @Test
    fun testPublicKeyAuthenticator() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair1 = kpg.generateKeyPair()
        val keyPair2 = kpg.generateKeyPair()

        val authorized = mapOf("admin" to listOf(keyPair1.public))
        val authenticator = SftpPublicKeyAuthenticator(authorized)

        // Same session dummy
        val dummySession = object : ServerSession by (java.lang.reflect.Proxy.newProxyInstance(
            ServerSession::class.java.classLoader,
            arrayOf(ServerSession::class.java)
        ) { _, _, _ -> null } as ServerSession) {}

        assertTrue(authenticator.authenticate("admin", keyPair1.public, dummySession))
        assertFalse(authenticator.authenticate("admin", keyPair2.public, dummySession))
        assertFalse(authenticator.authenticate("guest", keyPair1.public, dummySession))
    }
}
