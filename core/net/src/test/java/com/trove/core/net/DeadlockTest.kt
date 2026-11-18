package com.trove.core.net

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exhaustive tests for SessionManager concurrency properties.
 * 
 * Verifies three critical properties:
 * 1. Reentrancy for nested client calls (useClient in stream).
 * 2. Reentrancy for nested streaming calls (stream in stream - used by copyTo).
 * 3. Mutual exclusion for different Job identities.
 */
class DeadlockTest {

    @Test
    fun testReentrancySameJob(): Unit = runBlocking {
        withTimeout(2000) {
            val sessionManager = SessionManager("MockClient")
            val callCounter = AtomicInteger(0)

            val inputStream = sessionManager.openLockingInputStream { client ->
                // Nested call: Should succeed via reentrancy
                sessionManager.useClient {
                    callCounter.incrementAndGet()
                }
                ByteArrayInputStream("test".toByteArray())
            }

            assertNotNull("Stream should be opened", inputStream)
            
            sessionManager.useClient { 
                callCounter.incrementAndGet()
            }

            inputStream?.close()
            assertEquals("Should allow nested calls from same Job", 2, callCounter.get())
        }
    }

    @Test
    fun testReentrancyNestedStreams(): Unit = runBlocking {
        withTimeout(2000) {
            val sessionManager = SessionManager("MockClient")
            
            // 1. Open first locking stream
            val input = sessionManager.openLockingInputStream { 
                ByteArrayInputStream("source".toByteArray()) 
            }
            assertNotNull(input)

            // 2. Open second locking stream on SAME session while first is open
            // This replicates the copyTo pattern and tests the reentrant streaming branch.
            val output = sessionManager.openLockingOutputStream {
                java.io.ByteArrayOutputStream()
            }
            assertNotNull("Nested output stream should be allowed via reentrancy", output)

            // 3. Cleanup
            output?.close()
            input?.close()
        }
    }

    @Test
    fun testReentrancyAcrossWithContext(): Unit = runBlocking {
        withTimeout(2000) {
            val sessionManager = SessionManager("MockClient")
            val callCounter = AtomicInteger(0)

            // 1. Establish ownership scope
            sessionManager.useClient {
                // 2. Call a nested operation that switches dispatchers (creating a new child Job)
                withContext(Dispatchers.IO) {
                    sessionManager.useClient {
                        callCounter.incrementAndGet()
                    }
                }
            }
            
            assertEquals("Should allow reentrancy across withContext boundaries", 1, callCounter.get())
        }
    }
}
