package com.trove.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class TrveChunkedStreamingTest {

    private val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val testSalt = ByteArray(32) { (it * 3).toByte() }
    private val testNoncePrefix = ByteArray(8) { (it * 7).toByte() }

    @Test
    fun `test header serialization and deserialization`() {
        val header = TrveHeader(
            salt = testSalt,
            noncePrefix = testNoncePrefix,
            chunkSize = 65536,
            plaintextLength = 123456L
        )

        val bos = ByteArrayOutputStream()
        header.write(bos)
        val bytes = bos.toByteArray()

        assertEquals(TrveHeader.HEADER_SIZE, bytes.size)

        val parsed = TrveHeader.read(ByteArrayInputStream(bytes))
        assertEquals(header, parsed)
    }

    @Test
    fun `test small plaintext round-trip`() {
        val original = "Hello Trove Secure Vault!".toByteArray(Charsets.UTF_8)

        val cipherOut = ByteArrayOutputStream()
        val outStream = TrveChunkedOutputStream(cipherOut, testKey, testSalt, testNoncePrefix)
        outStream.write(original)
        outStream.close()

        val inStream = TrveChunkedInputStream(ByteArrayInputStream(cipherOut.toByteArray()), testKey)
        val decrypted = inStream.readBytes()
        inStream.close()

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `test empty file round-trip`() {
        val original = ByteArray(0)

        val cipherOut = ByteArrayOutputStream()
        val outStream = TrveChunkedOutputStream(cipherOut, testKey, testSalt, testNoncePrefix)
        outStream.write(original)
        outStream.close()

        val inStream = TrveChunkedInputStream(ByteArrayInputStream(cipherOut.toByteArray()), testKey)
        val decrypted = inStream.readBytes()
        inStream.close()

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `test multi-chunk round-trip`() {
        // 160 KB spans three 64KB chunks (64KB + 64KB + 32KB)
        val size = 160 * 1024
        val original = ByteArray(size)
        SecureRandom().nextBytes(original)

        val cipherOut = ByteArrayOutputStream()
        val outStream = TrveChunkedOutputStream(cipherOut, testKey, testSalt, testNoncePrefix, chunkSize = 64 * 1024)
        outStream.write(original)
        outStream.close()

        val inStream = TrveChunkedInputStream(ByteArrayInputStream(cipherOut.toByteArray()), testKey)
        val decrypted = inStream.readBytes()
        inStream.close()

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `test tamper detection throws AEADBadTagException immediately on corrupted chunk`() {
        val size = 1000
        val original = ByteArray(size) { 0x42 }

        val cipherOut = ByteArrayOutputStream()
        val outStream = TrveChunkedOutputStream(cipherOut, testKey, testSalt, testNoncePrefix)
        outStream.write(original)
        outStream.close()

        val ciphertext = cipherOut.toByteArray()
        // Corrupt a byte in the payload area (after the 57-byte header and 4-byte chunk length)
        val corruptIndex = TrveHeader.HEADER_SIZE + 10
        ciphertext[corruptIndex] = (ciphertext[corruptIndex].toInt() xor 0xFF).toByte()

        val inStream = TrveChunkedInputStream(ByteArrayInputStream(ciphertext), testKey)
        assertThrows(AEADBadTagException::class.java) {
            inStream.readBytes()
        }
    }

    @Test
    fun `test wrong key fails authentication`() {
        val original = "Sensitive data".toByteArray()

        val cipherOut = ByteArrayOutputStream()
        val outStream = TrveChunkedOutputStream(cipherOut, testKey, testSalt, testNoncePrefix)
        outStream.write(original)
        outStream.close()

        val wrongKey = SecretKeySpec(ByteArray(32) { 0xFF.toByte() }, "AES")
        val inStream = TrveChunkedInputStream(ByteArrayInputStream(cipherOut.toByteArray()), wrongKey)

        assertThrows(AEADBadTagException::class.java) {
            inStream.readBytes()
        }
    }

    @Test
    fun `test EncFsConfigParser parses valid XML config`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE boost_serialization>
            <boost_serialization signature="serialization::archive" version="7">
            <EncFSConfig>
                <cipher>ssl/aes</cipher>
                <keySize>256</keySize>
                <blockSize>1024</blockSize>
                <kdfIterations>120000</kdfIterations>
                <saltLen>20</saltLen>
                <saltData>dGVzdHNhbHQxMjM0NTY3ODkwMTI=</saltData>
                <encodedKeyData>ZW5jb2RlZGtleWRhdGE=</encodedKeyData>
                <nameCoding>nameio/block</nameCoding>
            </EncFSConfig>
            </boost_serialization>
        """.trimIndent()

        val config = EncFsConfig.parse(xml)
        assertEquals("ssl/aes", config.cipher)
        assertEquals(256, config.keySize)
        assertEquals(1024, config.blockSize)
        assertEquals(120000, config.kdfIterations)
        assertEquals("nameio/block", config.nameCoding)
        assertTrue(config.salt.isNotEmpty())
        assertTrue(config.encodedKeyData.isNotEmpty())
    }
}
