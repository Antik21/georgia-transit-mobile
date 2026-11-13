package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix

/** Entry-local camera command state; only explicit commands advance its revision. */
internal class MapCameraController {
    private var lastCityId: CityId? = null
    private var lastAcceptedFix: UserLocationFix? = null
    private var nextRevision = 0L
    var command: MapCameraCommand? = null
        private set
    var insets = MapViewportInsets.None
    var zoomStep: MapZoomStep? = null
        private set

    fun reset() {
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

    fun zoomBy(delta: Double): MapZoomStep =
        MapZoomStep(totalDelta = (zoomStep?.totalDelta ?: 0.0) + delta).also { zoomStep = it }

    fun next(
        center: GeoPoint,
        zoom: Double,
        viewportInsets: MapViewportInsets = insets,
        animated: Boolean = false,
    ): MapCameraCommand =
        MapCameraCommand(
            center = center,
            zoom = zoom,
            revision = ++nextRevision,
            viewportInsets = viewportInsets,
            animated = animated,
        ).also { command = it }
}

/** Zoom buttons change only the published step; the camera command and its revision stay as they are. */
internal fun ViewState.withZoomStep(step: MapZoomStep): ViewState = copy(renderState = renderState?.copy(zoomStep = step))
