package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.WALK_DETOUR_FACTOR
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import com.denis.georgiatransit.shared.domain.model.distanceMetersTo
import kotlinx.collections.immutable.persistentListOf

internal fun JourneyOptionUi.withCurrentAccessWalk(origin: GeoPoint): JourneyOptionUi {
    val stop = boarding?.takeIf { it.plannedVehicleId != null }?.stopPosition ?: return this
    val meters = WalkingEstimate.meters(origin.distanceMetersTo(stop) * WALK_DETOUR_FACTOR)
    val previous = (legs.firstOrNull() as? JourneyLegUi.Walk)?.takeIf { it.role == JourneyWalkRole.Access }
    val walk = JourneyLegUi.Walk(JourneyWalkRole.Access, meters, WalkingEstimate.minutes(meters), isApproximate = true)
    val line = JourneyPlanLineUi(JourneyPlanLineKind.Walk, persistentListOf(origin, stop), JourneyWalkLineArgb)
    val remainingLines = if (lines.firstOrNull()?.kind == JourneyPlanLineKind.Walk) lines.removeAt(0) else lines
    return copy(
        legs = listOf(walk) + if (previous == null) legs else legs.drop(1),
        walkingMeters = walkingMeters?.let { (it - (previous?.meters ?: 0)).coerceAtLeast(0) + meters },
        lines = if (origin == stop) remainingLines else remainingLines.add(0, line),
    )
}

/** Plan IDs change on refresh. Compare the actual boarding and ride stops, including transfers. */
internal fun JourneyOptionUi.sameLiveJourney(other: JourneyOptionUi): Boolean {
    if (boarding?.plannedVehicleId != other.boarding?.plannedVehicleId) return false
    val rides = legs.filterIsInstance<JourneyLegUi.Ride>()
    val otherRides = other.legs.filterIsInstance<JourneyLegUi.Ride>()
    return boarding?.stopId == other.boarding?.stopId && rides.size == otherRides.size &&
        rides.zip(otherRides).all { (a, b) ->
            a.route.routeId == b.route.routeId && a.directionId == b.directionId &&
                a.fromStopId == b.fromStopId && a.toStopId == b.toStopId
        }
}

internal fun JourneyOptionUi.retained(): JourneyOptionUi {
    val firstRide = legs.firstOrNull { it is JourneyLegUi.Ride }
    return copy(labels = emptyList(), legs = legs.map {
        if (it === firstRide && it is JourneyLegUi.Ride) it.copy(waitMinutes = null) else it
    })
}

internal fun JourneyBoarding.sameBusAtStop(other: JourneyBoarding): Boolean =
    plannedVehicleId == other.plannedVehicleId && stopId == other.stopId && routeId == other.routeId && directionId == other.directionId
