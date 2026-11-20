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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BatumiLiveFeedTest {
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
    fun `city snapshot serves fallback positions after primary expiry and publishes recovered BatBus positions`() = runBlocking {
        val fixture = LiveSources()
        val activation = BffConfig.fromEnvironment(mapOf(
            "BATUMI_THETA_ENABLED" to "true",
            "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to BatumiThetaActivationConfig.Acknowledgement,
        )).batumiTheta
        BatumiThetaTransitProviderAdapter(
            activation = activation,
            client = fixture.theta,
            clock = fixture.clock,
            allBusesClient = fixture.primary,
            cityFeedPollInterval = Duration.ofMillis(25),
        ).use { adapter ->
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
    private val firstRouteBuses = """[{"Name":"example","Lat":41.64,"Lon":41.60,"Status":1}]"""
    var secondRouteResponse = """{"data":[]}"""

    val primary = object : BatumiAllBusesClient {
        override suspend fun getAllBuses(): String {
            if (!batBusAvailable) {
                if (batBusFailureLatencySeconds > 0) clock.advance(batBusFailureLatencySeconds)
                primaryFailure.complete(Unit)
                throw ProviderUnavailable("Simulated outage")
            }
            return """{"data":{"r1":$firstRouteBuses,"r2":[]}}"""
        }
        override fun close() = Unit
    }
    val theta = object : BatumiThetaCatalogClient {
        override suspend fun getDbData(): String = catalogPayload
        override suspend fun getBusLocsOnRoute(rawRouteId: String): String {
            thetaCalls += rawRouteId
            if (thetaRateLimited) throw ProviderRateLimited("Simulated rate limit", 30)
            val buses = firstRouteBuses.replace("41.60", "41.601")
            return if (rawRouteId == "r1") """{"data":$buses}""" else secondRouteResponse
        }
        override fun close() = Unit
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
