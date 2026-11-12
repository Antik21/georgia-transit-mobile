package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.JourneyTimingQuality
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.MAX_FIX_AGE_MILLIS
import com.denis.georgiatransit.shared.presentation.location.MAX_PRECISE_ACCURACY_METERS
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import com.denis.georgiatransit.shared.presentation.ui.displayLabel
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Instant

internal sealed interface JourneyLocationGate {
    data class Pass(val point: GeoPoint) : JourneyLocationGate
    data class Needed(val reason: JourneyLocationReason) : JourneyLocationGate

    companion object {
        /** ADR 0023: a fix resolves "My location" only when ≤ 120 s old and ≤ 250 m accurate at send time. */
        fun check(location: LocationState, nowMillis: Long): JourneyLocationGate {
            if (location.permission !is LocationPermissionState.Granted) {
                return Needed(JourneyLocationReason.PermissionRequired)
            }
            val fix = location.fix ?: return Needed(JourneyLocationReason.Locating)
            if (nowMillis - fix.capturedAtEpochMillis !in 0..MAX_FIX_AGE_MILLIS) {
                return Needed(JourneyLocationReason.Locating)
            }
            if (fix.accuracyMeters > MAX_PRECISE_ACCURACY_METERS) return Needed(JourneyLocationReason.Approximate)
            return Pass(fix.point)
        }
    }
}

/** Turns a plan page into display-ready options; routes come from the city catalogue for colours. */
internal object JourneyPlanUiMapper {
    fun options(page: JourneyPage, routes: List<TransitRoute>): List<JourneyOptionUi> =
        page.items.map { journey -> option(journey, routes) }

    private fun option(journey: TransitJourney, routes: List<TransitRoute>): JourneyOptionUi {
        val segments = journey.segments
        val transitIndexes = segments.indices.filter { segments[it].mode == JourneySegmentMode.Transit }
        return JourneyOptionUi(
            id = journey.id,
            totalMinutes = journey.durationSeconds?.let(::minutesCeil) ?: minutesBetween(journey.departureAt, journey.arrivalAt),
            arrivalAt = journey.arrivalAt,
            transfers = journey.transfers,
            walkingMeters = (journey.walkingDistanceMeters ?: segments.walkingMeters())?.roundToInt(),
            timing = journey.timingQuality?.toUi() ?: segments.derivedTiming(),
            legs = segments.mapIndexedNotNull { index, segment ->
                if (segment.mode == JourneySegmentMode.Transit) {
                    ride(segment, routes)
                } else {
                    walk(segment, walkRole(index, transitIndexes))
                }
            },
            lines = segments.mapNotNull { line(it, routes) }.toPersistentList(),
        )
    }

    private fun walk(segment: TransitJourneySegment, role: JourneyWalkRole): JourneyLegUi.Walk? {
        val sameStop = segment.fromStopId != null && segment.fromStopId == segment.toStopId
        val minutes = minutesBetween(segment.departureAt, segment.arrivalAt)
        if (role == JourneyWalkRole.Transfer && sameStop) return null
        if (segment.distanceMeters == 0.0 && minutes == 0) return null
        return JourneyLegUi.Walk(
            role = role,
            meters = segment.distanceMeters?.roundToInt(),
            minutes = minutes,
            isApproximate = segment.geometryApproximate != false,
        )
    }

    private fun ride(segment: TransitJourneySegment, routes: List<TransitRoute>) = JourneyLegUi.Ride(
        route = badge(segment, routes),
        stopCount = segment.intermediateStopCount?.plus(1),
        rideMinutes = minutesBetween(segment.departureAt, segment.arrivalAt),
        waitMinutes = segment.waitSeconds?.let(::minutesCeil),
        waitIsLive = segment.waitBasis == JourneyWaitBasis.LiveVehicle,
    )

    private fun badge(segment: TransitJourneySegment, routes: List<TransitRoute>): JourneyRouteBadgeUi {
        val routeId = checkNotNull(segment.routeId)
        val route = routes.firstOrNull { it.id == routeId }
            ?: return JourneyRouteBadgeUi(routeId, "?", JourneyUnknownRouteArgb, 0xFFFFFFFFL)
        return JourneyRouteBadgeUi(
            routeId = routeId,
            label = route.displayLabel(),
            backgroundArgb = route.colorArgb,
            textArgb = contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb),
        )
    }

    private fun line(segment: TransitJourneySegment, routes: List<TransitRoute>): JourneyPlanLineUi? {
        val decoded = segment.geometry?.let { decodeEncodedPolyline(it, POLYLINE_PRECISION) }
        val points = (decoded as? EncodedPolylineDecodeResult.Success)?.points
            ?: listOfNotNull(segment.fromPosition, segment.toPosition).takeIf { it.size == 2 && it[0] != it[1] }
            ?: return null
        val transit = segment.mode == JourneySegmentMode.Transit
        return JourneyPlanLineUi(
            kind = if (transit) JourneyPlanLineKind.Transit else JourneyPlanLineKind.Walk,
            points = points.toPersistentList(),
            colorArgb = if (transit) badge(segment, routes).backgroundArgb else JourneyWalkLineArgb,
        )
    }

    private fun walkRole(index: Int, transitIndexes: List<Int>): JourneyWalkRole = when {
        transitIndexes.isEmpty() -> JourneyWalkRole.Direct
        index < transitIndexes.first() -> JourneyWalkRole.Access
        index > transitIndexes.last() -> JourneyWalkRole.Egress
        else -> JourneyWalkRole.Transfer
    }

    private fun List<TransitJourneySegment>.walkingMeters(): Double? =
        filter { it.mode == JourneySegmentMode.Walk }.map { it.distanceMeters ?: return null }.sum()

    private fun List<TransitJourneySegment>.derivedTiming(): JourneyTimingUi? {
        val waits = filter { it.mode == JourneySegmentMode.Transit }.map { it.waitBasis == JourneyWaitBasis.LiveVehicle }
        return when {
            waits.isEmpty() -> null
            waits.all { it } -> JourneyTimingUi.Live
            waits.none { it } -> JourneyTimingUi.Estimated
            else -> JourneyTimingUi.Mixed
        }
    }

    private fun JourneyTimingQuality.toUi() = when (this) {
        JourneyTimingQuality.Live -> JourneyTimingUi.Live
        JourneyTimingQuality.Mixed -> JourneyTimingUi.Mixed
        JourneyTimingQuality.Estimated -> JourneyTimingUi.Estimated
    }

    private fun minutesBetween(start: Instant, end: Instant): Int =
        minutesCeil((end - start).inWholeSeconds.coerceAtLeast(0).toInt())

    private fun minutesCeil(seconds: Int): Int = ceil(seconds.coerceAtLeast(0) / 60.0).toInt()

    private const val POLYLINE_PRECISION = 5
}
