package com.trove.core.net.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * JVM unit tests for PkceUtils — RFC 7636 compliance.
 *
 * PEDAGOGICAL NOTE:
 * We test the PKCE primitives in isolation from any network call.
 * The spec defines exact encoding rules; we verify character sets,
 * length constraints, and the SHA-256 → base64url chain.
 */
class PkceUtilsTest {

    @Test
    fun `generateCodeVerifier produces valid length`() {
        val verifier = PkceUtils.generateCodeVerifier(43)
        assertEquals(43, verifier.length)

        val verifier128 = PkceUtils.generateCodeVerifier(128)
        assertEquals(128, verifier128.length)
    }

    @Test
    fun `generateCodeVerifier uses only unreserved characters`() {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
        repeat(20) {
            val verifier = PkceUtils.generateCodeVerifier()
            assertTrue(
                "Verifier contains invalid character: $verifier",
                verifier.all { it in unreserved }
            )
        }
    }

    @Test
    fun `generateCodeVerifier default length is 64`() {
        val verifier = PkceUtils.generateCodeVerifier()
        assertEquals(64, verifier.length)
    }

    @Test
    fun `generateCodeVerifier is random across calls`() {
        val a = PkceUtils.generateCodeVerifier()
        val b = PkceUtils.generateCodeVerifier()
        assertNotEquals("Two verifiers should not be identical", a, b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateCodeVerifier rejects length below 43`() {
        PkceUtils.generateCodeVerifier(42)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateCodeVerifier rejects length above 128`() {
        PkceUtils.generateCodeVerifier(129)
    }

    @Test
    fun `computeCodeChallenge is SHA256 then base64url`() {
        // RFC 7636 Appendix B test vector:
        // verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        // expected challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = PkceUtils.computeCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `computeCodeChallenge produces URL-safe output without padding`() {
        val verifier = PkceUtils.generateCodeVerifier()
        val challenge = PkceUtils.computeCodeChallenge(verifier)

        // No padding
        assertTrue("Challenge should not contain '='", '=' !in challenge)
        // URL-safe: no '+' or '/'
        assertTrue("Challenge should not contain '+'", '+' !in challenge)
        assertTrue("Challenge should not contain '/'", '/' !in challenge)
        // Should be non-empty
        assertTrue("Challenge should not be empty", challenge.isNotEmpty())
    }

    @Test
    fun `round-trip verifier to challenge is deterministic`() {
        val verifier = PkceUtils.generateCodeVerifier()
        val c1 = PkceUtils.computeCodeChallenge(verifier)
        val c2 = PkceUtils.computeCodeChallenge(verifier)
        assertEquals("Same verifier should produce same challenge", c1, c2)
    }
}
