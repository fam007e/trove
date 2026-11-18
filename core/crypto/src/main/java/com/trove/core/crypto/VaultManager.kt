package com.trove.core.crypto

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.trove.core.crypto.biometric.BiometricVaultGate
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * High-level coordinator for Trove Vault lifecycle operations.
 * 
 * Manages Argon2id passphrase derivation, Biometric authentication gates,
 * and mounting encrypted virtual directories as [VaultFileItem].
 */
object VaultManager {

    private const val VAULT_META_FILE = ".trve_vault"

    /**
     * Initialize a new encrypted vault inside [directory].
     */
    suspend fun createVault(
        directory: FileItem,
        passphrase: String
    ): Result<VaultFileItem> {
        return try {
            val salt = ByteArray(TrveHeader.SALT_LENGTH)
            SecureRandom().nextBytes(salt)

            val key = KeyDerivation.deriveKey(passphrase, salt)

            // Write vault marker header so unlock knows the primary salt
            when (val res = directory.createFile(VAULT_META_FILE)) {
                is Result.Success -> {
                    val metaItem = res.value
                    when (val out = metaItem.openOutputStream(append = false)) {
                        is Result.Success -> {
                            val header = TrveHeader(
                                salt = salt,
                                noncePrefix = ByteArray(TrveHeader.NONCE_PREFIX_LENGTH).apply {
                                    SecureRandom().nextBytes(this)
                                }
                            )
                            out.value.use { header.write(it) }
                            Result.Success(VaultFileItem(directory, key, salt))
                        }
                        is Result.Failure -> Result.Failure(out.exception)
                    }
                }
                is Result.Failure -> Result.Failure(res.exception)
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    /**
     * Unlock an existing vault using the user passphrase.
     */
    suspend fun unlockVault(
        directory: FileItem,
        passphrase: String
    ): Result<VaultFileItem> {
        return try {
            when (val findRes = directory.findChild(VAULT_META_FILE)) {
                is Result.Success -> {
                    val metaItem = findRes.value
                    when (val inRes = metaItem.openInputStream()) {
                        is Result.Success -> {
                            val header = inRes.value.use { TrveHeader.read(it) }
                            val key = KeyDerivation.deriveKey(passphrase, header.salt)
                            Result.Success(VaultFileItem(directory, key, header.salt))
                        }
                        is Result.Failure -> Result.Failure(inRes.exception)
                    }
                }
                is Result.Failure -> Result.Failure(findRes.exception)
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    /**
     * Check if a directory is an initialized TRVE vault.
     */
    suspend fun isVault(directory: FileItem): Boolean {
        return when (val res = directory.findChild(VAULT_META_FILE)) {
            is Result.Success -> true
            is Result.Failure -> false
        }
    }

    /**
     * Check if a directory is a legacy MiXplorer EncFS volume.
     */
    suspend fun isLegacyEncFs(directory: FileItem): Boolean {
        return when (val res = directory.findChild(EncFsConfig.CONFIG_FILE_NAME)) {
            is Result.Success -> true
            is Result.Failure -> false
        }
    }
}
