package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.presentation.location.LocationState

/** Pure state transition for a reconciled city, route selection, or location update. */
internal object MapSessionStateReducer {
    fun reduce(previous: ViewState, input: MapSessionStateInput): ViewState = previous.copy(
        cityName = input.city?.name.orEmpty(),
        routesAvailable = input.city?.capabilities?.routes == true,
        renderState = input.render,
        contentState = if (input.change.resetStops) {
            if (input.city?.capabilities?.stops == true) MapContentState.Loading else MapContentState.Unavailable
        } else previous.contentState,
        selectedRouteNames = input.routeNames,
        routeGeometryLegends = input.geometry.legends,
        attribution = input.city?.attribution.orEmpty(),
        location = input.location,
        selectedStop = input.selectedStop,
        nearbyStops = input.nearbyStops,
        stopArrivalsSheet = if (input.change.resetStops) null else input.sheet,
        vehicleLayerState = input.vehicleLayer,
        vehicleRoutes = input.vehicleRoutes,
        journeyPlanner = input.planner,
    )
}

internal data class MapSessionStateInput(
    val city: TransitCity?,
    val change: MapSessionChange,
    val location: LocationState,
    val render: MapRenderState?,
    val routeNames: List<String>,
    val geometry: RouteGeometrySnapshot,
    val selectedStop: SelectedStopUi?,
    val nearbyStops: List<NearbyStopUi>,
    val sheet: StopArrivalsSheetUi?,
    val vehicleLayer: VehicleLayerState,
    val vehicleRoutes: List<VehicleRouteAccessibilityUi>,
    val planner: JourneyPlannerUi,
)
