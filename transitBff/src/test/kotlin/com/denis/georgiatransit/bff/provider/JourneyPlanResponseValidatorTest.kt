package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JourneyPlanResponseValidatorTest {
    private val a = GeoPoint(41.64, 41.60)
    private val b = GeoPoint(41.64, 41.61)
    private val geometry = com.denis.georgiatransit.bff.geo.encodePolyline(listOf(a, b))
    private val walkIn = JourneySegment(
        departureAt = "2030-01-01T00:00:00Z",
        arrivalAt = "2030-01-01T00:02:00Z",
        mode = JourneySegmentMode.WALK,
        toStopId = "test:p:stop:s1",
        fromPosition = a,
        toPosition = b,
        distanceMeters = 156.0,
        geometry = geometry,
        geometryApproximate = true,
    )
    private val ride = JourneySegment(
        departureAt = "2030-01-01T00:05:00Z",
        arrivalAt = "2030-01-01T00:15:00Z",
        mode = JourneySegmentMode.TRANSIT,
        routeId = "test:p:route:r1",
        directionId = "test:p:direction:d1",
        fromStopId = "test:p:stop:s1",
        toStopId = "test:p:stop:s2",
        distanceMeters = 2_000.0,
        geometry = geometry,
        geometryApproximate = false,
        intermediateStopCount = 3,
        waitSeconds = 180,
        waitBasis = JourneyWaitBasis.LIVE_VEHICLE,
        vehicleId = "test:p:vehicle:v1",
    )
    private val walkOut = walkIn.copy(
        departureAt = "2030-01-01T00:15:00Z",
        arrivalAt = "2030-01-01T00:17:00Z",
        fromStopId = "test:p:stop:s2",
        toStopId = null,
    )

    private fun journey(segments: List<JourneySegment> = listOf(walkIn, ride, walkOut)) = Journey(
        id = "test:p:journey:j1",
        departureAt = "2030-01-01T00:00:00Z",
        arrivalAt = segments.last().arrivalAt,
        transfers = (segments.count { it.mode == JourneySegmentMode.TRANSIT } - 1).coerceAtLeast(0),
        legs = segments.filter { it.mode == JourneySegmentMode.TRANSIT }.map {
            JourneyLeg(it.routeId!!, it.directionId!!, it.fromStopId!!, it.toStopId!!, it.departureAt, it.arrivalAt)
        },
        segments = segments,
        durationSeconds = java.time.Duration.between(
            java.time.Instant.parse("2030-01-01T00:00:00Z"),
            java.time.Instant.parse(segments.last().arrivalAt),
        ).seconds,
        walkingDistanceMeters = segments.filter { it.mode == JourneySegmentMode.WALK }.sumOf { it.distanceMeters ?: 0.0 },
        timingQuality = JourneyTimingQuality.LIVE,
    )

    private fun page(vararg items: Journey, realtime: Boolean = true) = JourneyPage(
        items = items.toList(),
        observedAt = "2030-01-01T00:00:00Z",
        source = ArrivalSource.CLIENT_ESTIMATE,
        realtime = realtime,
        stale = false,
    )

    private fun rejects(page: JourneyPage) {
        assertFailsWith<ProviderNormalizedSchemaFailure> { JourneyPlanResponseValidator.page("test", page) }
    }

    @Test
    fun `a continuous walk transit walk plan is accepted`() {
        JourneyPlanResponseValidator.page("test", page(journey()))
        JourneyPlanResponseValidator.page("test", page(realtime = false))
    }

    @Test
    fun `time gaps and waits that do not close the gap are rejected`() {
        rejects(page(journey(listOf(walkIn, ride.copy(waitSeconds = 60), walkOut))))
        rejects(page(journey(listOf(walkIn, ride, walkOut.copy(departureAt = "2030-01-01T00:16:00Z")))))
        rejects(page(journey().copy(durationSeconds = 1)))
    }

    @Test
    fun `consecutive walks and mismatched stop handoffs are rejected`() {
        rejects(page(journey(listOf(walkIn, walkOut.copy(departureAt = walkIn.arrivalAt, arrivalAt = "2030-01-01T00:04:00Z")))))
        rejects(page(journey(listOf(walkIn.copy(toStopId = "test:p:stop:other"), ride, walkOut))))
    }

    @Test
    fun `inconsistent additive fields and flags are rejected`() {
        rejects(page(journey().copy(transfers = 1)))
        rejects(page(journey().copy(timingQuality = JourneyTimingQuality.MIXED)))
        rejects(page(journey().copy(walkingDistanceMeters = 10.0)))
        rejects(page(journey(), realtime = false))
        rejects(page(journey(listOf(walkIn.copy(distanceMeters = -1.0), ride, walkOut))))
        rejects(page(journey(listOf(walkIn.copy(waitSeconds = 0), ride, walkOut))))
        rejects(page(journey(listOf(walkIn, ride.copy(vehicleId = null), walkOut))))
        rejects(page(journey(listOf(walkIn, ride.copy(waitBasis = JourneyWaitBasis.HEADWAY_ESTIMATE), walkOut))))
        rejects(page(journey(listOf(walkIn, ride.copy(geometry = "_"), walkOut))))
        rejects(page(journey()).copy(source = ArrivalSource.SCHEDULE))
        rejects(page(journey(), journey()))
    }
}
