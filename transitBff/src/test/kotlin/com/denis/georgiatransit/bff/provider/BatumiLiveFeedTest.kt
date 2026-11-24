package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.config.BatumiThetaActivationConfig
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.batumi.FixtureRoute
import com.denis.georgiatransit.bff.provider.batumi.catalogJson
import com.denis.georgiatransit.bff.provider.batumi.eastLine
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BatumiLiveFeedTest {
    @Test
    fun `background observations orient slow buses and hold the bearing through a stop`() = runBlocking {
        val fixture = LiveSources()
        suspend fun sample(longitude: Double, seconds: Long = 5): Double? {
            fixture.clock.advance(seconds)
            fixture.longitude = longitude
            return fixture.feed.load(fixture.catalog).vehiclesByRoute.values.flatten().single().vehicle.bearing
        }
        assertNull(sample(41.60000))
        assertNull(sample(41.60005))
        val east = assertNotNull(sample(41.60012))
        assertEquals(90.0, east, 0.01)
        repeat(15) { assertEquals(east, sample(41.60010)) }
        assertEquals(270.0, assertNotNull(sample(41.59990)), 0.01)
        assertNull(sample(41.60900)) // GPS jump
        assertNull(sample(41.60880, seconds = 61)) // expired track
    }

    @Test
    fun `three minutes of consecutive failures switch every route and healthy primary returns immediately`() = runBlocking {
        val fixture = LiveSources()
        val primary = fixture.feed.load(fixture.catalog)
        assertEquals(BatumiLiveSource.BATBUS, primary.source)
        fixture.batBusAvailable = false
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        fixture.clock.advance(179)
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        assertEquals(emptySet(), fixture.thetaCalls.toSet())

        fixture.clock.advance(1)
        val fallback = fixture.feed.load(fixture.catalog)
        assertEquals(BatumiLiveSource.THETA, fallback.source)
        assertEquals(primary.vehiclesByRoute.keys, fallback.vehiclesByRoute.keys)
        assertEquals(primary.vehiclesByRoute.values.flatten().map { it.vehicle.id },
            fallback.vehiclesByRoute.values.flatten().map { it.vehicle.id })
        assertEquals(setOf("r1", "r2"), fixture.thetaCalls.toSet())

        fixture.batBusAvailable = true
        assertEquals(BatumiLiveSource.BATBUS, fixture.feed.load(fixture.catalog).source)
        fixture.batBusAvailable = false
        fixture.thetaCalls.clear()
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        assertEquals(emptySet(), fixture.thetaCalls.toSet())
    }

    @Test
    fun `a malformed route rejects the whole fallback and an empty array covers an idle route`() = runBlocking {
        val fixture = LiveSources()
        fixture.batBusAvailable = false
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        fixture.clock.advance(180)
        fixture.secondRouteResponse = """{"data":null}"""
        assertFailsWith<ProviderNormalizedSchemaFailure> { fixture.feed.load(fixture.catalog) }
        fixture.secondRouteResponse = """{"data":[]}"""
        fixture.clock.advance(10)
        val fallback = fixture.feed.load(fixture.catalog)
        assertEquals(2, fallback.vehiclesByRoute.size)
        assertEquals(1, fallback.vehiclesByRoute.values.count { it.isEmpty() })
    }

    @Test
    fun `fallback respects provider backoff while primary recovery remains immediate`() = runBlocking {
        val fixture = LiveSources()
        fixture.batBusAvailable = false
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        fixture.clock.advance(180)
        fixture.thetaRateLimited = true
        assertFailsWith<ProviderRateLimited> { fixture.feed.load(fixture.catalog) }
        fixture.thetaCalls.clear()
        fixture.clock.advance(10)
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        assertEquals(emptySet(), fixture.thetaCalls.toSet())
        fixture.batBusAvailable = true
        assertEquals(BatumiLiveSource.BATBUS, fixture.feed.load(fixture.catalog).source)
    }

    @Test
    fun `fallback keeps ten seconds between theta sweeps when BatBus failure latency changes`() = runBlocking {
        val fixture = LiveSources()
        fixture.batBusAvailable = false
        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        fixture.clock.advance(180)

        fixture.batBusFailureLatencySeconds = 8
        assertEquals(BatumiLiveSource.THETA, fixture.feed.load(fixture.catalog).source)
        fixture.thetaCalls.clear()
        fixture.batBusFailureLatencySeconds = 0
        fixture.clock.advance(2)

        assertFailsWith<ProviderUnavailable> { fixture.feed.load(fixture.catalog) }
        assertEquals(emptySet(), fixture.thetaCalls.toSet())
        fixture.clock.advance(8)
        assertEquals(BatumiLiveSource.THETA, fixture.feed.load(fixture.catalog).source)
        assertEquals(setOf("r1", "r2"), fixture.thetaCalls.toSet())

        fixture.batBusAvailable = true
        assertEquals(BatumiLiveSource.BATBUS, fixture.feed.load(fixture.catalog).source)
    }

    @Test
    fun `first client reads a bearing acquired by the city worker before any request`() = runBlocking {
        val fixture = LiveSources()
        val publishedMotion = CompletableDeferred<Unit>()
        val primary = object : BatumiAllBusesClient by fixture.primary {
            var polls = 0
            override suspend fun getAllBuses(): String {
                polls++
                fixture.clock.advance(5)
                if (polls >= 2) fixture.longitude = 41.6002
                if (polls == 3) publishedMotion.complete(Unit)
                return fixture.primary.getAllBuses()
            }
        }
        fixture.adapter(primary).use { adapter ->
            adapter.startGlobalFeed { true }
            withTimeout(2_000) { publishedMotion.await() }
            val route = fixture.catalog.routes.first { fixture.catalog.rawRouteId(it.id) == "r1" }
            val vehicle = adapter.vehicles(route.id, null).items.single()
            assertEquals(90.0, assertNotNull(vehicle.bearing), 0.01)
        }
    }

    @Test
    fun `city snapshot serves fallback positions after primary expiry and publishes recovered BatBus positions`() = runBlocking {
        val fixture = LiveSources()
        fixture.adapter().use { adapter ->
            adapter.startGlobalFeed { true }
            val routeId = fixture.catalog.routes.first { fixture.catalog.rawRouteId(it.id) == "r1" }.id
            suspend fun readAtLongitude(longitude: Double): RealtimeVehicles = withTimeout(20_000) {
                fun matches(page: RealtimeVehicles?) =
                    page?.items?.singleOrNull()?.position?.longitude == longitude
                suspend fun read() = try {
                    adapter.vehicles(routeId, null)
                } catch (_: ProviderUnavailable) {
                    null
                }
                var page = read()
                while (!matches(page)) {
                    delay(25)
                    page = read()
                }
                requireNotNull(page)
            }

            val primary = readAtLongitude(41.60)
            fixture.batBusAvailable = false
            withTimeout(2_000) { fixture.primaryFailure.await() }
            fixture.clock.advance(179)
            assertFailsWith<ProviderUnavailable> { adapter.vehicles(routeId, null) }
            assertEquals(emptySet(), fixture.thetaCalls.toSet())

            fixture.clock.advance(1)
            val fallback = readAtLongitude(41.601)
            assertFalse(fallback.stale)
            assertEquals(primary.items.single().id, fallback.items.single().id)
            fixture.batBusAvailable = true
            val recovered = readAtLongitude(41.60)
            assertFalse(recovered.stale)
            assertEquals(primary.items.single().id, recovered.items.single().id)
        }
    }
}

