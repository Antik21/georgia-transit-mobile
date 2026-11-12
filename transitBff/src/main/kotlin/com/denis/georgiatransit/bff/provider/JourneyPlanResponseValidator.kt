package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.parsePublicId
import com.denis.georgiatransit.bff.geo.decodePolyline
import java.time.Instant
import kotlin.math.abs

private const val MaximumPlannedOptions = 3
private const val DistanceToleranceMeters = 1.0

/**
 * Structural checks for `POST /journeys/plan` on top of the common journey validation: time
 * continuity with waits, WALK/TRANSIT alternation, and consistency of the additive fields.
 */
object JourneyPlanResponseValidator {
    fun page(cityId: String, page: JourneyPage) {
        NormalizedResponseValidator.journeyPage(cityId, page)
        if (page.source != ArrivalSource.CLIENT_ESTIMATE || page.items.size > MaximumPlannedOptions) invalid()
        if (page.items.map(Journey::id).distinct().size != page.items.size) invalid()
        page.items.forEach { journey(cityId, it) }
        val transit = page.items.flatMap(Journey::segments).filter { it.mode == JourneySegmentMode.TRANSIT }
        val expectedRealtime = transit.isNotEmpty() && transit.all { it.waitBasis == JourneyWaitBasis.LIVE_VEHICLE }
        if (page.realtime != expectedRealtime) invalid()
    }

    private fun journey(cityId: String, journey: Journey) {
        val segments = journey.segments.takeIf(List<JourneySegment>::isNotEmpty) ?: invalid()
        val departure = Instant.parse(journey.departureAt)
        val arrival = Instant.parse(journey.arrivalAt)
        if (journey.durationSeconds != arrival.epochSecond - departure.epochSecond) invalid()
        var previousEnd = departure
        var previous: JourneySegment? = null
        segments.forEach { segment ->
            segmentFields(cityId, segment)
            val start = Instant.parse(segment.departureAt).minusSeconds(segment.waitSeconds ?: 0L)
            if (start != previousEnd) invalid()
            previous?.let { adjacent(it, segment) }
            previousEnd = Instant.parse(segment.arrivalAt)
            previous = segment
        }
        if (previousEnd != arrival) invalid()
        val transit = segments.filter { it.mode == JourneySegmentMode.TRANSIT }
        if (journey.transfers != (transit.size - 1).coerceAtLeast(0)) invalid()
        val walking = segments.filter { it.mode == JourneySegmentMode.WALK }.sumOf { it.distanceMeters ?: invalid() }
        val reported = journey.walkingDistanceMeters ?: invalid()
        if (!reported.isFinite() || abs(reported - walking) > DistanceToleranceMeters) invalid()
        val live = transit.count { it.waitBasis == JourneyWaitBasis.LIVE_VEHICLE }
        val expectedQuality = when {
            live == 0 -> JourneyTimingQuality.ESTIMATED
            live == transit.size -> JourneyTimingQuality.LIVE
            else -> JourneyTimingQuality.MIXED
        }
        if (journey.timingQuality != expectedQuality) invalid()
    }

    private fun adjacent(previous: JourneySegment, next: JourneySegment) {
        when {
            previous.mode == JourneySegmentMode.WALK && next.mode == JourneySegmentMode.WALK -> invalid()
            previous.mode == JourneySegmentMode.TRANSIT && next.mode == JourneySegmentMode.TRANSIT ->
                if (previous.toStopId != next.fromStopId) invalid()
            previous.mode == JourneySegmentMode.WALK ->
                if (previous.toStopId != null && previous.toStopId != next.fromStopId) invalid()
            else -> if (next.fromStopId != null && next.fromStopId != previous.toStopId) invalid()
        }
    }

    private fun segmentFields(cityId: String, segment: JourneySegment) {
        val distance = segment.distanceMeters ?: invalid()
        if (!distance.isFinite() || distance < 0.0) invalid()
        segment.geometry?.let { encoded ->
            val points = try {
                decodePolyline(encoded)
            } catch (_: IllegalArgumentException) {
                invalid()
            }
            if (points.size < 2) invalid()
        }
        when (segment.mode) {
            JourneySegmentMode.WALK -> {
                if (segment.intermediateStopCount != null || segment.waitSeconds != null ||
                    segment.waitBasis != null || segment.vehicleId != null
                ) {
                    invalid()
                }
            }
            JourneySegmentMode.TRANSIT -> {
                if ((segment.intermediateStopCount ?: invalid()) < 0 || (segment.waitSeconds ?: invalid()) < 0L) invalid()
                when (segment.waitBasis ?: invalid()) {
                    JourneyWaitBasis.LIVE_VEHICLE -> {
                        val vehicle = parsePublicId(segment.vehicleId ?: invalid(), cityId, PublicEntityType.Vehicle) ?: invalid()
                        val route = parsePublicId(segment.routeId ?: invalid(), cityId, PublicEntityType.Route) ?: invalid()
                        if (vehicle.provider != route.provider) invalid()
                    }
                    JourneyWaitBasis.HEADWAY_ESTIMATE -> if (segment.vehicleId != null) invalid()
                }
            }
            else -> invalid()
        }
    }

    private fun invalid(): Nothing =
        throw ProviderNormalizedSchemaFailure("The provider returned an invalid normalized response")
}
