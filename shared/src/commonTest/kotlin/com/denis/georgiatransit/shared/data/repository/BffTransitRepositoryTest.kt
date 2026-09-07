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
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitCity
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BffTransitRepositoryTest {
    @Test
    fun freshOneHourRouteCacheAvoidsNetworkAndExpiryUsesEtag304ToRefreshValidationTime() = runTest {
        val store = MemoryStore()
        val clock = MutableClock(1_000_000L)
        val requests = mutableListOf<HttpRequestData>()
        var response = 0
        val repository = repository(store, clock) { request ->
            requests += request
            response += 1
            if (response == 1) jsonResponse(routesJson(), eTag = "v1") else jsonResponse("", HttpStatusCode.NotModified, eTag = "v1")
        }
        val request = RouteListRequest(CityId("demo"))

        assertEquals(TransitFreshness.Network, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        clock.now += HOUR_MILLIS
        assertEquals(TransitFreshness.CacheValid, assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request)).freshness)
        assertEquals(1, requests.size)

        clock.now += 1L
        val revalidated = assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request))
        assertEquals(TransitFreshness.NetworkValidated, revalidated.freshness)
        assertEquals(clock.now, revalidated.validatedAtEpochMillis)
        assertEquals("v1", requests.last().headers[HttpHeaders.IfNoneMatch])
        assertEquals(2, requests.size)
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
    fun expiredCatalogUsesStaleLastKnownGoodOnlyForTransientFailureAndKeepsFailureVisible() = runTest {
        val clock = MutableClock(1_000_000L)
        var online = true
        val repository = repository(MemoryStore(), clock) {
            if (online) jsonResponse(routesJson()) else throw IOException("offline")
        }
        val request = RouteListRequest(CityId("demo"))
        repository.refreshRoutes(request)
        clock.now += HOUR_MILLIS + 1L
        online = false

        val stale = assertIs<TransitLoadResult.Data<*>>(repository.refreshRoutes(request))

        assertEquals(TransitFreshness.StaleOffline, stale.freshness)
        assertIs<TransitFailure.Transport>(stale.revalidationFailure)
        assertEquals("demo:fixture:route:blue", repository.routes(CityId("demo")).single().id.value)
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
        clock.now += HOUR_MILLIS + 1L
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
        val repository = repository(store, clock) { jsonResponse(routesJson()) }

        repeat(13) { index -> repository.refreshRoutes(RouteListRequest(CityId("city$index"))) }
        assertEquals(12, store.keys("transit-bff-v1.routes.").size)

        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.Russian, TransitMode.Bus))
        repository.refreshRoutes(RouteListRequest(CityId("demo"), TransitLocale.English, TransitMode.Metro))
        assertTrue(store.keys("transit-bff-v1.routes.").any { it.endsWith("demo.Russian.Bus") })
        assertTrue(store.keys("transit-bff-v1.routes.").any { it.endsWith("demo.English.Metro") })
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

    private fun routesJson() =
        """[{"id":"demo:fixture:route:blue","providerId":"blue","shortName":"D1","longName":{"ru":"Р","en":"Route","ka":"მ"},"color":"#0057B8","textColor":"#FFFFFF","mode":"bus","directions":[]}]"""

    private fun citiesJson(routes: Boolean) =
        """[{"id":"demo","name":{"ru":"Демо","en":"Demo","ka":"დემო"},"center":{"latitude":41.7,"longitude":44.8},"defaultZoom":13.0,"capabilities":{"routes":$routes,"stops":true,"routeGeometry":true,"vehiclePositions":true,"officialArrivals":true,"tripPlanning":true},"availability":{"readiness":"DEVELOPMENT_FIXTURE","source":"FIXTURE"}}]"""

    private fun errorJson(code: String) =
        """{"error":{"code":"$code","message":"contract message","requestId":"req-123"}}"""

    private fun cachedCity() = TransitCity(
        id = CityId("demo"),
        name = "Demo",
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(true, true, true, true, true),
    )

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
        const val HOUR_MILLIS = 60L * 60L * 1_000L
    }
}
