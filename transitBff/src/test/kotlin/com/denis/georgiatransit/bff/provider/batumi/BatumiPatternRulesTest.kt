package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneySegmentMode.WALK
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatumiPatternRulesTest {
    private val outbound = eastLine("T", latitude = 41.640, count = 5, status = 1)
    private val inbound = eastLine("T", latitude = 41.640, count = 5, startLon = 41.620, status = 2)
        .mapIndexed { index, stop -> stop.copy(rawId = "T${index + 5}") }

    @Test
    fun `a ride never crosses a Status boundary of a non-circle route`() {
        val fixture = PlanningFixture(listOf(FixtureRoute("9", outbound + inbound)))
        fixture.vehicle("9", "t1", outbound[0])
        val statusOne = outbound.map { fixture.stopId(it.rawId) }.toSet()
        val statusTwo = inbound.map { fixture.stopId(it.rawId) }.toSet()

        val page = fixture.plan(near(outbound[1]), near(inbound[3]))

        assertTrue(page.items.isNotEmpty())
        val crossing = page.items.flatMap { it.transitSegments }
            .filter { it.fromStopId in statusOne && it.toStopId in statusTwo }
        assertTrue(crossing.isEmpty(), "no ride may continue through the terminal")
        assertTrue(page.items.none { it.transfers == 0 && it.transitSegments.isNotEmpty() })
    }

    @Test
    fun `a RouteIsCircle route lets one ride wrap through the terminal`() {
        val fixture = PlanningFixture(listOf(FixtureRoute("9", outbound + inbound, circle = true)))
        assertTrue(fixture.routeId("9") in fixture.catalog.circleRouteIds)
        fixture.vehicle("9", "t1", outbound[0])

        val fastest = fixture.plan(near(outbound[2]), near(inbound[2])).fastest()

        val ride = fastest.transitSegments.single()
        assertEquals(fixture.stopId("T2"), ride.fromStopId)
        assertEquals(fixture.stopId("T7"), ride.toStopId)
        assertEquals(4, ride.intermediateStopCount)
    }

    @Test
    fun `a circle ride may wrap past the first stop`() {
        val square = listOf(
            FixtureStop("Q0", 41.640, 41.600), FixtureStop("Q1", 41.640, 41.610), FixtureStop("Q2", 41.640, 41.620),
            FixtureStop("Q3", 41.650, 41.620), FixtureStop("Q4", 41.660, 41.620), FixtureStop("Q5", 41.660, 41.610),
            FixtureStop("Q6", 41.660, 41.600), FixtureStop("Q7", 41.650, 41.600),
        )
        val fixture = PlanningFixture(listOf(FixtureRoute("8", square, circle = true)))
        fixture.vehicle("8", "q1", square[5])

        val ride = fixture.plan(near(square[6]), near(square[2])).fastest().transitSegments.single()

        assertEquals(fixture.stopId("Q6"), ride.fromStopId)
        assertEquals(fixture.stopId("Q2"), ride.toStopId)
        assertEquals(3, ride.intermediateStopCount)
        assertEquals(false, ride.geometryApproximate, "the closing slice of a closed shape is exact")
    }

    @Test
    fun `a pattern without active vehicles is not offered`() {
        val line = eastLine("A", latitude = 41.640, count = 10)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line)))

        val page = fixture.plan(near(line[2]), near(line[8]))

        assertTrue(page.items.isEmpty(), "no transit and the walk is longer than 2 km")
        assertFalse(page.realtime)
    }

    @Test
    fun `headway estimate is used when no live bus serves the requested departure`() {
        val line = eastLine("A", latitude = 41.640, count = 10)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line)))
        fixture.vehicle("1", "v1", line[0])
        val departure = PlanNow.plusSeconds(3_600)

        val journey = fixture.plan(near(line[2]), near(line[8]), departureAt = departure).items.single()

        val ride = journey.transitSegments.single()
        val cycle = fixture.catalog.cycle(fixture.routeId("1"))
        val headway = ceil(cycle.totalMeters / FallbackMetersPerSecond).toLong()
        assertEquals(JourneyWaitBasis.HEADWAY_ESTIMATE, ride.waitBasis)
        assertEquals(headway, ride.waitSeconds)
        assertNull(ride.vehicleId)
        assertEquals(JourneyTimingQuality.ESTIMATED, journey.timingQuality)
    }

    @Test
    fun `a stale live snapshot keeps planning on headway estimates only`() {
        val line = eastLine("A", latitude = 41.640, count = 10)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line)))
        fixture.vehicle("1", "v1", line[0])

        val page = fixture.plan(near(line[2]), near(line[8]), liveObservedAt = PlanNow.minusSeconds(30))

        assertTrue(page.stale)
        assertFalse(page.realtime)
        assertEquals(JourneyWaitBasis.HEADWAY_ESTIMATE, page.items.single().transitSegments.single().waitBasis)
    }

    @Test
    fun `points closer than 150 m return only the walk-only option`() {
        val line = eastLine("A", latitude = 41.640, count = 10)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line)))
        fixture.vehicle("1", "v1", line[0])

        val page = fixture.plan(near(line[2]), near(line[2], northMeters = 120.0))

        val walk = page.items.single()
        assertEquals(listOf(WALK), walk.modes)
        assertEquals(0, walk.transfers)
        assertTrue(walk.legs.isEmpty())
        assertNull(walk.labels)
        assertEquals(JourneyTimingQuality.ESTIMATED, walk.timingQuality)
        assertEquals(true, walk.segments.single().geometryApproximate)
        assertTrue(walk.id.startsWith("batumi:theta:journey:walk-"))
        assertFalse(page.realtime)
    }

    @Test
    fun `planning fails closed when the live snapshot is too old or the graph is absent`() {
        val line = eastLine("A", latitude = 41.640, count = 3)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line)))

        assertTrue(fixture.planning.isReady(PlanNow, PlanNow.minusSeconds(60)))
        assertFalse(fixture.planning.isReady(PlanNow, PlanNow.minusSeconds(61)))
        assertFalse(fixture.planning.isReady(PlanNow, null))
        assertFalse(fixture.planning.isReady(PlanNow.plusSeconds(7 * 24 * 3_600L), PlanNow.plusSeconds(7 * 24 * 3_600L)))
        assertFailsWith<ProviderUnavailable> {
            fixture.plan(near(line[0]), near(line[2]), liveObservedAt = PlanNow.minusSeconds(120))
        }
    }

    @Test
    fun `stops away from the route shape fall back to an approximate stop-chain slice`() {
        val line = eastLine("A", latitude = 41.640, count = 4)
        val shape = line.map(FixtureStop::position).toMutableList().also { it[2] = GeoPoint(41.650, line[2].longitude) }
        val fixture = PlanningFixture(listOf(FixtureRoute("1", line, shape = shape)))

        val pattern = BatumiNetworkGraphBuilder.build(fixture.catalog, PlanNow).graph.patterns.single()

        assertFalse(pattern.segmentApproximate[0])
        assertTrue(pattern.segmentApproximate[1])
        assertTrue(pattern.segmentApproximate[2])
    }
}
