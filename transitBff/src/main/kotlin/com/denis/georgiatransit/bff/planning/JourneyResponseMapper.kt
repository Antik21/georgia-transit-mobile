package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLabel
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
    fun journey(itinerary: PlannedItinerary, rank: Int, labels: List<JourneyLabel>): Journey {
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
            labels = labels.takeIf(List<JourneyLabel>::isNotEmpty),
        )
    }

    private fun walkSegment(leg: WalkLeg) =
        walkSegment(leg.fromStop, leg.toStop, leg.fromPoint, leg.toPoint, leg.meters)
            .copy(departureAt = timestamp(leg.departure), arrivalAt = timestamp(leg.arrival))

    fun walkSegment(fromStop: Int?, toStop: Int?, from: GeoPoint, to: GeoPoint, meters: Double) = JourneySegment(
        mode = JourneySegmentMode.WALK,
        fromStopId = fromStop?.let { graph.stops[it].id },
        toStopId = toStop?.let { graph.stops[it].id },
        fromPosition = from,
        toPosition = to,
        distanceMeters = meters.roundToLong().toDouble(),
        geometry = encodePolyline(listOf(from, to)),
        geometryApproximate = true,
    )

    private fun rideSegment(leg: RideLeg) = rideSegment(leg.pattern, leg.boardPosition, leg.alightPosition).copy(
        departureAt = timestamp(leg.departure),
        arrivalAt = timestamp(leg.arrival),
        waitSeconds = leg.waitSeconds,
        waitBasis = leg.waitBasis,
        vehicleId = leg.vehicleId,
    )

    fun rideSegment(patternIndex: Int, board: Int, alight: Int): JourneySegment {
        val pattern = graph.patterns[patternIndex]
        val positions = board until alight
        val from = graph.stops[pattern.stops[board]]
        val to = graph.stops[pattern.stops[alight]]
        val geometry = positions.flatMap { position ->
            pattern.segmentGeometry[position].let { if (position == board) it else it.drop(1) }
        }
        return JourneySegment(
            mode = JourneySegmentMode.TRANSIT,
            routeId = pattern.routeId,
            directionId = pattern.directionId,
            fromStopId = from.id,
            toStopId = to.id,
            fromPosition = from.position,
            toPosition = to.position,
            fromStopName = from.name,
            toStopName = to.name,
            distanceMeters = positions.sumOf { pattern.segmentMeters[it] }.roundToLong().toDouble(),
            geometry = encodePolyline(geometry),
            geometryApproximate = positions.any { pattern.segmentApproximate[it] },
            intermediateStopCount = alight - board - 1,
        )
    }

    private fun legacyLeg(segment: JourneySegment) = JourneyLeg(
        routeId = requireNotNull(segment.routeId),
        directionId = requireNotNull(segment.directionId),
        fromStopId = requireNotNull(segment.fromStopId),
        toStopId = requireNotNull(segment.toStopId),
        departureAt = requireNotNull(segment.departureAt),
        arrivalAt = requireNotNull(segment.arrivalAt),
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
        val kind = if (itinerary.isWalkOnly) "walk" else "plan"
        return journeyId(signature, kind)
    }

    fun untimedJourney(segments: List<JourneySegment>): Journey {
        val rides = segments.filter { it.mode == JourneySegmentMode.TRANSIT }
        val signature = rides.joinToString("|") { "${it.routeId}|${it.directionId}|${it.fromStopId}|${it.toStopId}" }
        return Journey(
            id = journeyId(signature, "untimed"),
            transfers = rides.size - 1,
            legs = emptyList(),
            segments = segments,
            walkingDistanceMeters = segments.filter { it.mode == JourneySegmentMode.WALK }.sumOf { checkNotNull(it.distanceMeters) },
        )
    }

    private fun journeyId(signature: String, kind: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(StandardCharsets.UTF_8))
        val token = digest.take(8).joinToString("") { "%02x".format(it) }
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
