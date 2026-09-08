package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.SingleFlightCapacityExceeded
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SingleFlightTest {
    @Test
    fun `bounded observer reports owner and coalesced follower without another upstream attempt`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val outcomes = Collections.synchronizedList(mutableListOf<SingleFlightLookupOutcome>())
            val owner = async {
                singleFlight.get("key", outcomes::add) {
                    loads.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    9
                }
            }
            started.await()
            val follower = async(start = CoroutineStart.UNDISPATCHED) {
                singleFlight.get("key", outcomes::add) { error("coalesced follower ran loader") }
            }
            release.complete(Unit)

            assertEquals(9, owner.await())
            assertEquals(9, follower.await())
            assertEquals(1, loads.get())
            assertEquals(
                listOf(SingleFlightLookupOutcome.MISS_OWNER, SingleFlightLookupOutcome.COALESCED),
                outcomes,
            )
        }
    }

    @Test
    fun `concurrent callers for one key share work`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val results = List(25) {
                async {
                    singleFlight.get("same") {
                        loads.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        7
                    }
                }
            }
            started.await()
            release.complete(Unit)
            assertEquals(List(25) { 7 }, results.awaitAll())
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun `distinct keys run independently`() = runTest {
        SingleFlight<String, String>(1.seconds).use { singleFlight ->
            assertEquals(
                setOf("a", "b"),
                listOf("a", "b").map { key -> async { singleFlight.get(key) { key } } }.awaitAll().toSet(),
            )
        }
    }

    @Test
    fun `successful and failed work is removed`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            var loads = 0
            assertEquals(1, singleFlight.get("success") { ++loads })
            assertEquals(2, singleFlight.get("success") { ++loads })
            assertFailsWith<IllegalStateException> { singleFlight.get("failure") { error("boom") } }
            assertEquals(3, singleFlight.get("failure") { ++loads })
        }
    }

    @Test
    fun `completed load immediately frees same key and capacity for a fresh load`() = runTest {
        SingleFlight<String, Int>(1.seconds, maximumEntries = 1).use { singleFlight ->
            val loads = AtomicInteger()

            assertEquals(1, singleFlight.get("same") { loads.incrementAndGet() })
            assertEquals(2, singleFlight.get("same") { loads.incrementAndGet() })
            assertEquals(3, singleFlight.get("other") { loads.incrementAndGet() })
            assertEquals(3, loads.get())
        }
    }

    @Test
    fun `completed success and failure handoff residues start fresh generations`() = runTest {
        SingleFlight<String, Int>(1.seconds, maximumEntries = 1).use { singleFlight ->
            singleFlight.inFlightForTest()["handoff"] = CompletableDeferred(11)
            val replacementLoads = AtomicInteger()

            assertEquals(22, singleFlight.get("handoff") { replacementLoads.incrementAndGet(); 22 })
            assertEquals(1, replacementLoads.get())
        }

        SingleFlight<String, Int>(1.seconds, maximumEntries = 1).use { singleFlight ->
            val failedGeneration = CompletableDeferred<Int>()
            failedGeneration.completeExceptionally(IllegalStateException("completed failure"))
            singleFlight.inFlightForTest()["handoff"] = failedGeneration
            val replacementLoads = AtomicInteger()

            assertEquals(33, singleFlight.get("handoff") { replacementLoads.incrementAndGet(); 33 })
            assertEquals(1, replacementLoads.get())
        }
    }

    @Test
    fun `worker settlement and identity removal are atomic under the private monitor`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            val loaderStarted = CompletableDeferred<Unit>()
            val allowLoaderReturn = CompletableDeferred<Unit>()
            val loaderReturning = CountDownLatch(1)
            val workerThread = AtomicReference<Thread>()
            val loads = AtomicInteger()
            val first = async {
                singleFlight.get("atomic") {
                    loads.incrementAndGet()
                    workerThread.set(Thread.currentThread())
                    loaderStarted.complete(Unit)
                    allowLoaderReturn.await()
                    loaderReturning.countDown()
                    41
                }
            }
            loaderStarted.await()

            val lock = singleFlight.lockForTest()
            synchronized(lock) {
                allowLoaderReturn.complete(Unit)
                assertTrue(loaderReturning.await(2, TimeUnit.SECONDS), "loader did not return")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (workerThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                assertEquals(Thread.State.BLOCKED, workerThread.get().state)
                val generation = assertNotNull(singleFlight.inFlightForTest()["atomic"])
                assertFalse(generation.isCompleted, "result became visible before identity removal")
            }

            assertEquals(41, first.await())
            assertEquals(42, singleFlight.get("atomic") { loads.incrementAndGet(); 42 })
            assertEquals(2, loads.get())
        }
    }

    @Test
    fun `timeout cancels loader`() = runTest {
        SingleFlight<String, Unit>(25.milliseconds).use { singleFlight ->
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                singleFlight.get("slow") { delay(10.seconds) }
            }
        }
    }

    @Test
    fun `cancelling one waiter does not cancel shared work`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val cancelledWaiter = async {
                singleFlight.get("key") {
                    loads.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    9
                }
            }
            started.await()
            val survivingWaiter = async { singleFlight.get("key") { error("must be deduplicated") } }
            cancelledWaiter.cancelAndJoin()
            release.complete(Unit)

            assertEquals(9, survivingWaiter.await())
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun `capacity is bounded and released after completion`() = runTest {
        SingleFlight<String, Int>(1.seconds, maximumEntries = 1).use { singleFlight ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                singleFlight.get("one") {
                    started.complete(Unit)
                    release.await()
                    1
                }
            }
            started.await()
            assertFailsWith<SingleFlightCapacityExceeded> { singleFlight.get("two") { 2 } }
            release.complete(Unit)
            assertEquals(1, first.await())
            withContext(Dispatchers.Default) {
                withTimeout(1.seconds) {
                    while (runCatching { singleFlight.get("two") { 2 } }.isFailure) delay(1)
                }
            }
        }
    }

    @Test
    fun `close cancels shared work`() = runTest {
        val singleFlight = SingleFlight<String, Unit>(10.seconds)
        val started = CompletableDeferred<Unit>()
        val caller = async {
            singleFlight.get("key") {
                started.complete(Unit)
                delay(10.seconds)
            }
        }
        started.await()
        singleFlight.close()

        assertFailsWith<CancellationException> { caller.await() }
    }

    @Test
    fun `close completes an in-flight waiter before its worker begins`() = runTest {
        val singleFlight = SingleFlight<String, Unit>(10.seconds)
        val queuedGeneration = CompletableDeferred<Unit>()
        singleFlight.inFlightForTest()["queued"] = queuedGeneration
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.get("queued") { error("worker must not begin") }
        }

        singleFlight.close()

        val failure = assertFailsWith<CancellationException> { waiter.await() }
        assertEquals("SingleFlight is closed", failure.message)
        assertEquals(true, queuedGeneration.isCancelled)
    }

    @Test
    fun `close is idempotent`() {
        val singleFlight = SingleFlight<String, Unit>(10.seconds)

        singleFlight.close()
        singleFlight.close()
        singleFlight.close()
    }

    @Test
    fun `get after close rejects without running loader`() = runTest {
        val singleFlight = SingleFlight<String, Unit>(10.seconds)
        val loads = AtomicInteger()
        singleFlight.close()

        val failure = assertFailsWith<CancellationException> {
            singleFlight.get("closed") {
                throw AssertionError("loader ran ${loads.incrementAndGet()} time(s)")
            }
        }
        assertEquals("SingleFlight is closed", failure.message)
        assertEquals(0, loads.get())
    }
}

@Suppress("UNCHECKED_CAST")
private fun <K, V> SingleFlight<K, V>.inFlightForTest(): MutableMap<K, kotlinx.coroutines.Deferred<V>> {
    val field = SingleFlight::class.java.getDeclaredField("inFlight")
    field.isAccessible = true
    return assertNotNull(field.get(this) as? MutableMap<K, kotlinx.coroutines.Deferred<V>>)
}

private fun SingleFlight<*, *>.lockForTest(): Any {
    val field = SingleFlight::class.java.getDeclaredField("lock")
    field.isAccessible = true
    return assertNotNull(field.get(this))
}
