package com.denis.georgiatransit.shared.presentation.map

import kotlinx.collections.immutable.persistentListOf

private const val HIDDEN_VEHICLE_REVISION = -1L

/**
 * Layers the planner slice over the session render state for native maps.
 */
internal fun MapRenderState.withJourneyPlanner(planner: JourneyPlannerUi): MapRenderState {
    val render = planner.render
    if (render == JourneyPlanRenderUi() && !planner.isOpen) return this
    val overlay = MapJourneyOverlay(
        lines = render.lines,
        origin = render.origin,
        destination = render.destination,
        fitRevision = planner.cameraFitRevision,
        fitSuspended = planner.mapPick != null,
    )
    return copy(
        journey = overlay,
        vehicles = if (planner.isOpen) persistentListOf() else vehicles,
        vehicleSourceRevision = if (planner.isOpen) HIDDEN_VEHICLE_REVISION else vehicleSourceRevision,
        vehicleBadgeRevision = if (planner.isOpen) HIDDEN_VEHICLE_REVISION else vehicleBadgeRevision,
    )
}
