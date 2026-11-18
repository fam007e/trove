package com.trove.core.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Handles per-chunk authenticated encryption/decryption for the TRVE format.
 * 
 * Nonce derivation: 8 bytes random file prefix + 4 bytes chunk index (Big Endian).
 * AAD: "TRVE" || version (1B) || chunkIndex (4B) || isLast (1B).
 */
class TrveChunkedCipher(
    private val key: SecretKey,
    private val noncePrefix: ByteArray,
    private val version: Byte = TrveHeader.CURRENT_VERSION
) {
    companion object {
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = 16
        const val NONCE_LENGTH = 12
    }

    private fun deriveNonce(chunkIndex: Int): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        System.arraycopy(noncePrefix, 0, nonce, 0, 8)
        val buf = ByteBuffer.wrap(nonce, 8, 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(chunkIndex)
        return nonce
    }

    private fun buildAad(chunkIndex: Int, isLast: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(TrveHeader.MAGIC)
        buf.put(version)
        buf.putInt(chunkIndex)
        buf.put(if (isLast) 1.toByte() else 0.toByte())
        return buf.array()
    }

    fun encryptChunk(
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        chunkIndex: Int,
        isLast: Boolean
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = deriveNonce(chunkIndex)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        cipher.updateAAD(buildAad(chunkIndex, isLast))
        return cipher.doFinal(plaintext, offset, length)
    }

    fun decryptChunk(
        ciphertextWithTag: ByteArray,
        offset: Int,
        length: Int,
        chunkIndex: Int,
        isLast: Boolean
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = deriveNonce(chunkIndex)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        cipher.updateAAD(buildAad(chunkIndex, isLast))
        return cipher.doFinal(ciphertextWithTag, offset, length)
    }
}
