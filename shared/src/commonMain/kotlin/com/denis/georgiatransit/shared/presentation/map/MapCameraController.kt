package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix

/** Entry-local camera command state; only explicit commands advance its revision. */
internal class MapCameraController {
    private var lastCityId: CityId? = null
    private var lastAcceptedFix: UserLocationFix? = null
    private var nextRevision = 0L
    private var pendingRouteFrame: Set<RouteId> = emptySet()
    var routeBoundsSupported: Boolean = false
    var command: MapCameraCommand? = null
        private set
    var insets = MapViewportInsets.None
    var zoomStep: MapZoomStep? = null
        private set

    fun reset() {
        cancelRouteFrame()
        lastCityId = null
        command = null
        lastAcceptedFix = null
    }

    fun forCity(city: TransitCity, fix: UserLocationFix?): MapCameraCommand {
        val isCityChange = lastCityId != null && lastCityId != city.id
        if (lastCityId != city.id || command == null) {
            lastCityId = city.id
            next(city.center, city.defaultZoom)
            lastAcceptedFix = if (isCityChange) fix else null
        }
        if (fix == null) {
            lastAcceptedFix = null
        } else if (fix != lastAcceptedFix) {
            lastAcceptedFix = fix
            if (fix.recenterMap) next(fix.point, 15.0)
        }
        return checkNotNull(command)
    }

    fun zoomBy(delta: Double): MapZoomStep {
        cancelRouteFrame()
        return MapZoomStep(totalDelta = (zoomStep?.totalDelta ?: 0.0) + delta).also { zoomStep = it }
    }

    fun cancelRouteFrame() {
        pendingRouteFrame = emptySet()
    }

    fun onRouteSelection(city: TransitCity?, ids: Set<RouteId>, change: MapSessionChange, viewport: MapViewport?) {
        if (change.cityChanged || change.routeSelectionChanged) {
            cancelRouteFrame()
            command = command?.let { previous ->
                if (previous.routeBounds == null) previous else previous.copy(
                    center = viewport?.center ?: previous.center,
                    zoom = viewport?.zoom ?: previous.zoom,
                    routeBounds = null,
                )
            }
        }
        if (!routeBoundsSupported || city?.supportsRouteFraming != true) {
            cancelRouteFrame()
        } else if (change.routesAdded && !change.initial && !change.cityChanged) {
            pendingRouteFrame = ids.toSet()
        }
    }

    fun frameRoutesIfReady(data: MapRouteFrameData) {
        if (pendingRouteFrame.isEmpty() || pendingRouteFrame != data.selectedIds || !data.visible) return
        val bounds = data.bounds() ?: return
        next(bounds.center, command?.zoom ?: data.city.defaultZoom, routeBounds = bounds)
    }

    fun next(
        center: GeoPoint,
        zoom: Double,
        viewportInsets: MapViewportInsets = insets,
        animated: Boolean = false,
        routeBounds: MapRouteBounds? = null,
    ): MapCameraCommand {
        cancelRouteFrame()
        return MapCameraCommand(
            center = center,
            zoom = zoom,
            revision = ++nextRevision,
            viewportInsets = viewportInsets,
            animated = animated,
            routeBounds = routeBounds,
        ).also { command = it }
    }
}

/** Zoom buttons change only the published step; the camera command and its revision stay as they are. */
internal fun ViewState.withZoomStep(step: MapZoomStep): ViewState = copy(renderState = renderState?.copy(zoomStep = step))
