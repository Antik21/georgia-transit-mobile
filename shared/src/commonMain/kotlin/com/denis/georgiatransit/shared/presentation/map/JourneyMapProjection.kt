package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlinx.collections.immutable.toPersistentList

/**
 * Layers the planner slice over the session render state for native maps. While a plan is drawn,
 * only its nearest buses stay on the vehicle layer, so they alone carry the attention pulse.
 */
internal fun MapRenderState.withJourneyPlanner(planner: JourneyPlannerUi): MapRenderState {
    val render = planner.render
    if (render == JourneyPlanRenderUi()) return this
    val overlay = MapJourneyOverlay(
        lines = render.lines,
        origin = render.origin,
        destination = render.destination,
        fitRevision = planner.cameraFitRevision,
        fitSuspended = planner.mapPick != null,
    )
    if (render.lines.isEmpty()) return copy(journey = overlay)
    val buses = planner.buses?.let { buses -> buses.vehicles.map { it.toMarker(buses.boarding) } }.orEmpty()
    val sourceRevision = journeyRevision(buses.map { listOf(it.id, it.position, it.bearingDegrees) })
    return copy(
        journey = overlay,
        vehicles = buses.toPersistentList(),
        vehicleSourceRevision = sourceRevision,
        vehicleBadgeRevision = journeyRevision(buses.map { listOf(it.routeLabel, it.routeColorArgb, it.routeTextColorArgb) }),
    )
}

private fun JourneyBusUi.toMarker(boarding: JourneyBoarding) = MapVehicleMarker(
    id = id,
    routeId = route.routeId,
    directionId = boarding.directionId,
    position = position,
    routeColorArgb = route.backgroundArgb,
    bearingDegrees = bearingDegrees,
    positionKind = VehiclePositionKind.Gps,
    freshness = TransitFreshness.Network,
    routeLabel = route.label,
    routeTextColorArgb = route.textArgb,
    isStale = false,
    frameRevision = 0L,
)

// Adapters only compare revisions for inequality; the negative range never meets a session revision.
private fun journeyRevision(content: Any): Long = -1L - (content.hashCode().toLong() and 0x7FFF_FFFFL)
