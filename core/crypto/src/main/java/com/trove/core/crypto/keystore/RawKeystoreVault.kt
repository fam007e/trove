package com.trove.core.crypto.keystore

import com.trove.core.crypto.VaultConstants
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Log

/**
 * Native Keystore implementation for secure storage of small secrets.
 * 
 * PEDAGOGICAL NOTE:
 * This uses the Android Keystore System to generate a key that NEVER leaves 
 * the hardware (TEE or StrongBox). We use AES-GCM for authenticated encryption, 
 * ensuring that any tampering with the stored secret will be detected during decryption.
 */
object RawKeystoreVault {
    private const val ALIAS = "trove_vault_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_KEY_SIZE = 256
    private const val TAG = "RawKeystoreVault"
    private const val IV_SIZE = 12 // Standard GCM nonce size

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(ALIAS, null)?.let { return it as SecretKey }
        
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .build())
        }.generateKey()
    }

    /**
     * Encrypts a value and stores it in SharedPreferences.
     * Uses commit() for synchronous write to minimize corruption windows.
     */
    fun encryptToPrefs(context: Context, key: String, value: ByteArray) {
        val cipher = Cipher.getInstance(VaultConstants.AES_GCM_NO_PADDING).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value)
        
        // Store as IV + Ciphertext (IV is always 12 bytes for GCM)
        val combined = iv + encrypted
        context.getSharedPreferences(VaultConstants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(key, Base64.encodeToString(combined, Base64.NO_WRAP))
            .commit() // Synchronous write for security metadata
    }

    fun decryptBase64(base64Ciphertext: String): ByteArray? {
        return try {
            val raw = Base64.decode(base64Ciphertext, Base64.NO_WRAP)
            if (raw.size < IV_SIZE) {
                Log.e(TAG, "Ciphertext too short (possibly corrupted)")
                return null
            }
            
            val iv = raw.copyOfRange(0, IV_SIZE)
            val data = raw.copyOfRange(IV_SIZE, raw.size)
            
            Cipher.getInstance(VaultConstants.AES_GCM_NO_PADDING).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }.doFinal(data)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed (Authentication/Integrity error)", e)
            null
        }
    }

    fun decryptFromPrefs(context: Context, key: String): ByteArray? {
        val encoded = context.getSharedPreferences(VaultConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return decryptBase64(encoded)
    }
}
