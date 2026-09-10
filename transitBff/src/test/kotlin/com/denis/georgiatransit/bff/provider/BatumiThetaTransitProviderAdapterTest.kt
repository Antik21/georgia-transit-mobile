package com.denis.georgiatransit.bff.provider

import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.service.TransitService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatumiThetaTransitProviderAdapterTest {
    @Test
    fun `parser accepts data wrapper numeric strings and produces normalized catalog`() {
        val catalog = BatumiThetaCatalogParser.parse(fixture(wrapped = true), Instant.parse("2026-09-10T00:00:00Z"))
        assertEquals(1, catalog.routes.size)
        assertEquals("12", catalog.routes.single().shortName)
        assertEquals(2, catalog.stops.size)
        assertEquals(2, catalog.stopsFor(catalog.routes.single().id).size)
        assertTrue(catalog.shapes.getValue(catalog.routes.single().id).value.isNotBlank())
        assertTrue(catalog.routes.single().id.startsWith("batumi:theta:route:"))
    }

    @Test
    fun `parser accepts root legacy aliases and rejects broken cross references`() {
        assertEquals(1, BatumiThetaCatalogParser.parse(fixture(wrapped = false), Instant.EPOCH).routes.size)
        assertFailsWith<ProviderNormalizedSchemaFailure> {
            BatumiThetaCatalogParser.parse(fixture(wrapped = true).replace("\"r1\":{\"Order\":2}", "\"missing\":{\"Order\":2}"), Instant.EPOCH)
        }
        assertFailsWith<ProviderNormalizedSchemaFailure> {
            BatumiThetaCatalogParser.parse(fixture(wrapped = true).replace("41.64", "51.64"), Instant.EPOCH)
        }
    }

    @Test
    fun `parser accepts current keyed Theta objects and coordinate aliases`() {
        val payload = """{"data":{"routesNames":{"r1":{"RouteIdGeoGps":"r1","RouteNameEN":"12","RouteNameKA":"12","RouteSortOrder":1}},"busStops":{"s1":{"BusStopIdGeoGps":"s1","BusStopNumber":"A","BusStopNameEN":"One","BusStopNameKA":"ერთი","BusStopLatitude":41.64,"BusStopLongitude":41.65,"routes":{"r1":{"Status":1,"Order":1}}},"s2":{"BusStopIdGeoGps":"s2","BusStopNumber":"B","BusStopNameEN":"Two","BusStopNameKA":"ორი","BusStopLatitude":41.641,"BusStopLongitude":41.651,"routes":{"r1":{"Status":1,"Order":2}}}},"routeCoordinatesGrouped":{"r1":[{"lat":41.64,"lon":41.65},{"lat":41.641,"lon":41.651}]}}}"""
        val catalog = BatumiThetaCatalogParser.parse(payload, Instant.EPOCH)
        assertEquals("12", catalog.routes.single().shortName)
        assertEquals("One", catalog.stopsFor(catalog.routes.single().id).first().name.en)
    }

    @Test
    fun `keyed collection keys are safe identifier fallbacks`() {
        val payload = """{"data":{"routesNames":{"r1":{"RouteNameEN":"12","RouteNameKA":"12"}},"busStops":{"s1":{"BusStopNumber":"A","BusStopNameEN":"One","BusStopNameKA":"ერთი","BusStopLatitude":41.64,"BusStopLongitude":41.65,"routes":{"r1":{"Order":1}}},"s2":{"BusStopNumber":"B","BusStopNameEN":"Two","BusStopNameKA":"ორი","BusStopLatitude":41.641,"BusStopLongitude":41.651,"routes":{"r1":{"Order":2}}}},"routeCoordinatesGrouped":{"r1":[{"lat":41.64,"lon":41.65},{"lat":41.641,"lon":41.651}]}}}"""
        val catalog = BatumiThetaCatalogParser.parse(payload, Instant.EPOCH)
        assertEquals(1, catalog.routes.size)
        assertEquals(2, catalog.stops.size)
    }

    @Test
    fun `adapter reuses one validated catalog snapshot within refresh TTL`() = runBlocking {
        val client = FakeCatalogClient(fixture(true))
        BatumiThetaTransitProviderAdapter(
            activation = BffConfig.fromEnvironment(
                mapOf(
                    "BATUMI_THETA_ENABLED" to "true",
                    "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to "I_UNDERSTAND_THETA_DEV_ONLY",
                ),
            ).batumiTheta,
            client = client,
        ).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            adapter.stopDirectory("en")
            adapter.shape(route.id, route.directions.single().id)
        }
        assertEquals(1, client.calls)
    }

    @Test
    fun `concurrent failed refresh shares one generation then serves LKG during cooldown`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
        val client = FakeCatalogClient(fixture(true)).apply { failAfterFirst = true }
        val adapter = BatumiThetaTransitProviderAdapter(activation(), client, clock)
        adapter.routes("en", "bus") // initial valid snapshot
        clock.advanceSeconds(601)
        coroutineScope { List(8) { async { adapter.routes("en", "bus") } }.awaitAll() }
        adapter.stopDirectory("en")
        assertEquals(2, client.calls, "one initial fetch plus one failed refresh generation")
        adapter.close()
    }

    @Test
    fun `live parser accepts root data object numeric values and null empty`() {
        val route = BatumiThetaCatalogParser.parse(fixture(true), Instant.EPOCH).routes.single()
        val item = """{"Name":"internal-1","Lat":"41.6400","Lon":41.6500,"Status":1}"""
        assertEquals(1, BatumiThetaLiveParser.parse("{\"data\":[$item]}", route, Instant.EPOCH).size)
        assertEquals(1, BatumiThetaLiveParser.parse("{\"data\":$item}", route, Instant.EPOCH).size)
        assertEquals(0, BatumiThetaLiveParser.parse("{\"data\":null}", route, Instant.EPOCH).size)
        assertFailsWith<ProviderNormalizedSchemaFailure> {
            BatumiThetaLiveParser.parse("{\"data\":[{\"Name\":\"x\",\"Lat\":51,\"Lon\":41.65}]}", route, Instant.EPOCH)
        }
    }

    @Test
    fun `live route is catalog prevalidated cached and stale LKG is marked`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
        val client = FakeCatalogClient(fixture(true)).apply {
            livePayload = """{"data":[{"Name":"internal-1","Lat":41.6400,"Lon":41.6500,"Status":"x"}]}"""
        }
        BatumiThetaTransitProviderAdapter(activation(), client, clock).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            val fresh = adapter.vehicles(route.id, null)
            assertEquals(1, fresh.items.size)
            assertEquals(60, fresh.maxAgeSeconds, "a marker must remain valid between eight-second client polls")
            assertEquals(1, adapter.vehicles(route.id, null).items.size)
            assertEquals(1, client.liveCalls)
            clock.advanceSeconds(5); client.liveFailure = true
            val stale = adapter.vehicles(route.id, null)
            assertTrue(stale.stale)
            assertEquals(60, stale.maxAgeSeconds)
            clock.advanceSeconds(61)
            assertFailsWith<ProviderUnavailable> { adapter.vehicles(route.id, null) }
        }
        Unit
    }

    @Test
    fun `live observation time is the BFF receipt instant`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
        val client = object : BatumiThetaCatalogClient {
            override suspend fun getDbData(): String = fixture(true)
            override suspend fun getBusLocsOnRoute(rawRouteId: String): String {
                clock.advanceSeconds(3)
                return """{"data":[{"Name":"private","Lat":41.64,"Lon":41.65}]}"""
            }
            override fun close() = Unit
        }
        BatumiThetaTransitProviderAdapter(activation(), client, clock).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            val page = adapter.vehicles(route.id, null)
            assertEquals(Instant.parse("2026-09-10T00:00:03Z"), page.observedAt)
            assertEquals("2026-09-10T00:00:03Z", page.items.single().observedAt)
        }
    }

    @Test
    fun `BFF service exposes only normalized Batumi live vehicle capability`() = runBlocking {
        val client = FakeCatalogClient(fixture(true)).apply {
            livePayload = """{"data":[{"Name":"internal-only","Lat":41.64,"Lon":41.65}]}"""
        }
        BatumiThetaTransitProviderAdapter(activation(), client).use { adapter ->
            TransitService(ProviderRegistry(listOf(adapter)), 60, 60, 15).use { service ->
                val route = service.routes("batumi", "en", "bus").single()
                val page = service.vehicles("batumi", route.id, null)
                assertTrue(adapter.city.capabilities.vehiclePositions)
                assertEquals(1, page.items.size)
                assertTrue(!page.items.single().id.contains("internal-only"))
            }
        }
    }

    @Test
    fun `concurrent live callers coalesce one per route fetch`() = runBlocking {
        val client = FakeCatalogClient(fixture(true)).apply {
            liveDelayMillis = 20
            livePayload = """{"data":null}"""
        }
        BatumiThetaTransitProviderAdapter(activation(), client).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            coroutineScope { List(8) { async { adapter.vehicles(route.id, null) } }.awaitAll() }
            assertEquals(1, client.liveCalls)
        }
    }

    @Test
    fun `arrival estimate requires consistent fresh movement and never presents vehicle Name`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
        val client = FakeCatalogClient(fixture(true))
        BatumiThetaTransitProviderAdapter(activation(), client, clock).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            val stop = adapter.stopDirectory("en").single { it.code == "B" }
            fun live(lat: Double, lon: Double) { client.livePayload = """{"data":[{"Name":"not-a-fleet-number","Lat":$lat,"Lon":$lon,"Status":1}]}""" }
            live(41.6400, 41.6500); adapter.vehicles(route.id, null)
            clock.advanceSeconds(5); live(41.6403, 41.6503); adapter.vehicles(route.id, null)
            clock.advanceSeconds(5); live(41.6406, 41.6506); adapter.vehicles(route.id, null)
            val arrivals = adapter.arrivals(stop.id, 5, "en")
            assertEquals(ArrivalSource.CLIENT_ESTIMATE, arrivals.source)
            assertEquals(1, arrivals.items.size)
            assertEquals(route.id, arrivals.items.single().routeId)
            assertTrue(arrivals.items.single().expectedInMinutes != null)
            assertEquals(null, arrivals.items.single().tripId)
        }
    }

    @Test
    fun `ETA is withheld for passed stop zero movement and stale snapshot`() = runBlocking {
        suspend fun arrive(
            points: List<Pair<Double, Double>>,
            targetCode: String,
            stale: Boolean = false,
        ): com.denis.georgiatransit.bff.provider.RealtimeArrivals {
            val client = FakeCatalogClient(fixture(true))
            val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
            return BatumiThetaTransitProviderAdapter(activation(), client, clock).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            points.forEach { (lat, lon) ->
                client.livePayload = """{"data":[{"Name":"private","Lat":$lat,"Lon":$lon}]}"""
                adapter.vehicles(route.id, null)
                clock.advanceSeconds(5)
            }
            if (stale) client.liveFailure = true
            adapter.arrivals(adapter.stopDirectory("en").single { it.code == targetCode }.id, 5, "en")
            }
        }
        // A non-loop route cannot wrap past its terminal to a previous stop.
        assertEquals(0, arrive(listOf(41.6402 to 41.6502, 41.6405 to 41.6505, 41.6408 to 41.6508), "A").items.size)
        assertEquals(0, arrive(List(3) { 41.6402 to 41.6502 }, "B").items.size)
        assertTrue(arrive(listOf(41.6400 to 41.6500, 41.6403 to 41.6503, 41.6406 to 41.6506), "B", stale = true).stale)
        Unit
    }

    @Test
    fun `loop ETA wraps once only after direction is established`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-10T00:00:00Z"))
        val client = FakeCatalogClient(loopFixture())
        BatumiThetaTransitProviderAdapter(activation(), client, clock).use { adapter ->
            val route = adapter.routes("en", "bus").single()
            listOf(41.6400 to 41.6501, 41.6400 to 41.6505, 41.6401 to 41.6510).forEachIndexed { index, (lat, lon) ->
                client.livePayload = """{"data":[{"Name":"private","Lat":$lat,"Lon":$lon}]}"""
                adapter.vehicles(route.id, null)
                if (index < 2) clock.advanceSeconds(5)
            }
            assertEquals(1, adapter.arrivals(adapter.stopDirectory("en").single { it.code == "A" }.id, 5, "en").items.size)
        }
    }

    private fun fixture(wrapped: Boolean): String {
        val json = """{"routesNames":[{"RouteIdGeoGps":"r1","RouteNameGeoGps":"12","RouteNameGeoGpsKA":"12","RouteSortOrder":"1"}],"busStops":[{"BusStopIdGeoGps":"s1","BusStopNumber":"A","BusStopNameGeoGps":"One","Lat":"41.64","Lon":41.65,"routes":{"r1":{"Order":"1"}}},{"BusStopIdGeoGps":"s2","BusStopNumber":"B","BusStopNameGeoGps":"Two","Lat":41.641,"Lon":"41.651","routes":{"r1":{"Order":2}}}],"routeCoordinatesGrouped":{"r1":[{"lat":41.64,"lon":41.65},{"lat":"41.641","lon":"41.651"}]}}"""
        return if (wrapped) "{\"data\":$json}" else json
    }

    private fun loopFixture(): String = """{"data":{"routesNames":[{"RouteIdGeoGps":"loop","RouteNameGeoGps":"L","RouteNameGeoGpsKA":"L","RouteSortOrder":1}],"busStops":[{"BusStopIdGeoGps":"a","BusStopNumber":"A","BusStopNameGeoGps":"Start","Lat":41.64,"Lon":41.65,"routes":{"loop":{"Order":1}}},{"BusStopIdGeoGps":"b","BusStopNumber":"B","BusStopNameGeoGps":"Corner","Lat":41.64,"Lon":41.651,"routes":{"loop":{"Order":2}}}],"routeCoordinatesGrouped":{"loop":[{"lat":41.64,"lon":41.65},{"lat":41.64,"lon":41.651},{"lat":41.641,"lon":41.651},{"lat":41.64,"lon":41.65}]}}}"""

    private class FakeCatalogClient(private val payload: String) : BatumiThetaCatalogClient {
        var calls = 0
        var failAfterFirst = false
        var liveCalls = 0
        var liveFailure = false
        var liveDelayMillis = 0L
        var livePayload = "{\"data\":null}"
        override suspend fun getDbData(): String {
            calls += 1
            if (failAfterFirst && calls > 1) { delay(20); throw ProviderUnavailable("test") }
            return payload
        }
        override suspend fun getBusLocsOnRoute(rawRouteId: String): String {
            liveCalls += 1
            if (liveDelayMillis > 0) delay(liveDelayMillis)
            if (liveFailure) throw ProviderUnavailable("test")
            return livePayload
        }
        override fun close() = Unit
    }

    private fun activation() = BffConfig.fromEnvironment(
        mapOf("BATUMI_THETA_ENABLED" to "true", "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to "I_UNDERSTAND_THETA_DEV_ONLY"),
    ).batumiTheta

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
    }
}