private class LiveSources {
    val clock = FeedTestClock()
    private val catalogPayload = catalogJson(listOf(
        FixtureRoute("r1", eastLine("a", 41.64, 3)),
        FixtureRoute("r2", eastLine("b", 41.65, 3)),
    ))
    val catalog = BatumiThetaCatalogParser.parse(catalogPayload, clock.instant())
    @Volatile var batBusAvailable = true
    var batBusFailureLatencySeconds = 0L
    var thetaRateLimited = false
    val thetaCalls = ConcurrentLinkedQueue<String>()
    val primaryFailure = CompletableDeferred<Unit>()
    @Volatile var longitude = 41.60
    private fun firstRouteBuses(longitude: Double) = """[{"Name":"example","Lat":41.64,"Lon":$longitude,"Status":1}]"""
    var secondRouteResponse = """{"data":[]}"""

    val primary = object : BatumiAllBusesClient {
        override suspend fun getAllBuses(): String {
            if (!batBusAvailable) {
                if (batBusFailureLatencySeconds > 0) clock.advance(batBusFailureLatencySeconds)
                primaryFailure.complete(Unit)
                throw ProviderUnavailable("Simulated outage")
            }
            return """{"data":{"r1":${firstRouteBuses(longitude)},"r2":[]}}"""
        }
        override fun close() = Unit
    }
    val theta = object : BatumiThetaCatalogClient {
        override suspend fun getDbData(): String = catalogPayload
        override suspend fun getBusLocsOnRoute(rawRouteId: String): String {
            thetaCalls += rawRouteId
            if (thetaRateLimited) throw ProviderRateLimited("Simulated rate limit", 30)
            val buses = firstRouteBuses(41.601)
            return if (rawRouteId == "r1") """{"data":$buses}""" else secondRouteResponse
        }
        override fun close() = Unit
    }
    fun adapter(bulkClient: BatumiAllBusesClient = primary): BatumiThetaTransitProviderAdapter {
        val activation = BffConfig.fromEnvironment(mapOf(
            "BATUMI_THETA_ENABLED" to "true",
            "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to BatumiThetaActivationConfig.Acknowledgement,
        )).batumiTheta
        return BatumiThetaTransitProviderAdapter(
            activation = activation,
            client = theta,
            clock = clock,
            allBusesClient = bulkClient,
            cityFeedPollInterval = Duration.ofMillis(25),
        )
    }

    val feed = BatumiLiveFeed(primary, theta, clock, isEnabled = { true })
}

private class FeedTestClock : Clock() {
    @Volatile private var now = Instant.parse("2026-11-19T08:00:00Z")
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
}
