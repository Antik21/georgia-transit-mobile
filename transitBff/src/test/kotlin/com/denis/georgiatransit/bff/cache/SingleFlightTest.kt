package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.SingleFlightCapacityExceeded
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SingleFlightTest {
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
    fun `completed handoff generation remains shareable until worker cleanup`() = runTest {
        SingleFlight<String, Int>(1.seconds).use { singleFlight ->
            val completedGeneration = CompletableDeferred(11)
            val inFlight = singleFlight.inFlightForTest()
            inFlight["handoff"] = completedGeneration
            val replacementLoads = AtomicInteger()

            assertEquals(11, singleFlight.get("handoff") { replacementLoads.incrementAndGet(); 22 })
            assertEquals(0, replacementLoads.get())
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
