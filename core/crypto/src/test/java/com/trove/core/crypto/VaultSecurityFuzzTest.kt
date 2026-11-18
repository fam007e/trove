package com.trove.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.Random
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * CVE & Fuzz Security Test: Cryptographic Integrity & Anti-Tampering.
 *
 * Fuzz-tests the TRVE format header parser, chunked AEAD authentication,
 * and verifies that any unauthorized mutation, bit-flip, replay, or truncation
 * triggers an immediate security exception and cannot be silently accepted.
 */
class VaultSecurityFuzzTest {

    private val testKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val testNoncePrefix = ByteArray(8) { (it * 3).toByte() }

    @Test
    fun testCorruptedMagicHeaderFuzz() {
        val random = Random(42)
        // Corrupt magic bytes across multiple randomized runs
        for (i in 0 until 50) {
            val validSalt = ByteArray(32) { 0x01 }
            val validPrefix = ByteArray(8) { 0x02 }
            val header = TrveHeader(salt = validSalt, noncePrefix = validPrefix)

            val baos = ByteArrayOutputStream()
            header.write(baos)
            val headerBytes = baos.toByteArray()

            // Corrupt magic bytes (offsets 0..3)
            val corruptIndex = random.nextInt(4)
            headerBytes[corruptIndex] = (headerBytes[corruptIndex].toInt() xor (random.nextInt(255) + 1)).toByte()

            try {
                TrveHeader.read(ByteArrayInputStream(headerBytes))
                fail("Corrupted magic byte at offset $corruptIndex must fail header validation")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Invalid magic") == true)
            }
        }
    }

    @Test
    fun testCorruptedVersionHeaderFuzz() {
        val validSalt = ByteArray(32) { 0x01 }
        val validPrefix = ByteArray(8) { 0x02 }
        val header = TrveHeader(salt = validSalt, noncePrefix = validPrefix)

        val baos = ByteArrayOutputStream()
        header.write(baos)
        val headerBytes = baos.toByteArray()

        // Corrupt version at offset 4
        headerBytes[4] = 0x99.toByte()

        try {
            TrveHeader.read(ByteArrayInputStream(headerBytes))
            fail("Unsupported version 0x99 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported TRVE version") == true)
        }
    }

    @Test
    fun testBitFlipCiphertextFuzzing() {
        val cipher = TrveChunkedCipher(testKey, testNoncePrefix)
        val plaintext = "Trove Confidential Vault Secret Data Payload".toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.encryptChunk(plaintext, 0, plaintext.size, chunkIndex = 0, isLast = true)

        val random = Random(1337)

        // Fuzz 100 randomized bit-flips across ciphertext and GCM authentication tag
        for (iteration in 0 until 100) {
            val corrupted = ciphertext.clone()
            val targetByte = random.nextInt(corrupted.size)
            val bitMask = (1 shl random.nextInt(8)).toByte()
            corrupted[targetByte] = (corrupted[targetByte].toInt() xor bitMask.toInt()).toByte()

            try {
                cipher.decryptChunk(corrupted, 0, corrupted.size, chunkIndex = 0, isLast = true)
                fail("Iteration $iteration: Bit-flip at offset $targetByte MUST throw AEADBadTagException!")
            } catch (e: GeneralSecurityException) {
                // Expected: AES-GCM authentication verification failure
                assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException || e.message?.contains("Tag") == true)
            }
        }
    }

    @Test
    fun testChunkReplayAttackRejectedByAAD() {
        val cipher = TrveChunkedCipher(testKey, testNoncePrefix)
        val chunk0 = "Chunk 0 Plaintext".toByteArray()
        val chunk1 = "Chunk 1 Plaintext".toByteArray()

        val enc0 = cipher.encryptChunk(chunk0, 0, chunk0.size, chunkIndex = 0, isLast = false)
        val enc1 = cipher.encryptChunk(chunk1, 0, chunk1.size, chunkIndex = 1, isLast = true)

        // Attempt replay: try to decrypt Chunk 1 bytes as Chunk 0
        try {
            cipher.decryptChunk(enc1, 0, enc1.size, chunkIndex = 0, isLast = false)
            fail("Replaying chunk 1 as chunk 0 must fail AAD integrity check")
        } catch (e: GeneralSecurityException) {
            // Expected: AAD mismatch between chunkIndex 1 and 0
        }

        // Attempt replay: try to decrypt Chunk 0 with isLast = true
        try {
            cipher.decryptChunk(enc0, 0, enc0.size, chunkIndex = 0, isLast = true)
            fail("Altering isLast AAD flag must fail GCM verification")
        } catch (e: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun testTruncatedCiphertextChunkFails() {
        val cipher = TrveChunkedCipher(testKey, testNoncePrefix)
        val plaintext = "Large enough payload to test truncation".toByteArray()
        val enc = cipher.encryptChunk(plaintext, 0, plaintext.size, chunkIndex = 0, isLast = true)

        // Truncate tag bytes off the end
        val truncated = enc.copyOf(enc.size - 5)

        try {
            cipher.decryptChunk(truncated, 0, truncated.size, chunkIndex = 0, isLast = true)
            fail("Truncated chunk missing GCM tag bytes must be rejected")
        } catch (e: GeneralSecurityException) {
            // Expected
        }
    }
}
