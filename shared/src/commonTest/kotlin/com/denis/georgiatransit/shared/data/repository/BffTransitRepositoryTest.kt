package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheEntry
import com.denis.georgiatransit.shared.data.cache.TransitCacheStore
import com.denis.georgiatransit.shared.data.cache.TransitClock
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.network.TransitBffClient
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BffTransitRepositoryTest {
    @Test
    fun nearbyCacheIsFreshOnlyForTheExactCityViewportLimitAndLocaleQuery() = runTest {
        val store = MemoryStore()
        val clock = MutableClock(1_000_000L)
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(store, clock) { request ->
            requests += request
            jsonResponse(nearbyStopsJson())
        }
        val city = CityId("demo")
        val center = GeoPoint(41.7, 44.8)

        val network = assertIs<TransitLoadResult.Data<*>>(
            repository.nearbyStops(city, center, 1_000, 50, TransitLocale.English),
        )
        val cached = assertIs<TransitLoadResult.Data<*>>(
            repository.nearbyStops(city, center, 1_000, 50, TransitLocale.English),
        )
        assertEquals(TransitFreshness.Network, network.freshness)
        assertEquals(TransitFreshness.CacheValid, cached.freshness)
        assertEquals(1, requests.size)

        repository.nearbyStops(city, GeoPoint(41.70001, 44.8), 1_000, 50, TransitLocale.English)
        repository.nearbyStops(city, center, 1_025, 50, TransitLocale.English)
        repository.nearbyStops(city, center, 1_000, 49, TransitLocale.English)
        repository.nearbyStops(city, center, 1_000, 50, TransitLocale.Russian)
        repository.nearbyStops(CityId("other"), center, 1_000, 50, TransitLocale.English)

        assertEquals(6, requests.size, "No different nearby query may reuse another viewport's LKG")
        assertEquals(6, store.keys(NearbyCachePrefixForTest).size)
    }

    @Test
    fun expiredNearbyEntryUsesStaleLkgOnlyForAllowedFailureAndKeepsFailureVisible() = runTest {
        val clock = MutableClock(1_000_000L)
        var online = true
        val repository = repository(MemoryStore(), clock) {
            if (online) jsonResponse(nearbyStopsJson()) else throw IOException("offline")
        }
        val request: suspend () -> TransitLoadResult<List<TransitStop>> = {
            repository.nearbyStops(CityId("demo"), GeoPoint(41.7, 44.8), 1_000, 50, TransitLocale.English)
        }
        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(request()).freshness)
        clock.now += NEARBY_TTL_MILLIS
        assertEquals(TransitFreshness.CacheValid, assertIs<TransitLoadResult.Data<*>>(request()).freshness)

        clock.now += 1L
        online = false
        val stale = assertIs<TransitLoadResult.Data<List<TransitStop>>>(request())
        assertEquals(TransitFreshness.StaleOffline, stale.freshness)
        assertIs<TransitFailure.Transport>(stale.revalidationFailure)
        assertEquals("demo:fixture:stop:center", stale.value.single().id.value)
    }

    @Test
    fun cityNotFoundAndProviderConflictAreNeverMaskedByNearbyLkg() = runTest {
        val clock = MutableClock(1_000_000L)
        var failureCode: String? = null
        val store = MemoryStore()
        val repository = repository(store, clock) {
            failureCode?.let { code ->
                val status = if (code == "CITY_NOT_FOUND") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                jsonResponse(errorJson(code), status)
            } ?: jsonResponse(nearbyStopsJson())
        }
        val request: suspend () -> TransitLoadResult<List<TransitStop>> = {
            repository.nearbyStops(CityId("demo"), GeoPoint(41.7, 44.8), 1_000, 50, TransitLocale.English)
        }
        request()
        clock.now += NEARBY_TTL_MILLIS + 1L

        failureCode = "CITY_NOT_FOUND"
        assertIs<TransitFailure.CityNotFound>(assertIs<TransitLoadResult.Failure>(request()).error)
        assertEquals(1, store.keys(NearbyCachePrefixForTest).size, "404 remains available for catalog-driven revalidation")

        failureCode = "PROVIDER_ID_CHANGED"
        assertIs<TransitFailure.ProviderIdChanged>(assertIs<TransitLoadResult.Failure>(request()).error)
        assertTrue(store.keys(NearbyCachePrefixForTest).isEmpty(), "409 must invalidate the exact stale provider identity")
    }

    @Test
    fun nearbyCacheIsBoundedAndCancellationEscapesWithoutWritingAnEntry() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        var cancel = false
        val repository = repository(store, clock) {
            if (cancel) throw CancellationException("cancelled")
            jsonResponse(nearbyStopsJson())
        }

        repeat(25) { index ->
            repository.nearbyStops(
                CityId("demo"),
                GeoPoint(41.7 + index * 0.00001, 44.8),
                1_000,
                50,
                TransitLocale.English,
            )
            clock.now += 1L
        }
        assertEquals(24, store.keys(NearbyCachePrefixForTest).size)

        cancel = true
        assertFailsWith<CancellationException> {
            repository.nearbyStops(
                CityId("cancelled"),
                GeoPoint(41.7, 44.8),
                1_000,
                50,
                TransitLocale.English,
            )
        }
        assertTrue(store.keys(NearbyCachePrefixForTest).none { it.contains("cancelled") })
    }

    @Test
    fun forcedCityRevalidationBypassesAStillValidCityCache() = runTest {
        val clock = MutableClock(1_000_000L)
        var routes = true
        var requests = 0
        val repository = repository(MemoryStore(), clock) {
            requests += 1
            jsonResponse(citiesJson(routes = routes))
        }

        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(
            repository.refreshCityCapabilities(),
        ).freshness)
        routes = false
        val forced = assertIs<TransitLoadResult.Data<List<TransitCity>>>(repository.revalidateCityCapabilities())

        assertEquals(2, requests)
        assertFalse(forced.value.single().capabilities.routes)
    }

    @Test
    fun routeCacheIsFreshStrictlyBefore48HoursAndRevalidatesAtAndAfterTheBoundary() = runTest {
        val store = MemoryStore()
        val clock = MutableClock(1_000_000L)
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(store, clock) { request ->
            requests += request
            if (requests.size == 1) jsonResponse(routesJson(), eTag = "v1")
            else jsonResponse("", HttpStatusCode.NotModified, eTag = "v1")
        }
        val request = RouteListRequest(CityId("demo"))

        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        clock.now += ROUTE_TTL_MILLIS - 1L
        assertEquals(TransitFreshness.CacheValid, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        assertEquals(1, requests.size)

        clock.now += 1L
        assertEquals(TransitFreshness.NetworkValidated, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        assertEquals(2, requests.size)
        assertEquals("v1", requests.last().headers[HttpHeaders.IfNoneMatch])

        clock.now += ROUTE_TTL_MILLIS + 1L
        assertEquals(TransitFreshness.NetworkValidated, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        assertEquals(3, requests.size)
    }

    @Test
    fun route200AtomicallyReplacesPayloadEtagAndBothTimestamps() = runTest {
        val store = MemoryStore()
        val clock = MutableClock(1_000_000L)
        var response = 0
        val repository = repository(store, clock) {
            response += 1
            if (response == 1) jsonResponse(routesJson(suffix = "blue"), eTag = "old")
            else jsonResponse(routesJson(suffix = "green"), eTag = "new")
        }
        val request = RouteListRequest(CityId("demo"))

        repository.refreshRoutes(request)
        val original = routeEntry(store, request)
        clock.now += ROUTE_TTL_MILLIS
        val updated = assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request))
        val encoded = routeEntry(store, request)

        assertEquals(TransitFreshness.Network, updated.freshness)
        assertEquals(clock.now, entryLong(encoded, "fetchedAtEpochMillis"))
        assertEquals(clock.now, entryLong(encoded, "validatedAtEpochMillis"))
        assertEquals("new", entryString(encoded, "eTag"))
        assertTrue(encoded.contains("demo:fixture:route:green"))
        assertFalse(encoded.contains("demo:fixture:route:blue"))
        assertTrue(original.contains("demo:fixture:route:blue"))
    }

    @Test
    fun route304PreservesPayloadAndFetchTimeAdvancesValidationAndUsesResponseThenOldEtag() = runTest {
        val store = MemoryStore()
        val clock = MutableClock(1_000_000L)
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(store, clock) { request ->
            requests += request
            when (requests.size) {
                1 -> jsonResponse(routesJson(), eTag = "old")
                2 -> jsonResponse("", HttpStatusCode.NotModified, eTag = "response")
                else -> jsonResponse("", HttpStatusCode.NotModified)
            }
        }
        val request = RouteListRequest(CityId("demo"))

        repository.refreshRoutes(request)
        val original = routeEntry(store, request)
        val originalPayload = entryPayload(original)
        val fetchedAt = entryLong(original, "fetchedAtEpochMillis")

        clock.now += ROUTE_TTL_MILLIS
        repository.refreshRoutes(request)
        val responseEtagEntry = routeEntry(store, request)
        assertEquals("old", requests[1].headers[HttpHeaders.IfNoneMatch])
        assertEquals(fetchedAt, entryLong(responseEtagEntry, "fetchedAtEpochMillis"))
        assertEquals(clock.now, entryLong(responseEtagEntry, "validatedAtEpochMillis"))
        assertEquals("response", entryString(responseEtagEntry, "eTag"))
        assertEquals(originalPayload, entryPayload(responseEtagEntry))

        clock.now += ROUTE_TTL_MILLIS
        repository.refreshRoutes(request)
        val retainedEtagEntry = routeEntry(store, request)
        assertEquals("response", requests[2].headers[HttpHeaders.IfNoneMatch])
        assertEquals("response", entryString(retainedEtagEntry, "eTag"))
        assertEquals(fetchedAt, entryLong(retainedEtagEntry, "fetchedAtEpochMillis"))
        assertEquals(clock.now, entryLong(retainedEtagEntry, "validatedAtEpochMillis"))
        assertEquals(originalPayload, entryPayload(retainedEtagEntry))
    }

    @Test
    fun notModifiedWithoutCacheAndNoCacheTransportFailureAreExplicitFailures() = runTest {
        val clock = MutableClock(1_000_000L)
        val noCache304 = repository(MemoryStore(), clock) { jsonResponse("", HttpStatusCode.NotModified) }
        val request = RouteListRequest(CityId("demo"))
        assertIs<TransitFailure.InvalidResponse>(assertIs<TransitLoadResult.Failure>(noCache304.refreshRoutes(request)).error)

        val noCacheTransport = repository(MemoryStore(), clock) { throw IOException("offline") }
        assertIs<TransitFailure.Transport>(assertIs<TransitLoadResult.Failure>(noCacheTransport.refreshRoutes(request)).error)
    }

    @Test
    fun expiredCatalogReturnsUnchangedLkgForTransportAndRetryableHttpFailures() = runTest {
        val failures = listOf(
            null to TransitFailure.Transport::class,
            (HttpStatusCode.TooManyRequests to "RATE_LIMITED") to TransitFailure.RateLimited::class,
            (HttpStatusCode.BadGateway to "UPSTREAM_BAD_RESPONSE") to TransitFailure.UpstreamBadResponse::class,
            (HttpStatusCode.ServiceUnavailable to "UPSTREAM_UNAVAILABLE") to TransitFailure.UpstreamUnavailable::class,
            (HttpStatusCode.GatewayTimeout to "UPSTREAM_TIMEOUT") to TransitFailure.UpstreamTimeout::class,
        )

        failures.forEach { (httpFailure, expectedClass) ->
            val clock = MutableClock(1_000_000L)
            val store = MemoryStore()
            var online = true
            var failureAttempts = 0
            val repository = repository(store, clock) {
                if (online) jsonResponse(routesJson(), eTag = "lkg")
                else {
                    failureAttempts += 1
                    if (httpFailure == null) throw IOException("offline")
                    else jsonResponse(errorJson(httpFailure.second), httpFailure.first)
                }
            }
            val request = RouteListRequest(CityId("demo"))
            repository.refreshRoutes(request)
            val persisted = routeEntry(store, request)
            clock.now += ROUTE_TTL_MILLIS
            online = false

            val stale = assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request))

            assertEquals(TransitFreshness.StaleOffline, stale.freshness)
            val failure = requireNotNull(stale.revalidationFailure)
            assertEquals(expectedClass, failure::class)
            assertEquals(if (httpFailure != null) "req-123" else null, failure.requestId)
            assertEquals(3, failureAttempts, "Each permitted GET failure must exhaust two bounded retries")
            if (failure is TransitFailure.RateLimited) assertEquals(5, failure.retryAfterSeconds)
            if (failure is TransitFailure.UpstreamUnavailable) assertEquals(5, failure.retryAfterSeconds)
            assertEquals(1_000_000L, stale.validatedAtEpochMillis)
            assertEquals(persisted, routeEntry(store, request), "LKG bytes must not change for $expectedClass")
            assertEquals("demo:fixture:route:blue", repository.routes(CityId("demo")).single().id.value)
        }
    }

    @Test
    fun providerIdChangedInvalidatesRouteCacheAndCityCapabilitiesOrRemovalInvalidateAllAffectedRoutes() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        var phase = 0
        val repository = repository(store, clock) { request ->
            when (phase) {
                0 -> jsonResponse(routesJson())
                1 -> jsonResponse(errorJson("PROVIDER_ID_CHANGED"), HttpStatusCode.Conflict)
                2 -> jsonResponse(citiesJson(routes = false))
                else -> error("Unexpected ${request.url}")
            }
        }
        val request = RouteListRequest(CityId("demo"))
        repository.refreshRoutes(request)
        assertTrue(store.keys("transit-bff-v1.routes.").isNotEmpty())

        phase = 1
        clock.now += ROUTE_TTL_MILLIS
        assertIs<TransitFailure.ProviderIdChanged>(assertIs<TransitLoadResult.Failure>(repository.refreshRoutes(request)).error)
        assertTrue(store.keys("transit-bff-v1.routes.").isEmpty())
        assertTrue(repository.routes(CityId("demo")).isEmpty())

        phase = 0
        repository.refreshRoutes(request)
        phase = 2
        repository.refreshCityCapabilities()
        assertTrue(store.keys("transit-bff-v1.routes.").isEmpty())
        assertTrue(repository.routes(CityId("demo")).isEmpty())
    }

    @Test
    fun cacheRejectsCorruptWrongSchemaOversizeAndUnsoundTimestamps() {
        val store = MemoryStore()
        val cache = TransitCache(store)
        store.write("corrupt", "{")
        store.write("oversize", "x".repeat(21))
        store.write("wrong-schema", """{"schemaVersion":2,"fetchedAtEpochMillis":1,"validatedAtEpochMillis":1,"payload":[]}""")
        store.write("future", """{"schemaVersion":1,"fetchedAtEpochMillis":11,"validatedAtEpochMillis":11,"payload":[]}""")
        store.write("negative", """{"schemaVersion":1,"fetchedAtEpochMillis":-1,"validatedAtEpochMillis":0,"payload":[]}""")

        assertNull(cache.read<List<String>>("corrupt", 100, 10))
        assertNull(cache.read<List<String>>("oversize", 20, 10))
        assertNull(cache.read<List<String>>("wrong-schema", 100, 10))
        assertNull(cache.read<List<String>>("future", 100, 10))
        assertNull(cache.read<List<String>>("negative", 100, 10))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun routeCacheIsBoundedAndKeysAreIsolatedByCityLocaleAndMode() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        val repository = repository(store, clock) { request ->
            val mode = when (request.url.parameters["mode"]) {
                "metro" -> TransitMode.Metro
                "tram" -> TransitMode.Tram
                "ferry" -> TransitMode.Ferry
                else -> TransitMode.Bus
            }
            val cityId = request.url.encodedPath.substringAfter("/v1/cities/").substringBefore('/')
            jsonResponse(routesJson(cityId = cityId, mode = mode))
        }

        repeat(13) { index ->
            repository.refreshRoutes(RouteListRequest(CityId("city$index")))
            clock.now += 1L
        }
        assertEquals(12, store.keys("transit-bff-v1.routes.").size)
        assertFalse(store.keys(RouteCachePrefixForTest).any { it.contains("city0.") })

        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.Russian, TransitMode.Bus))
        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Metro))
        assertTrue(store.keys("transit-bff-v1.routes.").any { it.endsWith("demo.Russian.Bus") })
        assertTrue(store.keys("transit-bff-v1.routes.").any { it.endsWith("demo.English.Metro") })
    }

    @Test
    fun hydrationPublishesFreshAndStaleRoutesAndHonorsAuthoritativeCityEligibility() = runTest {
        val freshClock = MutableClock(1_000_000L)
        val freshStore = MemoryStore()
        val writer = repository(freshStore, freshClock) { request ->
            when {
                request.url.encodedPath.endsWith("/cities") -> jsonResponse(citiesJson(routes = true))
                else -> jsonResponse(
                    routesJson(mode = if (request.url.parameters["mode"] == "metro") TransitMode.Metro else TransitMode.Bus),
                )
            }
        }
        writer.refreshCityCapabilities()
        writer.refreshRoutes(RouteListRequest(CityId("demo")))
        writer.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.Russian, TransitMode.Bus))
        writer.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Metro))

        var freshNetworkCalls = 0
        val freshReader = repository(freshStore, freshClock) {
            freshNetworkCalls += 1
            error("Fresh hydration must avoid HTTP")
        }
        assertEquals(
            TransitFreshness.CacheValid,
            assertIs<TransitLoadResult.Data<*>>(freshReader.refreshRoutes(RouteListRequest(CityId("demo")))).freshness,
        )
        assertEquals(0, freshNetworkCalls)
        assertEquals("demo:fixture:route:blue", freshReader.routes(CityId("demo")).single().id.value)
        assertEquals(
            TransitMode.Bus,
            assertIs<TransitLoadResult.Data<List<TransitRoute>>>(
                freshReader.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.Russian, TransitMode.Bus)),
            ).value.single().mode,
        )
        assertEquals(
            TransitMode.Metro,
            assertIs<TransitLoadResult.Data<List<TransitRoute>>>(
                freshReader.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Metro)),
            ).value.single().mode,
        )
        assertEquals(0, freshNetworkCalls, "Hydrated locale/mode variants must remain isolated and fresh")

        val staleClock = MutableClock(freshClock.now + ROUTE_TTL_MILLIS)
        val staleReader = repository(freshStore, staleClock) { request ->
            if (request.url.encodedPath.endsWith("/cities")) jsonResponse(citiesJson(routes = true))
            else error("Only city revalidation is expected")
        }
        staleReader.revalidateCityCapabilities()
        assertEquals(
            "demo:fixture:route:blue",
            staleReader.routes(CityId("demo")).single().id.value,
            "Expired LKG must still hydrate into memory before route revalidation",
        )

        listOf(
            citiesJson(routes = false),
            citiesJson(routes = true, cityId = "other"),
        ).forEach { cityPayload ->
            val store = MemoryStore()
            val clock = MutableClock(2_000_000L)
            val seed = repository(store, clock) { request ->
                if (request.url.encodedPath.endsWith("/cities")) jsonResponse(cityPayload)
                else jsonResponse(routesJson())
            }
            seed.refreshCityCapabilities()
            seed.refreshRoutes(RouteListRequest(CityId("demo")))
            assertTrue(store.keys(RouteCachePrefixForTest).isNotEmpty())

            val reader = repository(store, clock) { error("Eligibility hydration must not call HTTP") }
            assertIs<TransitLoadResult.Data<*>>(reader.refreshCityCapabilities())
            assertTrue(reader.routes(CityId("demo")).isEmpty())
            assertTrue(store.keys(RouteCachePrefixForTest).isEmpty())
        }

        val noCityStore = MemoryStore()
        val noCityClock = MutableClock(3_000_000L)
        val noCityWriter = repository(noCityStore, noCityClock) { jsonResponse(routesJson()) }
        noCityWriter.refreshRoutes(RouteListRequest(CityId("demo")))
        noCityStore.remove(CityCacheKeyForTest)
        val noCityReader = repository(noCityStore, noCityClock) { error("No-city hydration must not call HTTP") }
        assertEquals(
            TransitFreshness.CacheValid,
            assertIs<TransitLoadResult.Data<*>>(noCityReader.refreshRoutes(RouteListRequest(CityId("demo")))).freshness,
        )
        assertEquals("demo:fixture:route:blue", noCityReader.routes(CityId("demo")).single().id.value)
    }

    @Test
    fun coldStartHydrationEvictsAValidRouteCatalogStoredUnderAnotherRouteKeyWithoutPublishingIt() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        val request = RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Bus)
        val writer = repository(store, clock) { networkRequest ->
            if (networkRequest.url.encodedPath.endsWith("/cities")) jsonResponse(citiesJson(routes = true))
            else jsonResponse(routesJson(mode = TransitMode.Bus))
        }
        writer.refreshCityCapabilities()
        writer.refreshRoutes(request)

        val validEncodedCatalog = routeEntry(store, request)
        val misplacedKey = routeCacheKeyForTest(
            RouteListRequest(CityId("other"), TransitLocale.Russian, TransitMode.Metro),
        )
        store.values.remove(routeCacheKeyForTest(request))
        store.values[misplacedKey] = validEncodedCatalog

        var networkCalls = 0
        val coldReader = repository(store, clock) {
            networkCalls += 1
            error("Cold-start hydration must not need HTTP for a valid city snapshot")
        }
        assertEquals(
            TransitFreshness.CacheValid,
            assertIs<TransitLoadResult.Data<*>>(coldReader.refreshCityCapabilities()).freshness,
        )

        assertEquals(0, networkCalls)
        assertTrue(coldReader.routes(CityId("demo")).isEmpty(), "Misplaced bytes must not enter the default route snapshot")
        assertTrue(coldReader.routes(CityId("other")).isEmpty(), "A foreign key must not publish another city variant")
        assertFalse(store.values.containsKey(misplacedKey), "Cold-start hydration must evict the semantically misplaced entry")
    }

    @Test
    fun modeCorruptCacheIsEvictedAndInvalidMode200LeavesOldLkgBytesUntouched() = runTest {
        val request = RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Bus)

        val corruptStore = MemoryStore()
        val corruptClock = MutableClock(1_000_000L)
        var corruptCalls = 0
        val corruptRepository = repository(corruptStore, corruptClock) { requestData ->
            corruptCalls += 1
            assertNull(requestData.headers[HttpHeaders.IfNoneMatch], "Evicted semantic corruption must not send its ETag")
            jsonResponse(routesJson(mode = TransitMode.Bus), eTag = "valid")
        }
        corruptRepository.refreshRoutes(request)
        val key = routeCacheKeyForTest(request)
        corruptStore.values[key] = requireNotNull(corruptStore.values[key]).replaceLast(
            oldValue = "\"mode\":\"Bus\"",
            newValue = "\"mode\":\"Metro\"",
        )
        val corruptReader = repository(corruptStore, corruptClock) { requestData ->
            corruptCalls += 1
            assertNull(requestData.headers[HttpHeaders.IfNoneMatch])
            jsonResponse(routesJson(mode = TransitMode.Bus), eTag = "repaired")
        }
        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(corruptReader.refreshRoutes(request)).freshness)
        assertEquals(2, corruptCalls)
        assertTrue(routeEntry(corruptStore, request).contains("\"mode\":\"Bus\""))

        val lkgStore = MemoryStore()
        val lkgClock = MutableClock(2_000_000L)
        var returnWrongMode = false
        val lkgRepository = repository(lkgStore, lkgClock) {
            if (returnWrongMode) jsonResponse(routesJson(mode = TransitMode.Metro), eTag = "wrong")
            else jsonResponse(routesJson(mode = TransitMode.Bus), eTag = "lkg")
        }
        lkgRepository.refreshRoutes(request)
        val before = routeEntry(lkgStore, request)
        lkgClock.now += ROUTE_TTL_MILLIS
        returnWrongMode = true

        assertIs<TransitFailure.InvalidResponse>(
            assertIs<TransitLoadResult.Failure>(lkgRepository.refreshRoutes(request)).error,
        )
        assertEquals(before, routeEntry(lkgStore, request))
    }

    @Test
    fun route404And501InvalidateEveryVariantForOnlyTheRequestedCity() = runTest {
        listOf(
            HttpStatusCode.NotFound to "CITY_NOT_FOUND",
            HttpStatusCode.NotImplemented to "CAPABILITY_NOT_AVAILABLE",
        ).forEach { (status, code) ->
            val clock = MutableClock(1_000_000L)
            val store = MemoryStore()
            var failure = false
            val repository = repository(store, clock) { request ->
                if (failure && request.url.encodedPath.contains("/cities/demo/routes")) {
                    jsonResponse(errorJson(code), status)
                } else {
                    val cityId = request.url.encodedPath.substringAfter("/v1/cities/").substringBefore('/')
                    val mode = if (request.url.parameters["mode"] == "metro") TransitMode.Metro else TransitMode.Bus
                    jsonResponse(routesJson(cityId = cityId, mode = mode))
                }
            }
            seedRouteVariants(repository)
            failure = true
            clock.now += ROUTE_TTL_MILLIS

            val result = assertIs<TransitLoadResult.Failure>(repository.refreshRoutes(RouteListRequest(CityId("demo"))))
            if (status == HttpStatusCode.NotFound) assertIs<TransitFailure.CityNotFound>(result.error)
            else assertIs<TransitFailure.CapabilityUnavailable>(result.error)
            assertTrue(store.keys(RouteCachePrefixForTest).none { it.contains(".demo.") })
            assertTrue(store.keys(RouteCachePrefixForTest).any { it.contains(".other.") })
            assertTrue(repository.routes(CityId("demo")).isEmpty())
            assertEquals("other:fixture:route:blue", repository.routes(CityId("other")).single().id.value)
        }
    }

    @Test
    fun providerIdChangeFromVehiclesArrivalsAndJourneysInvalidatesAllSameCityVariantsOnly() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        val repository = repository(store, clock) { request ->
            if (request.url.encodedPath.endsWith("/routes")) {
                val cityId = request.url.encodedPath.substringAfter("/v1/cities/").substringBefore('/')
                val mode = if (request.url.parameters["mode"] == "metro") TransitMode.Metro else TransitMode.Bus
                jsonResponse(routesJson(cityId = cityId, mode = mode))
            } else {
                jsonResponse(errorJson("PROVIDER_ID_CHANGED"), HttpStatusCode.Conflict)
            }
        }

        suspend fun assertInvalidates(operation: suspend () -> TransitLoadResult<*>) {
            seedRouteVariants(repository)
            assertTrue(store.keys(RouteCachePrefixForTest).count { it.contains(".demo.") } >= 2)
            assertIs<TransitFailure.ProviderIdChanged>(assertIs<TransitLoadResult.Failure>(operation()).error)
            assertTrue(store.keys(RouteCachePrefixForTest).none { it.contains(".demo.") })
            assertTrue(store.keys(RouteCachePrefixForTest).any { it.contains(".other.") })
            assertTrue(repository.routes(CityId("demo")).isEmpty())
            assertEquals("other:fixture:route:blue", repository.routes(CityId("other")).single().id.value)
        }

        assertInvalidates {
            repository.vehicles(CityId("demo"), RouteId("demo:fixture:route:blue"))
        }
        assertInvalidates {
            repository.arrivals(CityId("demo"), StopId("demo:fixture:stop:center"), 10, TransitLocale.English)
        }
        assertInvalidates {
            repository.journeys(
                CityId("demo"),
                GeoPoint(41.7, 44.8),
                GeoPoint(41.8, 44.9),
                Instant.parse("2030-01-01T00:00:00Z"),
                TransitLocale.English,
                2,
            )
        }
    }

    @Test
    fun cancelledExpiredRouteRefreshPreservesCacheAndConcurrentRefreshesRemainConsistent() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        var cancelled = false
        val repository = repository(store, clock) {
            if (cancelled) throw CancellationException("cancelled")
            jsonResponse(routesJson(), eTag = "lkg")
        }
        val request = RouteListRequest(CityId("demo"))
        repository.refreshRoutes(request)
        val before = routeEntry(store, request)
        clock.now += ROUTE_TTL_MILLIS
        cancelled = true

        assertFailsWith<CancellationException> { repository.refreshRoutes(request) }
        assertEquals(before, routeEntry(store, request))
        assertEquals("demo:fixture:route:blue", repository.routes(CityId("demo")).single().id.value)
    }

    @Test
    fun cacheWriteFailureAndConcurrentRefreshesDoNotHideNetworkDataOrRegressSnapshot() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore(failWrites = true)
        val repository = repository(store, clock) { jsonResponse(routesJson()) }
        val request = RouteListRequest(CityId("demo"))

        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        assertEquals("demo:fixture:route:blue", repository.routes(CityId("demo")).single().id.value)
        assertTrue(store.values.isEmpty())

        coroutineScope {
            repeat(20) { launch { repository.refreshRoutes(request) } }
        }
        assertEquals(listOf(RouteId("demo:fixture:route:blue")), repository.routes(CityId("demo")).map { it.id })
    }

    @Test
    fun lazyHydrationIsOnceOnlyPreservesCacheValidCitiesAndUsesInjectedCacheDispatcher() = runTest {
        val clock = MutableClock(1_000_000L)
        val store = MemoryStore()
        val cachedCities = listOf(cachedCity())
        TransitCache(store).write(
            CityCacheKeyForTest,
            TransitCacheEntry(
                fetchedAtEpochMillis = clock.now,
                validatedAtEpochMillis = clock.now,
                payload = cachedCities,
            ),
            64 * 1024,
        )
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val repository = repository(store, clock, cacheDispatcher = dispatcher) { jsonResponse(routesJson()) }

        assertTrue(repository.cities().isEmpty(), "Construction must not hydrate the synchronous cache on the caller thread")
        assertEquals(0, store.readCount(CityCacheKeyForTest))

        val cityResult = assertIs<TransitLoadResult.Data<*>>(repository.refreshCityCapabilities())
        assertEquals(TransitFreshness.CacheValid, cityResult.freshness)
        assertEquals(cachedCities, repository.cities())
        assertEquals(2, store.readCount(CityCacheKeyForTest), "One hydration read and one normal cache lookup are expected")

        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(
            repository.refreshRoutes(RouteListRequest(CityId("demo"))),
        ).freshness)
        assertEquals(2, store.readCount(CityCacheKeyForTest), "Route refresh must reuse the completed hydration")
        assertTrue(dispatcher.dispatchCount > 0, "Cache I/O must enter the injected cache dispatcher")
    }

    private fun repository(
        store: MemoryStore,
        clock: MutableClock,
        cacheDispatcher: CoroutineDispatcher? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = if (cacheDispatcher == null) {
        BffTransitRepository(
            client = TransitBffClient(mockHttpClient(handler), BffEndpointConfiguration("https://bff.example")),
            cache = TransitCache(store),
            clock = clock,
        )
    } else {
        BffTransitRepository(
            client = TransitBffClient(mockHttpClient(handler), BffEndpointConfiguration("https://bff.example")),
            cache = TransitCache(store),
            clock = clock,
            cacheDispatcher = cacheDispatcher,
        )
    }

    private fun mockHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        eTag: String? = null,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(
            *buildList {
                add(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                add(HttpHeaders.XRequestId to listOf("req-123"))
                eTag?.let { add(HttpHeaders.ETag to listOf(it)) }
            }.toTypedArray(),
        ),
    )

    private fun routesJson(
        cityId: String = "demo",
        mode: TransitMode = TransitMode.Bus,
        suffix: String = "blue",
    ): String {
        val wireMode = mode.name.lowercase()
        return """[{"id":"$cityId:fixture:route:$suffix","providerId":"$suffix","shortName":"D1","longName":{"ru":"Р","en":"Route","ka":"მ"},"color":"#0057B8","textColor":"#FFFFFF","mode":"$wireMode","directions":[]}]"""
    }

    private fun nearbyStopsJson() =
        """[{"id":"demo:fixture:stop:center","providerId":"center","code":"D001","name":{"ru":"Центр","en":"Center","ka":"ცენტრი"},"position":{"latitude":41.7,"longitude":44.8},"routeIds":[],"mode":"bus"}]"""

    private fun citiesJson(routes: Boolean, cityId: String = "demo") =
        """[{"id":"$cityId","name":{"ru":"Демо","en":"Demo","ka":"დემო"},"center":{"latitude":41.7,"longitude":44.8},"defaultZoom":13.0,"capabilities":{"routes":$routes,"stops":true,"routeGeometry":true,"vehiclePositions":true,"officialArrivals":true,"tripPlanning":true},"availability":{"readiness":"DEVELOPMENT_FIXTURE","source":"FIXTURE"}}]"""

    private fun errorJson(code: String) =
        """{"error":{"code":"$code","message":"contract message","retryAfterSeconds":5,"requestId":"req-123"}}"""

    private fun cachedCity() = TransitCity(
        id = CityId("demo"),
        name = "Demo",
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(true, true, true, true, true),
    )

    private suspend fun seedRouteVariants(repository: BffTransitRepository) {
        repository.refreshRoutes(RouteListRequest(CityId("demo")))
        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.Russian, TransitMode.Bus))
        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Metro))
        repository.refreshRoutes(RouteListRequest(CityId("other")))
    }

    private fun routeCacheKeyForTest(request: RouteListRequest): String =
        "$RouteCachePrefixForTest${request.cityId.value}.${request.locale.name}.${request.mode?.name ?: "all"}"

    private fun routeEntry(store: MemoryStore, request: RouteListRequest): String =
        requireNotNull(store.values[routeCacheKeyForTest(request)])

    private fun entryLong(encoded: String, field: String): Long =
        Json.parseToJsonElement(encoded).jsonObject.getValue(field).jsonPrimitive.content.toLong()

    private fun entryString(encoded: String, field: String): String =
        Json.parseToJsonElement(encoded).jsonObject.getValue(field).jsonPrimitive.content

    private fun entryPayload(encoded: String) = Json.parseToJsonElement(encoded).jsonObject.getValue("payload")

    private fun String.replaceLast(oldValue: String, newValue: String): String {
        val index = lastIndexOf(oldValue)
        require(index >= 0) { "Missing '$oldValue' in cached JSON" }
        return replaceRange(index, index + oldValue.length, newValue)
    }

    private class MemoryStore(private val failWrites: Boolean = false) : TransitCacheStore {
        val values = linkedMapOf<String, String>()
        private val readCounts = mutableMapOf<String, Int>()
        override fun read(key: String): String? {
            readCounts[key] = (readCounts[key] ?: 0) + 1
            return values[key]
        }
        override fun write(key: String, value: String): Boolean = !failWrites && values.put(key, value).let { true }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun keys(prefix: String): List<String> = values.keys.filter { it.startsWith(prefix) }
        fun readCount(key: String): Int = readCounts[key] ?: 0
    }

    private class MutableClock(var now: Long) : TransitClock {
        override fun nowEpochMillis(): Long = now
    }

    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatchCount = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount += 1
            delegate.dispatch(context, block)
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)
    }

    private companion object {
        const val CityCacheKeyForTest = "transit-bff-v1.cities"
        const val RouteCachePrefixForTest = "transit-bff-v1.routes."
        const val NearbyCachePrefixForTest = "transit-bff-v1.nearby."
        const val ROUTE_TTL_MILLIS = 48L * 60L * 60L * 1_000L
        const val NEARBY_TTL_MILLIS = 5L * 60L * 1_000L
    }
}
