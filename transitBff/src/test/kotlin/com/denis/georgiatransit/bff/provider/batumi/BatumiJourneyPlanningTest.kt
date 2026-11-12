package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.JourneyLabel
import com.denis.georgiatransit.bff.api.JourneySegmentMode.TRANSIT
import com.denis.georgiatransit.bff.api.JourneySegmentMode.WALK
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.geo.decodePolyline
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BatumiJourneyPlanningTest {
    private val lineA = eastLine("A", latitude = 41.640, count = 10)

    @Test
    fun `direct trip boards the first catchable live bus and maps a continuous itinerary`() {
        val fixture = PlanningFixture(listOf(FixtureRoute("1", lineA)))
        fixture.vehicle("1", "v1", lineA[0])

        val page = fixture.plan(near(lineA[2]), near(lineA[8]))

        assertEquals(ArrivalSource.CLIENT_ESTIMATE, page.source)
        assertTrue(page.realtime)
        val journey = page.items.single()
        assertEquals(listOf(WALK, TRANSIT, WALK), journey.modes)
        assertEquals(0, journey.transfers)
        assertEquals(JourneyTimingQuality.LIVE, journey.timingQuality)
        val ride = journey.transitSegments.single()
        assertEquals(fixture.stopId("A2"), ride.fromStopId)
        assertEquals(fixture.stopId("A8"), ride.toStopId)
        assertEquals(5, ride.intermediateStopCount)
        assertEquals(JourneyWaitBasis.LIVE_VEHICLE, ride.waitBasis)
        assertEquals("batumi:theta:vehicle:v1", ride.vehicleId)
        assertEquals(false, ride.geometryApproximate)
        assertTrue(decodePolyline(assertNotNull(ride.geometry)).size >= 2)

        val chain = fixture.catalog.cycle(fixture.routeId("1")).chain
        val boardAt = PlanNow.epochSecond + (chain[2].cumulativeMeters / FallbackMetersPerSecond + 60).roundToLong()
        val alightAt = PlanNow.epochSecond + (chain[8].cumulativeMeters / FallbackMetersPerSecond + 7 * 60).roundToLong()
        assertEquals(boardAt, ride.departureEpoch())
        assertEquals(alightAt, ride.arrivalEpoch())
        assertEquals(journey.segments[0].arrivalEpoch(), ride.departureEpoch() - assertNotNull(ride.waitSeconds))
        assertEquals(listOf(fixture.routeId("1")), journey.legs.map { it.routeId })
        assertTrue(abs(assertNotNull(journey.walkingDistanceMeters) - 2 * 30 * 1.3) <= 2.0)
    }

    @Test
    fun `origin and destination within a metre of the stops still yield a continuous page`() {
        val fixture = PlanningFixture(listOf(FixtureRoute("1", lineA)))
        fixture.vehicle("1", "v1", lineA[0])

        val page = fixture.plan(near(lineA[2], northMeters = 0.5), near(lineA[8], northMeters = 0.5))

        assertEquals(listOf(TRANSIT), page.items.first { it.transitSegments.isNotEmpty() }.modes)
    }

    @Test
    fun `transfer at a shared stop chains two transit segments without a walk`() {
        val lineB = northLine("B", longitude = lineA[6].longitude, count = 10, startLat = 41.616)
            .map { if (it.rawId == "B6") lineA[6] else it }
        val fixture = PlanningFixture(listOf(FixtureRoute("1", lineA), FixtureRoute("2", lineB)))
        fixture.vehicle("1", "v1", lineA[0])
        fixture.vehicle("2", "w1", lineB[0])

        val journey = fixture.plan(near(lineA[2]), near(lineB[9], northMeters = 0.0, eastMeters = 40.0)).fastest()

        assertEquals(listOf(WALK, TRANSIT, TRANSIT, WALK), journey.modes)
        assertEquals(1, journey.transfers)
        val (first, second) = journey.transitSegments
        assertEquals(fixture.stopId("A6"), first.toStopId)
        assertEquals(fixture.stopId("A6"), second.fromStopId)
        assertEquals(fixture.routeId("2"), second.routeId)
        assertTrue(second.departureEpoch() - assertNotNull(second.waitSeconds) == first.arrivalEpoch())
        assertTrue(second.departureEpoch() >= first.arrivalEpoch() + 60, "transfer buffer is respected")
    }

    @Test
    fun `transfer via a walked stop inserts a walk between the rides`() {
        val lineC = northLine("C", longitude = lineA[6].longitude, count = 5, startLat = 41.6418) +
            FixtureStop("C5", 41.700, lineA[6].longitude)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", lineA), FixtureRoute("3", lineC)))
        fixture.vehicle("1", "v1", lineA[0])
        fixture.vehicle("3", "c1", lineC[5])

        val journey = fixture.plan(near(lineA[3]), near(lineC[4])).fastest()

        assertEquals(listOf(WALK, TRANSIT, WALK, TRANSIT, WALK), journey.modes)
        val transfer = journey.segments[2]
        assertEquals(fixture.stopId("A6"), transfer.fromStopId)
        assertEquals(fixture.stopId("C0"), transfer.toStopId)
        assertEquals(true, transfer.geometryApproximate)
        assertEquals(JourneyWaitBasis.LIVE_VEHICLE, journey.transitSegments[1].waitBasis)
    }

    @Test
    fun `a farther stop with an earlier live bus beats the nearest stop`() {
        val lineA20 = eastLine("A", latitude = 41.640, count = 20)
        val lineE = eastLine("E", latitude = 41.6445, count = 20)
        val fixture = PlanningFixture(listOf(FixtureRoute("1", lineA20), FixtureRoute("5", lineE)))
        fixture.vehicle("1", "v1", lineA20[0])
        fixture.vehicle("5", "e1", lineE[19])

        val page = fixture.plan(near(lineE[6], northMeters = -30.0), near(lineE[18], northMeters = -30.0))

        val fastest = page.fastest()
        assertEquals(listOf(JourneyLabel.OPTIMAL, JourneyLabel.FASTEST), fastest.labels)
        assertEquals(fastest, page.items.first(), "the OPTIMAL option leads the page")
        assertEquals(fixture.routeId("1"), fastest.transitSegments.single().routeId)
        assertEquals(fixture.stopId("A6"), fastest.transitSegments.single().fromStopId)
        val nearest = page.items.single { it.transitSegments.singleOrNull()?.routeId == fixture.routeId("5") }
        assertEquals(fixture.stopId("E6"), nearest.transitSegments.single().fromStopId)
        assertTrue(fastest.arrivalAt < nearest.arrivalAt)
        assertTrue(assertNotNull(nearest.walkingDistanceMeters) < assertNotNull(fastest.walkingDistanceMeters))
        assertEquals(listOf(JourneyLabel.LEAST_WALKING), nearest.labels)
    }

    @Test
    fun `boarding upstream on the same route wins when the nearest stop is beyond the live horizon`() {
        val longLine = eastLine("L", latitude = 41.640, count = 45)
        val fixture = PlanningFixture(listOf(FixtureRoute("7", longLine)))
        fixture.vehicle("7", "l1", longLine[0])

        // The destination is far enough that walking all the way cannot beat the bus.
        val page = fixture.plan(near(longLine[26], eastMeters = -120.0), near(longLine[44]))

        val transit = page.items.single { it.transitSegments.isNotEmpty() }.transitSegments.single()
        assertEquals(fixture.stopId("L25"), transit.fromStopId)
        assertEquals(JourneyWaitBasis.LIVE_VEHICLE, transit.waitBasis)
        assertTrue(page.realtime)
    }
}
