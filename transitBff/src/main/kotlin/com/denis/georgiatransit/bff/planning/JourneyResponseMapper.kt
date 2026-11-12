package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.formatPublicId
import com.denis.georgiatransit.bff.geo.encodePolyline
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.roundToLong

/** Maps planner itineraries to the additive public `Journey` shape (ADR 0022 contract section). */
internal class JourneyResponseMapper(
    private val graph: TransitNetworkGraph,
    private val cityId: String,
    private val providerId: String,
) {
    fun journey(itinerary: PlannedItinerary, rank: Int): Journey {
        val segments = itinerary.legs.map { leg ->
            when (leg) {
                is WalkLeg -> walkSegment(leg)
                is RideLeg -> rideSegment(leg)
            }
        }
        return Journey(
            id = journeyId(itinerary, rank),
            departureAt = timestamp(itinerary.departure),
            arrivalAt = timestamp(itinerary.arrival),
            transfers = itinerary.transfers,
            legs = segments.filter { it.mode == JourneySegmentMode.TRANSIT }.map(::legacyLeg),
            segments = segments,
            durationSeconds = itinerary.durationSeconds,
            walkingDistanceMeters = segments.filter { it.mode == JourneySegmentMode.WALK }
                .sumOf { it.distanceMeters ?: 0.0 },
            timingQuality = timingQuality(itinerary),
        )
    }

    private fun walkSegment(leg: WalkLeg) = JourneySegment(
        departureAt = timestamp(leg.departure),
        arrivalAt = timestamp(leg.arrival),
        mode = JourneySegmentMode.WALK,
        fromStopId = leg.fromStop?.let { graph.stops[it].id },
        toStopId = leg.toStop?.let { graph.stops[it].id },
        fromPosition = leg.fromPoint,
        toPosition = leg.toPoint,
        distanceMeters = leg.meters.roundToLong().toDouble(),
        geometry = encodePolyline(listOf(leg.fromPoint, leg.toPoint)),
        geometryApproximate = true,
    )

    private fun rideSegment(leg: RideLeg): JourneySegment {
        val pattern = graph.patterns[leg.pattern]
        val positions = leg.boardPosition until leg.alightPosition
        val geometry = positions.flatMap { position ->
            pattern.segmentGeometry[position].let { if (position == leg.boardPosition) it else it.drop(1) }
        }
        return JourneySegment(
            departureAt = timestamp(leg.departure),
            arrivalAt = timestamp(leg.arrival),
            mode = JourneySegmentMode.TRANSIT,
            routeId = pattern.routeId,
            directionId = pattern.directionId,
            fromStopId = graph.stops[pattern.stops[leg.boardPosition]].id,
            toStopId = graph.stops[pattern.stops[leg.alightPosition]].id,
            fromPosition = graph.stops[pattern.stops[leg.boardPosition]].position,
            toPosition = graph.stops[pattern.stops[leg.alightPosition]].position,
            distanceMeters = positions.sumOf { pattern.segmentMeters[it] }.roundToLong().toDouble(),
            geometry = encodePolyline(geometry),
            geometryApproximate = positions.any { pattern.segmentApproximate[it] },
            intermediateStopCount = leg.alightPosition - leg.boardPosition - 1,
            waitSeconds = leg.waitSeconds,
            waitBasis = leg.waitBasis,
            vehicleId = leg.vehicleId,
        )
    }

    private fun legacyLeg(segment: JourneySegment) = JourneyLeg(
        routeId = requireNotNull(segment.routeId),
        directionId = requireNotNull(segment.directionId),
        fromStopId = requireNotNull(segment.fromStopId),
        toStopId = requireNotNull(segment.toStopId),
        departureAt = segment.departureAt,
        arrivalAt = segment.arrivalAt,
    )

    /** Derived only from public stop/route IDs and times, never from the request coordinates. */
    private fun journeyId(itinerary: PlannedItinerary, rank: Int): String {
        val signature = buildString {
            append(rank).append('|').append(itinerary.departure)
            itinerary.rides.forEach { ride ->
                val pattern = graph.patterns[ride.pattern]
                append('|').append(pattern.routeId)
                append('|').append(graph.stops[pattern.stops[ride.boardPosition]].id)
                append('|').append(graph.stops[pattern.stops[ride.alightPosition]].id)
                append('|').append(ride.departure)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(StandardCharsets.UTF_8))
        val token = digest.take(8).joinToString("") { "%02x".format(it) }
        val kind = if (itinerary.isWalkOnly) "walk" else "plan"
        return formatPublicId(cityId, providerId, PublicEntityType.Journey, "$kind-$token")
    }

    private fun timestamp(epochSecond: Long): String = Instant.ofEpochSecond(epochSecond).toString()
}

internal fun timingQuality(itinerary: PlannedItinerary): JourneyTimingQuality {
    val live = itinerary.rides.count { it.waitBasis == JourneyWaitBasis.LIVE_VEHICLE }
    return when {
        itinerary.rides.isEmpty() || live == 0 -> JourneyTimingQuality.ESTIMATED
        live == itinerary.rides.size -> JourneyTimingQuality.LIVE
        else -> JourneyTimingQuality.MIXED
    }
}
