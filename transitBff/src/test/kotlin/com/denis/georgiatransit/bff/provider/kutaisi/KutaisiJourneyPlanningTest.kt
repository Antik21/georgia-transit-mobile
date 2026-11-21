package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.geo.encodePolyline
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.service.JourneyPlanService
import com.denis.georgiatransit.bff.service.TransitService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KutaisiJourneyPlanningTest {
    private val city = KnownCityIds.Kutaisi
    private val now = Instant.parse("2026-11-19T08:00:00Z")

    @Test
    fun `route variants include a transfer with absent times and retain shape and stop names`() = runBlocking<Unit> {
        val feed = PlanningFeed()
        KutaisiAzryTransitProviderAdapter(feed, Clock.fixed(now, ZoneOffset.UTC)).use { adapter ->
            val registry = ProviderRegistry(listOf(adapter))
            val plans = JourneyPlanService(IntrinsicCapabilitySnapshotSource(registry))
            val query = JourneyPlanQuery(feed.from, feed.to, now, maxTransfers = 2, transferBufferSeconds = 60, locale = "en")
            assertFailsWith<CapabilityNotAvailable> { plans.plan(city, query, allowUntimedJourneys = true) }
            adapter.startJourneyPlanning(enabled = { true }, onFailure = {})
            withTimeout(5_000) { while (!adapter.tripPlanningReady) delay(10) }
            val cities = TransitService(registry, 60, 60, 1)
            try {
                assertFalse(cities.cities().single().capabilities.tripPlanning)
                assertTrue(cities.cities(allowUntimedJourneys = true).single().capabilities.tripPlanning)
                assertFailsWith<CapabilityNotAvailable> { plans.plan(city, query) }

                val page = plans.plan(city, query, allowUntimedJourneys = true)
                assertFalse(page.realtime)
                assertEquals(now.toString(), page.observedAt)
                assertEquals(listOf(0, 1), page.items.map { it.transfers })
                val transfer = page.items.last()
                assertNull(transfer.durationSeconds)
                assertNull(transfer.departureAt)
                assertNull(transfer.arrivalAt)
                assertTrue(transfer.legs.isEmpty())
                assertEquals(listOf(JourneySegmentMode.TRANSIT, JourneySegmentMode.WALK, JourneySegmentMode.TRANSIT),
                    transfer.segments.map { it.mode })
                val (firstRide, walk, nextRide) = transfer.segments
                assertEquals("Transfer west", firstRide.toStopName?.en)
                assertEquals("Transfer east", nextRide.fromStopName?.en)
                assertEquals(firstRide.toStopId, walk.fromStopId)
                assertEquals(nextRide.fromStopId, walk.toStopId)
                assertTrue(assertNotNull(walk.distanceMeters) > 0.0)
                assertFalse(assertNotNull(firstRide.geometryApproximate))
                assertTrue(transfer.segments.all { it.arrivalAt == null && it.waitSeconds == null && it.waitBasis == null })
                assertEquals(0, plans.plan(city, query.copy(maxTransfers = 0), true).items.single().transfers)
                assertTrue(plans.plan(city, query.copy(from = feed.to, to = feed.from), true).items.isEmpty())
                val shortWalk = plans.plan(city, query.copy(to = GeoPoint(feed.from.latitude, feed.from.longitude + 0.0005)), true)
                    .items.single()
                assertTrue(assertNotNull(shortWalk.durationSeconds) > 0)
                assertNotNull(shortWalk.arrivalAt)
            } finally {
                cities.close()
            }
        }
    }

    private class PlanningFeed : AzryFeed {
        val from = GeoPoint(42.270, 42.680)
        val to = GeoPoint(42.270, 42.740)
        private val west = GeoPoint(42.270, 42.708)
        private val east = GeoPoint(42.270, 42.711)
        private val routes = listOf("west", "east", "direct").map { id ->
            AzryRoute(id, id, mode = "BUS", patterns = listOf(AzryPattern("out", AzryStopName("A"), AzryStopName("B"))))
        }
        private val stopsByRoute = mapOf(
            "west" to listOf(stop("a", "Start", from), stop("west", "Transfer west", west)),
            "east" to listOf(stop("east", "Transfer east", east), stop("b", "Destination", to)),
            "direct" to listOf(stop("a", "Start", from), stop("b", "Destination", to)),
        )

        override suspend fun routes(locale: String) = routes
        override suspend fun route(id: String, locale: String) = routes.single { it.id == id }
        override suspend fun directionStops(id: String, pattern: String, locale: String) = stopsByRoute.getValue(id)
        override suspend fun polylines(id: String, patterns: List<String>): Map<String, AzryPolyline> {
            val points = stopsByRoute.getValue(id).map { GeoPoint(it.lat, it.lon) }
            return mapOf("out" to AzryPolyline(encodePolyline(points)))
        }
        override suspend fun stops(locale: String): List<AzryStop> = error("planning uses the ordered directions")
        override suspend fun schedule(id: String, pattern: String, locale: String): List<AzrySchedule> = error("no trip times")
        override suspend fun positions(id: String, patterns: List<String>): Map<String, List<AzryPosition>> = error("no live timings")
        override suspend fun arrivals(stopId: String, locale: String, limit: Int): List<AzryArrival> = error("no live timings")
        override suspend fun stopRoutes(stopId: String, locale: String): List<AzryRoute> = error("unused")
        override fun close() = Unit

        private fun stop(id: String, name: String, point: GeoPoint) = AzryStop(id, name = name, lat = point.latitude,
            lon = point.longitude, vehicleMode = "BUS")
    }
}
