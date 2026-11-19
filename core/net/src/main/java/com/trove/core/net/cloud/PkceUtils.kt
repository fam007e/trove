package com.trove.core.net.cloud

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof Key for Code Exchange) OAuth 2.0 engine — RFC 7636.
 *
 * PEDAGOGICAL NOTES:
 *
 * Public clients (native mobile apps) cannot safely embed a client_secret,
 * because any APK can be decompiled. PKCE solves this:
 *
 *   1. App generates a random `code_verifier` (43–128 chars, URL-safe)
 *   2. App computes `code_challenge = BASE64URL(SHA256(code_verifier))`
 *   3. App opens the browser to the authorization endpoint, passing
 *      `code_challenge` + `code_challenge_method=S256`
 *   4. The user authenticates and grants scopes in the browser
 *   5. The auth server redirects back with an authorization `code`
 *   6. App exchanges the `code` + original `code_verifier` for tokens
 *   7. The server verifies SHA256(code_verifier) == stored challenge
 *
 * This ensures an intercepted `code` is useless without the verifier,
 * which never left the app process.
 *
 * We use a loopback redirect (`http://127.0.0.1:{port}/callback`) so the
 * token exchange happens entirely on-device, never leaving localhost.
 * This is the RFC 8252 §7.3 recommended pattern for native apps.
 */
object PkceUtils {

    private val secureRandom = SecureRandom()

    /**
     * Generate a cryptographically random code verifier.
     * RFC 7636 §4.1: 43–128 characters from [A-Z, a-z, 0-9, "-", ".", "_", "~"].
     */
    fun generateCodeVerifier(length: Int = 64): String {
        require(length in 43..128) { "Code verifier length must be 43–128" }
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return buildString(length) {
            repeat(length) {
                append(unreserved[secureRandom.nextInt(unreserved.length)])
            }
        }
    }

    /**
     * Compute the S256 code challenge from a verifier.
     * RFC 7636 §4.2: BASE64URL(SHA256(ASCII(code_verifier)))
     */
    fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(digest)
    }

    /**
     * Base64url encoding without padding, as per RFC 4648 §5.
     * Uses java.util.Base64 (available API 26+ and pure JVM).
     */
    fun base64UrlEncode(data: ByteArray): String {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }
}
