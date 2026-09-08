package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.FakeAdapter
import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.RequestRateLimited
import com.denis.georgiatransit.bff.api.retryAfterHeader
import com.denis.georgiatransit.bff.api.RouteNotFound
import com.denis.georgiatransit.bff.api.ServiceFailure
import com.denis.georgiatransit.bff.api.StateConflict
import com.denis.georgiatransit.bff.api.StopNotFound
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.api.UpstreamTimeout
import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import com.denis.georgiatransit.bff.capabilities
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderConflict
import com.denis.georgiatransit.bff.provider.ProviderInvalidArgument
import com.denis.georgiatransit.bff.provider.ProviderJsonDecodeFailure
import com.denis.georgiatransit.bff.provider.ProviderNormalizedSchemaFailure
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import com.denis.georgiatransit.bff.route
import com.denis.georgiatransit.bff.stop
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitServiceTest {
    @Test
    fun `capability false returns 501 semantics through a configured adapter`() = runTest {
        val adapter = FakeAdapter(city = FakeAdapter().city.copy(capabilities = capabilities(routes = false)))
        service(adapter).use { transit ->
            val failure = assertFailsWith<CapabilityNotAvailable> { transit.routes("test", "en", null) }
            assertEquals(501, failure.status.value)
            assertEquals("CAPABILITY_NOT_AVAILABLE", failure.errorCode)
            assertTrue(failure.message.contains("test"))
            assertEquals(0, adapter.routesCalls.get())
        }
    }

    @Test
    fun `provider failures map to stable service statuses and error codes`() = runTest {
        val cases = listOf(
            ProviderInvalidArgument("bad query") to Triple(400, "INVALID_ARGUMENT", InvalidArgument::class),
            ProviderRouteNotFound("missing route") to Triple(404, "ROUTE_NOT_FOUND", RouteNotFound::class),
            ProviderConflict("changed") to Triple(409, "PROVIDER_ID_CHANGED", StateConflict::class),
            ProviderRateLimited("slow down", 17) to Triple(429, "RATE_LIMITED", RequestRateLimited::class),
            ProviderCapabilityUnavailable("unsupported") to
                Triple(501, "CAPABILITY_NOT_AVAILABLE", CapabilityNotAvailable::class),
            ProviderBadGateway("raw secret provider payload") to Triple(502, "UPSTREAM_BAD_RESPONSE", UpstreamBadGateway::class),
            ProviderUnavailable("down", 9) to Triple(503, "UPSTREAM_UNAVAILABLE", UpstreamUnavailable::class),
            ProviderTimeout("late") to Triple(504, "UPSTREAM_TIMEOUT", UpstreamTimeout::class),
        )

        cases.forEach { (providerFailure, expected) ->
            val adapter = FakeAdapter().apply { routesResult = { throw providerFailure } }
            service(adapter).use { transit ->
                val failure = assertFailsWith<ServiceFailure> { transit.routes("test", "en", null) }
                assertEquals(expected.first, failure.status.value)
                assertEquals(expected.second, failure.errorCode)
                assertTrue(expected.third.isInstance(failure))
                when (providerFailure) {
                    is ProviderRateLimited -> assertEquals("17", failure.retryAfterHeader())
                    is ProviderUnavailable -> assertEquals("9", failure.retryAfterHeader())
                    else -> Unit
                }
                if (providerFailure is ProviderBadGateway) {
                    assertFalse(failure.message.contains("secret"))
                }
            }
        }
    }

    @Test
    fun `stop not found remains entity aware`() = runTest {
        val adapter = FakeAdapter().apply { arrivalsResult = { throw ProviderStopNotFound("missing stop") } }
        service(adapter).use { transit ->
            val failure = assertFailsWith<StopNotFound> { transit.arrivals("test", stop.id, 1, "en") }
            assertEquals(404, failure.status.value)
            assertEquals("STOP_NOT_FOUND", failure.errorCode)
        }
    }

    @Test
    fun `invalid normalized response maps to safe 502 without provider leakage`() = runTest {
        val adapter = FakeAdapter().apply {
            routesResult = { listOf(route.copy(color = "provider-secret-color")) }
        }
        service(adapter).use { transit ->
            val failure = assertFailsWith<UpstreamBadGateway> { transit.routes("test", "en", null) }
            assertEquals("UPSTREAM_BAD_RESPONSE", failure.errorCode)
            assertEquals("The provider returned an invalid response", failure.message)
            assertFalse(failure.message.contains("provider-secret-color"))
        }
    }

    @Test
    fun `JSON decode and normalized schema classifications remain internal safe 502 responses`() = runTest {
        listOf(
            ProviderJsonDecodeFailure("raw-json-provider-payload"),
            ProviderNormalizedSchemaFailure("raw-normalized-provider-payload"),
        ).forEach { providerFailure ->
            val adapter = FakeAdapter().apply { routesResult = { throw providerFailure } }
            service(adapter).use { transit ->
                val failure = assertFailsWith<UpstreamBadGateway> { transit.routes("test", "en", null) }
                assertEquals("UPSTREAM_BAD_RESPONSE", failure.errorCode)
                assertEquals("The provider returned an invalid response", failure.message)
                assertFalse(failure.message.contains("raw-"))
            }
        }
    }

    @Test
    fun `nearby stops use cached directory with Haversine radius ordering and limit`() = runTest {
        val close = stop.copy(id = "test:provider:stop:close", providerId = "close", position = GeoPoint(41.7001, 44.8))
        val medium = stop.copy(id = "test:provider:stop:medium", providerId = "medium", position = GeoPoint(41.701, 44.8))
        val far = stop.copy(id = "test:provider:stop:far", providerId = "far", position = GeoPoint(41.72, 44.8))
        val adapter = FakeAdapter().apply { stopDirectoryResult = { listOf(far, medium, close) } }
        service(adapter).use { transit ->
            val first = transit.nearbyStops("test", GeoPoint(41.7, 44.8), radiusMeters = 200, limit = 2, locale = "en")
            val second = transit.nearbyStops("test", GeoPoint(41.7, 44.8), radiusMeters = 20, limit = 10, locale = "en")

            assertEquals(listOf(close.id, medium.id), first.map { it.id })
            assertEquals(listOf(close.id), second.map { it.id })
            assertEquals(1, adapter.stopDirectoryCalls.get())
        }
    }

    @Test
    fun `directory cache key count is bounded`() = runTest {
        val adapter = FakeAdapter()
        service(adapter).use { transit ->
            repeat(256) { index -> transit.routes("test", "locale-$index", null) }
            val failure = assertFailsWith<UpstreamUnavailable> { transit.routes("test", "overflow", null) }
            assertEquals("UPSTREAM_UNAVAILABLE", failure.errorCode)
            assertEquals("1", failure.retryAfterHeader())
        }
    }

    @Test
    fun `failed distinct cache keys cannot exhaust capacity`() = runTest {
        val adapter = FakeAdapter().apply {
            routesResult = { throw ProviderUnavailable("temporary") }
        }
        service(adapter).use { transit ->
            repeat(256) { index ->
                assertFailsWith<UpstreamUnavailable> { transit.routes("test", "failed-$index", null) }
            }

            adapter.routesResult = { listOf(route) }
            assertEquals(listOf(route), transit.routes("test", "valid-after-failures", null))
        }
    }

    @Test
    fun `same key callers coalesce through the service cache`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val adapter = FakeAdapter().apply {
            routesResult = {
                loads.incrementAndGet()
                started.complete(Unit)
                release.await()
                listOf(route)
            }
        }
        service(adapter).use { transit ->
            val calls = List(20) { async { transit.routes("test", "same", null) } }
            started.await()
            release.complete(Unit)

            assertEquals(List(20) { listOf(route) }, calls.awaitAll())
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun `generic adapters are circuit protected before further upstream calls`() = runTest {
        val adapter = FakeAdapter().apply { routesResult = { throw ProviderUnavailable("upstream failure") } }
        val observability = BffObservability(
            config = BffConfig.fromEnvironment(
                mapOf(
                    "BFF_FIXTURES_ENABLED" to "true",
                    "BFF_CIRCUIT_FAILURE_THRESHOLD" to "2",
                ),
            ),
            allowedProviders = setOf("test" to TelemetryProvider.FIXTURE),
        )
        instrumentedService(adapter, observability).use { transit ->
            repeat(3) { assertFailsWith<UpstreamUnavailable> { transit.routes("test", "en", null) } }
            assertEquals(2, adapter.routesCalls.get(), "open circuit must block the generic adapter")
            assertTrue(observability.render().contains("event=\"circuit_rejected\""))
        }
    }

    @Test
    fun `single flight timeout maps to 504`() = runTest {
        val adapter = FakeAdapter().apply {
            vehiclesResult = {
                delay(2_000)
                error("unreachable")
            }
        }
        service(adapter, realtimeSeconds = 1).use { transit ->
            val failure = assertFailsWith<UpstreamTimeout> { transit.vehicles("test", route.id, null) }
            assertEquals(504, failure.status.value)
            assertEquals("UPSTREAM_TIMEOUT", failure.errorCode)
        }
    }
}

private fun service(adapter: FakeAdapter, realtimeSeconds: Long = 15): TransitService =
    TransitService(
        registry = ProviderRegistry(listOf(adapter)),
        directoryCacheTtlSeconds = 3_600,
        shapeCacheTtlSeconds = 3_600,
        realtimeSingleFlightSeconds = realtimeSeconds,
    )

private fun instrumentedService(adapter: FakeAdapter, observability: BffObservability): TransitService = TransitService(
    capabilitySnapshots = IntrinsicCapabilitySnapshotSource(ProviderRegistry(listOf(adapter))),
    directoryCacheTtlSeconds = 3_600,
    shapeCacheTtlSeconds = 3_600,
    realtimeSingleFlightSeconds = 15,
    observability = observability,
)
