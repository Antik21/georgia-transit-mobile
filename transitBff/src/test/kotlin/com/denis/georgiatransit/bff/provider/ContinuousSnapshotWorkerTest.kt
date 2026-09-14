package com.denis.georgiatransit.bff.provider

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinuousSnapshotWorkerTest {
    @Test
    fun `polls without callers and publishes complete snapshots`() = runBlocking {
        val calls = AtomicInteger()
        ContinuousSnapshotWorker(Duration.ofMillis(10)) { calls.incrementAndGet() }.use { worker ->
            delay(35)
            assertTrue(calls.get() >= 3)
            assertTrue(worker.get() in 1..calls.get())
        }
    }

    @Test
    fun `failed refresh retains last validated snapshot`() = runBlocking {
        val calls = AtomicInteger()
        ContinuousSnapshotWorker(Duration.ofMillis(10)) {
            val call = calls.incrementAndGet()
            if (call == 2) throw ProviderUnavailable("temporary")
            call
        }.use { worker ->
            assertEquals(1, worker.get())
            while (calls.get() < 2) delay(2)
            assertEquals(1, worker.get())
            while (calls.get() < 3) delay(2)
            assertEquals(3, worker.get())
        }
    }

    @Test
    fun `disabled polling does not complete the first attempt`() = runBlocking {
        val enabled = AtomicBoolean(false)
        val calls = AtomicInteger()
        ContinuousSnapshotWorker(
            pollInterval = Duration.ofMillis(10),
            isEnabled = enabled::get,
            loader = calls::incrementAndGet,
        ).use { worker ->
            val first = async { worker.get() }
            delay(35)

            assertEquals(0, calls.get())
            assertTrue(!first.isCompleted)

            enabled.set(true)
            assertEquals(1, first.await())
            assertTrue(calls.get() >= 1)
        }
    }
}
