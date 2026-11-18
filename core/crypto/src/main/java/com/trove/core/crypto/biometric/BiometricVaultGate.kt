package com.trove.core.crypto.biometric

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.trove.core.crypto.VaultConstants
import com.trove.core.fs.Result
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Biometric gate for the Trove Crypto Vault.
 *
 * PEDAGOGICAL NOTE:
 * Hardware-backed biometric keys enforce that cryptographic operations can ONLY
 * occur while the user is actively authenticated via Strong Biometrics.
 * Crucially, [setInvalidatedByBiometricEnrollment(true)] guarantees that if an attacker
 * with device passcode access adds a new fingerprint, the key is permanently destroyed.
 */
object BiometricVaultGate {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val BIOMETRIC_KEY_ALIAS = "trove_vault_biometric_key"
    private const val KEY_SIZE = 256
    private const val TAG_SIZE = 128

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    @Synchronized
    fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(BIOMETRIC_KEY_ALIAS, null)?.let { return it as SecretKey }

        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    fun createEncryptCryptoObject(): BiometricPrompt.CryptoObject {
        val key = getOrCreateBiometricKey()
        val cipher = Cipher.getInstance(VaultConstants.AES_GCM_NO_PADDING).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        return BiometricPrompt.CryptoObject(cipher)
    }

    fun createDecryptCryptoObject(iv: ByteArray): BiometricPrompt.CryptoObject {
        val key = getOrCreateBiometricKey()
        val cipher = Cipher.getInstance(VaultConstants.AES_GCM_NO_PADDING).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE, iv))
        }
        return BiometricPrompt.CryptoObject(cipher)
    }

    /**
     * Suspends until the user completes the biometric prompt or cancels.
     */
    suspend fun promptBiometric(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
        title: String = "Unlock Trove Vault",
        subtitle: String = "Verify your biometric identity to access encrypted files"
    ): Result<Cipher> = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val cipher = result.cryptoObject?.cipher
                if (cipher != null) {
                    continuation.resume(Result.Success(cipher))
                } else {
                    continuation.resume(Result.Failure(IllegalStateException("Authenticated but Cipher was null")))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                continuation.resume(Result.Failure(SecurityException("Biometric error $errorCode: $errString")))
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Individual attempt failed; user can retry until max attempts reached
            }
        })

        continuation.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }

        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }

    /**
     * Biometrically encrypts a raw master key (e.g. derived from Argon2id) for storage.
     * Returns Pair(iv, encryptedData).
     */
    suspend fun encryptMasterKey(
        activity: FragmentActivity,
        masterKey: ByteArray
    ): Result<Pair<ByteArray, ByteArray>> {
        return try {
            val cryptoObject = createEncryptCryptoObject()
            when (val authResult = promptBiometric(activity, cryptoObject, title = "Setup Biometric Lock")) {
                is Result.Failure -> Result.Failure(authResult.exception)
                is Result.Success -> {
                    val cipher = authResult.value
                    val iv = cipher.iv
                    val encrypted = cipher.doFinal(masterKey)
                    Result.Success(Pair(iv, encrypted))
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    /**
     * Biometrically decrypts a stored master key.
     */
    suspend fun decryptMasterKey(
        activity: FragmentActivity,
        iv: ByteArray,
        encryptedMasterKey: ByteArray
    ): Result<ByteArray> {
        return try {
            val cryptoObject = createDecryptCryptoObject(iv)
            when (val authResult = promptBiometric(activity, cryptoObject, title = "Unlock Vault with Biometrics")) {
                is Result.Failure -> Result.Failure(authResult.exception)
                is Result.Success -> {
                    val cipher = authResult.value
                    val decrypted = cipher.doFinal(encryptedMasterKey)
                    Result.Success(decrypted)
                }
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }
}
