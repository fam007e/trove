package com.trove.core.net

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Ensures thread-safe, serialized access to a network client.
 */
interface NetworkSession<T> {
    suspend fun <R> useClient(block: suspend (T) -> R): R
    suspend fun openLockingInputStream(block: suspend (T) -> InputStream?): InputStream?
    suspend fun openLockingOutputStream(block: suspend (T) -> OutputStream?): OutputStream?
}

/**
 * A CoroutineContext element used to track session ownership.
 * Inherited across withContext and launch boundaries.
 */
internal class SessionOwnerElement(val manager: SessionManager<*>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionOwnerElement>
}

/**
 * A reentrant-aware SessionManager.
 * 
 * PEDAGOGICAL NOTE:
 * We use a custom CoroutineContext.Element to track ownership because 
 * Job identity is lost across withContext(Dispatchers.IO) boundaries.
 */
class SessionManager<T>(private val client: T) : NetworkSession<T> {
    private val mutex = Mutex()
    
    @Volatile
    private var ownerJob: Job? = null

    private suspend fun isOwner(): Boolean {
        // 1. Check for scoped ownership (propagated via context element)
        if (currentCoroutineContext()[SessionOwnerElement]?.manager === this) return true
        
        // 2. Fallback to Job identity (for single-call reentrancy)
        return ownerJob != null && ownerJob === currentCoroutineContext()[Job]
    }

    override suspend fun <R> useClient(block: suspend (T) -> R): R {
        if (isOwner()) return block(client)
        
        return mutex.withLock {
            // Establish ownership scope for this and all child coroutines
            withContext(SessionOwnerElement(this@SessionManager)) {
                ownerJob = coroutineContext[Job]
                try {
                    block(client)
                } finally {
                    ownerJob = null
                }
            }
        }
    }

    override suspend fun openLockingInputStream(block: suspend (T) -> InputStream?): InputStream? {
        if (isOwner()) return block(client)

        val wrapper = LockingInputStreamWrapper(this)
        mutex.lock(wrapper)
        ownerJob = currentCoroutineContext()[Job]
        try {
            val stream = block(client)
            if (stream == null) {
                ownerJob = null
                mutex.unlock(wrapper)
                return null
            }
            wrapper.setDelegate(stream)
            return wrapper
        } catch (e: Exception) {
            ownerJob = null
            mutex.unlock(wrapper)
            throw e
        }
    }

    override suspend fun openLockingOutputStream(block: suspend (T) -> OutputStream?): OutputStream? {
        if (isOwner()) return block(client)

        val wrapper = LockingOutputStreamWrapper(this)
        mutex.lock(wrapper)
        ownerJob = currentCoroutineContext()[Job]
        try {
            val stream = block(client)
            if (stream == null) {
                ownerJob = null
                mutex.unlock(wrapper)
                return null
            }
            wrapper.setDelegate(stream)
            return wrapper
        } catch (e: Exception) {
            ownerJob = null
            mutex.unlock(wrapper)
            throw e
        }
    }

    internal fun releaseLock(ownerToken: Any) {
        ownerJob = null
        mutex.unlock(ownerToken)
    }
}

private class LockingInputStreamWrapper(
    private val manager: SessionManager<*>
) : InputStream() {
    private var delegate: InputStream? = null
    private val closed = AtomicBoolean(false)

    fun setDelegate(stream: InputStream) {
        this.delegate = stream
    }

    override fun read(): Int = delegate?.read() ?: -1
    override fun read(b: ByteArray): Int = delegate?.read(b) ?: -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate?.read(b, off, len) ?: -1
    override fun available(): Int = delegate?.available() ?: 0
    override fun skip(n: Long): Long = delegate?.skip(n) ?: 0
    override fun mark(readlimit: Int) { delegate?.mark(readlimit) }
    override fun reset() { delegate?.reset() }
    override fun markSupported(): Boolean = delegate?.markSupported() ?: false
    
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                delegate?.close()
            } finally {
                manager.releaseLock(this)
            }
        }
    }
}

private class LockingOutputStreamWrapper(
    private val manager: SessionManager<*>
) : OutputStream() {
    private var delegate: OutputStream? = null
    private val closed = AtomicBoolean(false)

    fun setDelegate(stream: OutputStream) {
        this.delegate = stream
    }

    override fun write(b: Int) { delegate?.write(b) }
    override fun write(b: ByteArray) { delegate?.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { delegate?.write(b, off, len) }
    override fun flush() { delegate?.flush() }
    
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                delegate?.close()
            } finally {
                manager.releaseLock(this)
            }
        }
    }
}
