package com.trove.core.crypto.auth

import android.content.Context
import com.trove.core.crypto.VaultConstants
import com.trove.core.crypto.keystore.RawKeystoreVault

/**
 * Manages secure storage of credentials.
 */
class CredentialManager(private val context: Context) {
    
    companion object {
        private const val CRED_PREFIX = "cred_"
    }

    fun storeHash(user: String, hash: String) {
        RawKeystoreVault.encryptToPrefs(context, CRED_PREFIX + user, hash.toByteArray(Charsets.UTF_8))
    }

    fun getHash(user: String): String? {
        return RawKeystoreVault.decryptFromPrefs(context, CRED_PREFIX + user)?.toString(Charsets.UTF_8)
    }

    fun getAllCredentials(): Map<String, String> {
        val prefs = context.getSharedPreferences(VaultConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val credentials = mutableMapOf<String, String>()
        
        for ((key, value) in prefs.all) {
            if (key.startsWith(CRED_PREFIX) && value is String) {
                // Decrypt directly from the cached string value to avoid redundant prefs lookups
                val decrypted = RawKeystoreVault.decryptBase64(value)
                if (decrypted != null) {
                    credentials[key.removePrefix(CRED_PREFIX)] = decrypted.toString(Charsets.UTF_8)
                }
            }
        }
        return credentials
    }
}
