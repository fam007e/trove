package com.trove.core.crypto

import com.trove.core.fs.FileItem
import com.trove.core.fs.PosixPermissions
import com.trove.core.fs.Result
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * GA Implementation of [FileItem] for encrypted Vault storage.
 * 
 * Uses the `TRVE` Chunked Streaming AEAD format (AES-256-GCM + chunk-bound AAD).
 * Completely eliminates the truncation, unauthenticated stream, and counter overflow hazards
 * of monolithic CipherInputStream.
 */
class VaultFileItem(
    private val delegate: FileItem,
    private val secretKey: SecretKey,
    private val defaultSalt: ByteArray = ByteArray(TrveHeader.SALT_LENGTH)
) : FileItem by delegate {

    override suspend fun openInputStream(): Result<InputStream> {
        return delegate.openInputStream().map { input ->
            TrveChunkedInputStream(input, secretKey)
        }
    }

    override suspend fun openOutputStream(append: Boolean): Result<OutputStream> {
        // Appending to an authenticated chunked stream requires re-authenticating the final chunk;
        // for security and cryptographic integrity, we write fresh streams.
        return delegate.openOutputStream(append = false).map { output ->
            val noncePrefix = ByteArray(TrveHeader.NONCE_PREFIX_LENGTH)
            SecureRandom().nextBytes(noncePrefix)
            TrveChunkedOutputStream(output, secretKey, defaultSalt, noncePrefix)
        }
    }

    override suspend fun createFile(name: String): Result<FileItem> {
        return delegate.createFile(name).map { VaultFileItem(it, secretKey, defaultSalt) }
    }

    override suspend fun listFiles(): Result<List<FileItem>> {
        return delegate.listFiles().map { list ->
            list.map { VaultFileItem(it, secretKey, defaultSalt) }
        }
    }

    override suspend fun moveTo(destDir: FileItem, newName: String): Result<FileItem> {
        val targetDestDir = if (destDir is VaultFileItem) destDir.delegate else destDir
        return delegate.moveTo(targetDestDir, newName).map { VaultFileItem(it, secretKey, defaultSalt) }
    }

    override suspend fun findChild(name: String): Result<FileItem> {
        return delegate.findChild(name).map { VaultFileItem(it, secretKey, defaultSalt) }
    }

    override suspend fun getParent(): Result<FileItem?> {
        return delegate.getParent().map { it?.let { VaultFileItem(it, secretKey, defaultSalt) } }
    }
}
