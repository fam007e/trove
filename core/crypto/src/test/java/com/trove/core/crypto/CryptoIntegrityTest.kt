package com.trove.core.crypto

import android.content.Context
import com.trove.core.crypto.keystore.RawKeystoreVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
class CryptoIntegrityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun testAeadTamperDetection() {
        val originalSecret = "TopSecret123".toByteArray()
        val key = "test_secret_key"
        
        // 1. Encrypt and store
        RawKeystoreVault.encryptToPrefs(context, key, originalSecret)
        
        // 2. Retrieve the raw base64 string from prefs
        val prefs = context.getSharedPreferences(VaultConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val originalBase64 = prefs.getString(key, null)
        assertNotNull(originalBase64)
        
        // 3. Tamper with the ciphertext (flip a bit in the last byte)
        val rawBytes = android.util.Base64.decode(originalBase64, android.util.Base64.NO_WRAP)
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0xFF).toByte()
        val tamperedBase64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
        
        // 4. Attempt to decrypt tampered data
        // RawKeystoreVault.decryptBase64 should catch the AEADBadTagException and return null
        val decrypted = RawKeystoreVault.decryptBase64(tamperedBase64)
        assertNull("Decryption should fail for tampered data due to AEAD tag mismatch", decrypted)
        
        // 5. Verify untampered data still works
        val cleanDecrypted = RawKeystoreVault.decryptBase64(originalBase64!!)
        assertArrayEquals(originalSecret, cleanDecrypted)
    }
}
