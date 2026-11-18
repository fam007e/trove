package com.trove.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class CryptoIntegrityTest {

    @Test
    fun testAeadTamperDetection() {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        val secretKey = kg.generateKey()

        val originalSecret = "TopSecret123".toByteArray()

        // 1. Encrypt with AES-GCM
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = encryptCipher.iv
        val ciphertext = encryptCipher.doFinal(originalSecret)

        val fullPayload = iv + ciphertext
        val base64Payload = Base64.getEncoder().encodeToString(fullPayload)
        assertNotNull(base64Payload)

        // 2. Tamper with the ciphertext (flip a bit in the authentication tag)
        val rawBytes = Base64.getDecoder().decode(base64Payload)
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0xFF).toByte()

        // 3. Attempt to decrypt tampered data - AEADBadTagException MUST be thrown
        val tamperedIv = rawBytes.copyOfRange(0, 12)
        val tamperedCiphertext = rawBytes.copyOfRange(12, rawBytes.size)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, tamperedIv))
        try {
            decryptCipher.doFinal(tamperedCiphertext)
            fail("Decryption should fail for tampered data due to AEAD tag mismatch")
        } catch (_: AEADBadTagException) {
            // Expected security behavior
        }

        // 4. Verify untampered data still decrypts cleanly
        val validDecryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        validDecryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val cleanDecrypted = validDecryptCipher.doFinal(ciphertext)
        assertArrayEquals(originalSecret, cleanDecrypted)
    }
}
