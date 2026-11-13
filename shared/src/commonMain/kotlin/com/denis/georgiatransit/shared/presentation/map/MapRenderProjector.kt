package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlinx.collections.immutable.persistentListOf

/** Builds one map-frame projection from already reconciled feature snapshots. */
internal class MapRenderProjector(
    private val camera: MapCameraController,
    private val selection: MapStopSelection,
    private val vehicles: MapVehiclePresentation,
    private val bffStyleUrl: String?,
) {
    fun project(
        city: TransitCity, fix: UserLocationFix?, viewportZoom: Double?,
        stops: MapStopsProjector, geometry: RouteGeometrySnapshot,
    ): MapRenderState {
        val command = camera.forCity(city, fix)
        return MapRenderState(
            camera = command,
            bffStyleUrl = bffStyleUrl,
            stops = stops.visibleMarkers(fix?.point, viewportZoom ?: command.zoom),
            stopClusters = persistentListOf(),
            vehicles = vehicles.vehicleMarkers,
            polylines = geometry.polylines,
            polylineSourceRevision = geometry.polylineSourceRevision,
            userLocation = fix,
            stopSourceRevision = selection.sourceRevision,
            vehicleSourceRevision = vehicles.vehicleFrameRevision,
            vehicleBadgeRevision = vehicles.vehicleBadgeRevision,
            zoomStep = camera.zoomStep,
        )
    }
}
