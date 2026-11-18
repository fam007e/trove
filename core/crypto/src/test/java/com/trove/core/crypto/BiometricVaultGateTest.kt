package com.trove.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class BiometricVaultGateTest {

    @Test
    fun testAesGcmCipherTransformation() {
        // Test software GCM transformation parity
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        val secretKey = kg.generateKey()

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = encryptCipher.iv
        assertNotNull(iv)
        assertEquals(12, iv.size)

        val plaintext = "TroveMasterVaultSecretPayload".toByteArray()
        val ciphertext = encryptCipher.doFinal(plaintext)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val decrypted = decryptCipher.doFinal(ciphertext)

        assertEquals(String(plaintext), String(decrypted))
    }
}
