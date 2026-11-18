package com.trove.core.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Handles Argon2id-based key derivation from user passphrases.
 * 
 * PEDAGOGICAL NOTE:
 * Argon2id is the state-of-the-art for password hashing, resistant to both GPU/ASIC 
 * side-channel and memory-hardness attacks. We use a 64MB memory cost as a 
 * balanced default for mobile hardware.
 */
object KeyDerivation {

    private const val MEMORY_COST = 65536 // 64 MB
    private const val ITERATIONS = 3
    private const val PARALLELISM = 4
    private const val KEY_LENGTH = 32 // 256 bits
    private const val SALT_KEY = "vault_kdf_salt"
    private const val ENCRYPTED_PREFS_NAME = "trove_secure_vault_meta"

    private fun getEncryptedPrefs(context: Context): EncryptedSharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(PARALLELISM)
            .withMemoryAsKB(MEMORY_COST)
            .withIterations(ITERATIONS)
            .build()
        
        val gen = Argon2BytesGenerator()
        gen.init(params)
        
        val keyBytes = ByteArray(KEY_LENGTH)
        gen.generateBytes(passphrase.toByteArray(Charsets.UTF_8), keyBytes)
        
        return SecretKeySpec(keyBytes, "AES")
    }

    fun generateAndStoreSalt(context: Context): ByteArray {
        val salt = ByteArray(16) // 128-bit salt is standard for Argon2
        SecureRandom().nextBytes(salt)
        
        getEncryptedPrefs(context).edit()
            .putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
            
        return salt
    }

    fun getStoredSalt(context: Context): ByteArray? {
        val encoded = getEncryptedPrefs(context).getString(SALT_KEY, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }
}
