package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlinx.collections.immutable.persistentListOf

private const val HIDDEN_LAYER_REVISION = -1L

/**
 * Layers the planner slice over the session render state for native maps.
 */
internal fun MapRenderState.withJourneyPlanner(planner: JourneyPlannerUi): MapRenderState {
    val render = planner.render
    if (render == JourneyPlanRenderUi() && !planner.isOpen) return this
    val boardingVehicle = planner.boardingVehicleMarker()
    val overlay = MapJourneyOverlay(
        lines = render.lines,
        origin = render.origin,
        destination = render.destination,
        fitRevision = planner.cameraFitRevision,
        fitSuspended = planner.mapPick != null,
        boardingFitPosition = planner.boardingFitPosition.takeIf { boardingVehicle != null },
    )
    return copy(
        journey = overlay,
        polylines = if (planner.isOpen) persistentListOf() else polylines,
        polylineSourceRevision = if (planner.isOpen) HIDDEN_LAYER_REVISION else polylineSourceRevision,
        vehicles = if (planner.isOpen) boardingVehicle?.let { persistentListOf(it) } ?: persistentListOf() else vehicles,
        vehicleSourceRevision = if (planner.isOpen) boardingVehicle.boardingSourceRevision() else vehicleSourceRevision,
        vehicleBadgeRevision = if (planner.isOpen) boardingVehicle.boardingBadgeRevision() else vehicleBadgeRevision,
    )
}

internal fun JourneyPlannerUi.boardingBus(): JourneyBusUi? {
    if (!isOpen || !showingResults || mapPick != null) return null
    val arrivals = buses ?: return null
    val plannedId = arrivals.boarding.plannedVehicleId ?: return null
    return arrivals.vehicles.firstOrNull { it.id == plannedId }
}

internal fun JourneyPlannerUi.boardingVehicleMarker(): MapVehicleMarker? {
    val bus = boardingBus() ?: return null
    return MapVehicleMarker(
        id = bus.id,
        routeId = bus.route.routeId,
        directionId = buses?.boarding?.directionId,
        position = bus.position,
        routeColorArgb = bus.route.backgroundArgb,
        bearingDegrees = bus.bearingDegrees,
        positionKind = bus.positionKind,
        freshness = TransitFreshness.Network,
        routeLabel = bus.route.label,
        routeTextColorArgb = bus.route.textArgb,
        isStale = false,
        frameRevision = 0L,
    )
}

internal fun MapVehicleMarker?.boardingSourceRevision(): Long = this?.hashCode().boardingLayerRevision()

internal fun MapVehicleMarker?.boardingBadgeRevision(): Long =
    this?.let { Triple(it.routeLabel, it.routeColorArgb, it.routeTextColorArgb).hashCode() }.boardingLayerRevision()

// Planner vehicle revisions are disjoint from both the hidden layer and the regular fleet revisions.
private fun Int?.boardingLayerRevision(): Long = this?.let { -2L - (it.toLong() and 0xFFFF_FFFFL) }
    ?: HIDDEN_LAYER_REVISION
