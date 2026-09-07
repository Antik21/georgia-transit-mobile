package com.denis.georgiatransit.bff.cache

import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class BoundedKeyedTtlCacheTest {
    @Test
    fun `same key waiters share the initial failed generation and retry starts a new one`() = runTest {
        val cache = BoundedKeyedTtlCache<String, Int>(60.seconds, 2)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val expected = MarkerFailure()

        cache.use {
            supervisorScope {
                val waiters = List(20) {
                    async {
                        cache.getOrLoad("key") {
                            loads.incrementAndGet()
                            started.complete(Unit)
                            release.await()
                            throw expected
                        }
                    }
                }
                started.await()
                release.complete(Unit)

                waiters.forEach { waiter ->
                    val failure = assertFailsWith<MarkerFailure> { waiter.await() }
                    assertSame(expected, failure)
                }
            }
            assertEquals(1, loads.get())
            assertEquals(42, cache.getOrLoad("key") { loads.incrementAndGet(); 42 })
            assertEquals(2, loads.get())
        }
    }

    @Test
    fun `cancelled loader generation is evicted and retry succeeds`() = runTest {
        val cache = BoundedKeyedTtlCache<String, Int>(60.seconds, 1)
        cache.use {
            assertFailsWith<CancellationException> {
                cache.getOrLoad("key") { throw CancellationException("provider cancelled") }
            }
            assertEquals(7, cache.getOrLoad("key") { 7 })
        }
    }

    @Test
    fun `cancelling a waiter does not remove a successful shared generation`() = runTest {
        val cache = BoundedKeyedTtlCache<String, Int>(60.seconds, 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        cache.use {
            val cancelledWaiter = async {
                cache.getOrLoad("key") {
                    loads.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    9
                }
            }
            started.await()
            val survivingWaiter = async { cache.getOrLoad("key") { error("must share") } }
            cancelledWaiter.cancelAndJoin()
            release.complete(Unit)

            assertEquals(9, survivingWaiter.await())
            assertEquals(9, cache.getOrLoad("key") { error("successful value must remain") })
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun `clock expiry prunes 256 ready entries and frees capacity deterministically`() = runTest {
        val clock = TestClock(Instant.EPOCH)
        val cache = BoundedKeyedTtlCache<Int, Int>(10.seconds, 256, clock)
        cache.use {
            repeat(256) { key -> assertEquals(key, cache.getOrLoad(key) { key }) }
            assertFailsWith<UpstreamUnavailable> { cache.getOrLoad(256) { 256 } }

            clock.advanceSeconds(10)
            assertEquals(256, cache.getOrLoad(256) { 256 })
        }
    }

    @Test
    fun `capacity pruning retains loading and live entries`() = runTest {
        val clock = TestClock(Instant.EPOCH)
        val cache = BoundedKeyedTtlCache<String, Int>(10.seconds, 2, clock)
        val loadingStarted = CompletableDeferred<Unit>()
        val releaseLoading = CompletableDeferred<Unit>()
        cache.use {
            assertEquals(1, cache.getOrLoad("live") { 1 })
            val loading = async {
                cache.getOrLoad("loading") {
                    loadingStarted.complete(Unit)
                    releaseLoading.await()
                    2
                }
            }
            loadingStarted.await()

            assertFailsWith<UpstreamUnavailable> { cache.getOrLoad("third") { 3 } }
            clock.advanceSeconds(9)
            assertFailsWith<UpstreamUnavailable> { cache.getOrLoad("third") { 3 } }
            clock.advanceSeconds(1)
            assertEquals(3, cache.getOrLoad("third") { 3 })

            releaseLoading.complete(Unit)
            assertEquals(2, loading.await())
            assertEquals(2, cache.getOrLoad("loading") { error("loading generation was lost") })
        }
    }
}

private class MarkerFailure : RuntimeException()

private class TestClock(private var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
