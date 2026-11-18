package com.trove.core.crypto

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * Streaming input adapter that authenticates and decrypts TRVE chunks on the fly.
 * 
 * If any byte of any chunk is tampered with, an [AEADBadTagException] is thrown immediately
 * during the reading of that chunk.
 */
class TrveChunkedInputStream(
    private val underlying: InputStream,
    private val key: SecretKey
) : InputStream() {

    val header: TrveHeader = TrveHeader.read(underlying)
    private val cipher = TrveChunkedCipher(key, header.noncePrefix, header.version)

    private var currentChunkPlaintext: ByteArray = ByteArray(0)
    private var currentChunkPos = 0
    private var chunkIndex = 0
    private var reachedEof = false
    private var closed = false

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IllegalStateException("Stream is closed")
        if (len == 0) return 0

        while (currentChunkPos >= currentChunkPlaintext.size) {
            if (reachedEof) return -1
            if (!readNextChunk()) return -1
        }

        val available = currentChunkPlaintext.size - currentChunkPos
        val toRead = Math.min(available, len)
        System.arraycopy(currentChunkPlaintext, currentChunkPos, b, off, toRead)
        currentChunkPos += toRead
        return toRead
    }

    private fun readNextChunk(): Boolean {
        // Read 4-byte chunk length
        val lenBytes = ByteArray(4)
        var readLen = 0
        while (readLen < 4) {
            val r = underlying.read(lenBytes, readLen, 4 - readLen)
            if (r == -1) {
                if (readLen == 0) {
                    reachedEof = true
                    return false
                }
                throw EOFException("Truncated chunk length prefix at chunk $chunkIndex")
            }
            readLen += r
        }

        val chunkCiphertextSize = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
        if (chunkCiphertextSize <= 0 || chunkCiphertextSize > header.chunkSize + 1024) {
            throw IllegalArgumentException("Invalid chunk size: $chunkCiphertextSize at index $chunkIndex")
        }

        val encrypted = ByteArray(chunkCiphertextSize)
        var readEnc = 0
        while (readEnc < chunkCiphertextSize) {
            val r = underlying.read(encrypted, readEnc, chunkCiphertextSize - readEnc)
            if (r == -1) throw EOFException("Truncated chunk ciphertext at index $chunkIndex")
            readEnc += r
        }

        // Try decrypting as intermediate chunk (isLast = false)
        try {
            currentChunkPlaintext = cipher.decryptChunk(
                encrypted, 0, chunkCiphertextSize, chunkIndex, isLast = false
            )
        } catch (e: AEADBadTagException) {
            // Might be the final chunk (isLast = true)
            try {
                currentChunkPlaintext = cipher.decryptChunk(
                    encrypted, 0, chunkCiphertextSize, chunkIndex, isLast = true
                )
                reachedEof = true
            } catch (_: AEADBadTagException) {
                // Real authentication failure (corrupted data or wrong key)
                throw AEADBadTagException("Authentication failed for TRVE chunk $chunkIndex: data tampered with or incorrect key")
            }
        }

        currentChunkPos = 0
        chunkIndex++
        return true
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        var remaining = n
        val buffer = ByteArray(Math.min(remaining, 8192).toInt())
        while (remaining > 0) {
            val read = read(buffer, 0, Math.min(remaining, buffer.size.toLong()).toInt())
            if (read == -1) break
            remaining -= read
        }
        return n - remaining
    }

    override fun close() {
        if (!closed) {
            underlying.close()
            closed = true
        }
    }
}
