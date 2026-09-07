package com.denis.georgiatransit.bff.cache

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ExpiringCacheTest {
    @Test
    fun `cache hits until expiry and refreshes at expiry`() = runTest {
        val clock = MutableClock(Instant.EPOCH)
        val cache = ExpiringCache<Int>(10.seconds, clock)
        var loads = 0

        assertEquals(1, cache.getOrLoad { ++loads })
        clock.advanceSeconds(9)
        assertEquals(1, cache.getOrLoad { ++loads })
        clock.advanceSeconds(1)
        assertEquals(2, cache.getOrLoad { ++loads })
        assertEquals(2, loads)
    }

    @Test
    fun `concurrent misses share one refresh`() = runTest {
        val cache = ExpiringCache<Int>(10.seconds, MutableClock(Instant.EPOCH))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()

        val callers = List(20) {
            async {
                cache.getOrLoad {
                    loads.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    42
                }
            }
        }
        started.await()
        release.complete(Unit)

        assertEquals(List(20) { 42 }, callers.awaitAll())
        assertEquals(1, loads.get())
    }
}

private class MutableClock(private var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
