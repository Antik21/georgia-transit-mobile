package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.geo.decodePolyline
import com.denis.georgiatransit.bff.geo.encodePolyline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransitJourneyPlannerTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val stops = List(6) { TransitStop("test:p:stop:s$it", GeoPoint(41.64, 41.60 + it * 0.004)) }
    private val graph = TransitNetworkGraph(
        stops = stops,
        patterns = listOf(
            TransitPattern(
                routeId = "test:p:route:r1",
                directionId = "test:p:direction:d1",
                stops = IntArray(6) { it },
                segmentGeometry = List(5) { listOf(stops[it].position, stops[it + 1].position) },
                segmentApproximate = BooleanArray(5),
                segmentMeters = DoubleArray(5) { 333.0 },
            ),
        ),
    )
    private val offsets = DoubleArray(6) { it * 100.0 }

    private fun timetable(boardOffsetSeconds: Long, horizonSeconds: Long = 2_700) = NetworkTimetable(
        observedAt = now,
        liveHorizonEndEpochSecond = now.epochSecond + horizonSeconds,
        stale = false,
        services = listOf(
            PatternService(
                liveTrips = listOf(LiveTrip("test:p:vehicle:v1", LongArray(6) { now.epochSecond + boardOffsetSeconds + it * 100 })),
                headwaySeconds = 900,
                rideOffsetSeconds = offsets,
            ),
        ),
    )

    private fun query(from: Int = 0, to: Int = 5) = JourneyPlanQuery(
        from = stops[from].position,
        to = stops[to].position,
        departureAt = now,
        maxTransfers = 2,
        transferBufferSeconds = 60,
        locale = "en",
    )

    @Test
    fun `a live departure inside the 45-minute horizon is used with its own ride times`() {
        val result = TransitJourneyPlanner(graph, timetable(boardOffsetSeconds = 600), "test", "p")
            .plan(query(), PlanningBudget.unbounded())

        val ride = result.items.single { it.transitSegments().isNotEmpty() }.transitSegments().single()
        assertEquals(JourneyWaitBasis.LIVE_VEHICLE, ride.waitBasis)
        assertEquals(now.plusSeconds(600).toString(), ride.departureAt)
        assertEquals(now.plusSeconds(1_100).toString(), ride.arrivalAt)
    }

    @Test
    fun `a live departure beyond the 45-minute horizon falls back to the headway estimate`() {
        val result = TransitJourneyPlanner(graph, timetable(boardOffsetSeconds = 2_760), "test", "p")
            .plan(query(), PlanningBudget.unbounded())

        val ride = result.items.single { it.transitSegments().isNotEmpty() }.transitSegments().single()
        assertEquals(JourneyWaitBasis.HEADWAY_ESTIMATE, ride.waitBasis)
        assertEquals(900L, ride.waitSeconds)
        assertEquals(now.plusSeconds(900 + 500).toString(), ride.arrivalAt)
    }

    @Test
    fun `an exhausted planning budget stops the search`() {
        val expired = PlanningBudget(System.nanoTime() - 1)
        assertFailsWith<PlanningBudgetExceeded> {
            TransitJourneyPlanner(graph, timetable(boardOffsetSeconds = 600), "test", "p").plan(query(), expired)
        }
    }

    @Test
    fun `cancellation is rethrown from the planning budget`() {
        val cancelled = PlanningBudget(System.nanoTime() + 1_000_000_000L) { true }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            TransitJourneyPlanner(graph, timetable(boardOffsetSeconds = 600), "test", "p").plan(query(), cancelled)
        }
    }

    @Test
    fun `live trips must be contiguous and non-decreasing`() {
        assertFailsWith<IllegalArgumentException> { LiveTrip("v", longArrayOf(10, NotServed, 30)) }
        assertFailsWith<IllegalArgumentException> { LiveTrip("v", longArrayOf(30, 20)) }
        assertEquals(1, LiveTrip("v", longArrayOf(NotServed, 20, 30)).firstPosition)
    }

    @Test
    fun `encoded polylines round-trip at precision 5`() {
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(points))
        assertEquals(points, decodePolyline(encodePolyline(points)))
    }

    @Test
    fun `walking neighbours are limited to 600 m straight-line`() {
        val neighbours = graph.walkNeighbours[0].map { graph.stops[it].id }
        assertEquals(listOf("test:p:stop:s1"), neighbours)
        assertTrue(graph.walkNeighbourMeters[0].single() in 400.0..450.0, "333 m × 1.3 walking factor")
    }

    private fun com.denis.georgiatransit.bff.api.Journey.transitSegments() =
        segments.filter { it.mode == com.denis.georgiatransit.bff.api.JourneySegmentMode.TRANSIT }
}
