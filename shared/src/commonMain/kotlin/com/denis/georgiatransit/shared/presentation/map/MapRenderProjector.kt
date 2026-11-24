package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlinx.collections.immutable.persistentListOf

/** Builds one map-frame projection from already reconciled feature snapshots. */
internal class MapRenderProjector(
    private val camera: MapCameraController,
    private val selection: MapStopSelection,
    private val vehicles: MapVehiclePresentation,
) {
    fun project(
        city: TransitCity, fix: UserLocationFix?, viewportZoom: Double?, styleUrl: String?,
        stops: MapStopsProjector, geometry: RouteGeometrySnapshot, routeFrame: MapRouteFrameData,
    ): MapRenderState {
        camera.forCity(city, fix)
        camera.frameRoutesIfReady(routeFrame)
        val command = checkNotNull(camera.command)
        return MapRenderState(
            camera = command,
            bffStyleUrl = styleUrl,
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
            cameraBounds = city.mapBounds,
        )
    }
}
