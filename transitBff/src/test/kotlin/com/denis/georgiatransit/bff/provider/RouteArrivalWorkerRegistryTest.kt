package com.denis.georgiatransit.bff.provider

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouteArrivalWorkerRegistryTest {
    @Test
    fun `one route worker refreshes in background and stops after idle timeout`() = runBlocking {
        val calls = AtomicInteger()
        RouteArrivalWorkerRegistry(
            pollInterval = Duration.ofMillis(10),
            idleTimeout = Duration.ofMillis(80),
            loader = { calls.incrementAndGet() },
        ).use { registry ->
            assertEquals(1, registry.get("route-a"))
            delay(35)
            assertTrue(calls.get() >= 2)
            assertEquals(1, registry.activeRouteCount())

            delay(80)
            assertEquals(0, registry.activeRouteCount())
            val stoppedAt = calls.get()
            delay(25)
            assertEquals(stoppedAt, calls.get())
        }
    }

    @Test
    fun `repeated requests share a worker and extend its lifetime`() = runBlocking {
        val calls = AtomicInteger()
        RouteArrivalWorkerRegistry(
            pollInterval = Duration.ofMillis(10),
            idleTimeout = Duration.ofMillis(60),
            loader = { calls.incrementAndGet() },
        ).use { registry ->
            registry.get("route-a")
            delay(40)
            registry.get("route-a")
            delay(35)
            assertEquals(1, registry.activeRouteCount())
            assertTrue(calls.get() >= 3)
            delay(40)
            assertEquals(0, registry.activeRouteCount())
        }
    }

    @Test
    fun `different routes have independent workers`() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        RouteArrivalWorkerRegistry(
            pollInterval = Duration.ofSeconds(1),
            idleTimeout = Duration.ofSeconds(1),
            loader = { routeId -> synchronized(calls) { calls[routeId] = (calls[routeId] ?: 0) + 1 }; routeId },
        ).use { registry ->
            assertEquals("route-a", registry.get("route-a"))
            assertEquals("route-b", registry.get("route-b"))
            assertEquals(2, registry.activeRouteCount())
            assertEquals(mapOf("route-a" to 1, "route-b" to 1), calls)
        }
    }

    @Test
    fun `worker keeps running after a failed refresh`() = runBlocking {
        val calls = AtomicInteger()
        RouteArrivalWorkerRegistry(
            pollInterval = Duration.ofMillis(10),
            idleTimeout = Duration.ofSeconds(1),
            loader = {
                if (calls.incrementAndGet() == 1) throw ProviderUnavailable("temporary")
                42
            },
        ).use { registry ->
            assertFailsWith<ProviderUnavailable> { registry.get("route-a") }
            delay(25)
            assertEquals(42, registry.get("route-a"))
            assertTrue(calls.get() >= 2)
        }
    }
}
