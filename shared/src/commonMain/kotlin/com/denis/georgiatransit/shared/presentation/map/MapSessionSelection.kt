package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.ui.RouteColorAvailability
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjector

internal data class MapSessionChange(
    val cityChanged: Boolean,
    val stopsCapabilityChanged: Boolean,
    val routesCapabilityChanged: Boolean,
    val vehicleCapabilityChanged: Boolean,
    val geometryCapabilityChanged: Boolean,
    val arrivalsCapabilityChanged: Boolean,
    val vehicleSelectionChanged: Boolean,
) {
    val selectionOverlayChanged: Boolean get() = vehicleSelectionChanged || routesCapabilityChanged
    val resetStops: Boolean get() = cityChanged || stopsCapabilityChanged
    val resetVehicles: Boolean get() = cityChanged || routesCapabilityChanged ||
        vehicleCapabilityChanged || vehicleSelectionChanged
    val resetRouteStops: Boolean get() = cityChanged || routesCapabilityChanged ||
        stopsCapabilityChanged || vehicleSelectionChanged
    val resetGeometry: Boolean get() = cityChanged || routesCapabilityChanged ||
        geometryCapabilityChanged || vehicleSelectionChanged
}

/** Reconciles the active city's canonical route catalogue with committed selection. */
internal class MapSessionSelection(private val repository: TransitRepository) {
    var city: TransitCity? = null
        private set
    var routeCatalog: List<TransitRoute> = emptyList()
        private set
    var projection = RouteSelectionProjection()
        private set
    var selectedCatalogRoutes: List<TransitRoute> = emptyList()
        private set
    var vehicleRoutes: List<TransitRoute> = emptyList()
        private set
    private var lastSelectedRouteIds: Set<RouteId> = emptySet()

    fun update(nextCity: TransitCity?, selectedIds: Set<RouteId>): MapSessionChange {
        val previousCity = city
        val cityChanged = nextCity?.id != previousCity?.id
        val previousProjection = projection
        routeCatalog = nextCity?.takeIf { it.capabilities.routes }?.let { selectedCity ->
            repository.routes(selectedCity.id).filter { it.cityId == selectedCity.id }
        }.orEmpty()
        projection = RouteSelectionProjector.resolve(routeCatalog, selectedIds)
        selectedCatalogRoutes = projection.routes.mapNotNull { selected ->
            routeCatalog.firstOrNull { it.id == selected.routeId }
        }
        vehicleRoutes = selectedCatalogRoutes.filter { route ->
            projection.byId[route.id]?.colorAvailability == RouteColorAvailability.Assigned
        }
        city = nextCity
        val change = MapSessionChange(
            cityChanged = cityChanged,
            stopsCapabilityChanged = !cityChanged && nextCity?.capabilities?.stops != previousCity?.capabilities?.stops,
            routesCapabilityChanged = !cityChanged && nextCity?.capabilities?.routes != previousCity?.capabilities?.routes,
            vehicleCapabilityChanged = !cityChanged &&
                nextCity?.capabilities?.vehiclePositions != previousCity?.capabilities?.vehiclePositions,
            geometryCapabilityChanged = !cityChanged &&
                nextCity?.capabilities?.routeGeometry != previousCity?.capabilities?.routeGeometry,
            arrivalsCapabilityChanged = !cityChanged &&
                nextCity?.capabilities?.arrivals != previousCity?.capabilities?.arrivals,
            vehicleSelectionChanged = selectedIds != lastSelectedRouteIds || projection != previousProjection,
        )
        lastSelectedRouteIds = selectedIds.toSet()
        return change
    }
}
