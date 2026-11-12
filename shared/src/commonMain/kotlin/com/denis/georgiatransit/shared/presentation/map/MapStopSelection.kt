package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitStop

/** Validates native source revisions and owns the selected stop's camera interaction. */
internal class MapStopSelection(private val camera: MapCameraController) {
    var selectedStopId: StopId? = null
        private set
    var sourceRevision = 0L
        private set

    fun nextSourceRevision(): Long {
        sourceRevision = if (sourceRevision == Long.MAX_VALUE) 1L else sourceRevision + 1L
        return sourceRevision
    }

    fun clear() {
        selectedStopId = null
    }

    fun select(
        stop: TransitStop?,
        eventRevision: Long,
        requireVisibleMarker: Boolean,
        visibleStops: List<MapStopMarker>?,
        city: TransitCity,
        viewportZoom: Double?,
    ): Boolean {
        if (eventRevision != sourceRevision || stop == null) return false
        if (requireVisibleMarker && visibleStops?.none { it.id == stop.id } != false) return false
        selectedStopId = stop.id
        camera.insets = MapViewportInsets.StopArrivalsSheet
        camera.next(stop.position, viewportZoom ?: camera.command?.zoom ?: city.defaultZoom)
        nextSourceRevision()
        return true
    }

    /** Null unless [stopId] is a visible group pin; a stale group tap keeps the current camera. */
    fun groupTap(stopId: StopId, eventRevision: Long, render: MapRenderState?): MapCameraCommand? {
        val group = render?.stops?.firstOrNull { it.id == stopId && it.representsGroup } ?: return null
        if (eventRevision != sourceRevision || eventRevision != render.stopSourceRevision) return render.camera
        return camera.next(group.position, ROUTE_STOP_GROUP_TAP_ZOOM, animated = true)
    }

    fun dismiss() {
        val previousCamera = camera.command
        selectedStopId = null
        camera.insets = MapViewportInsets.None
        if (previousCamera != null) {
            camera.next(previousCamera.center, previousCamera.zoom, MapViewportInsets.None)
        }
        nextSourceRevision()
    }

    fun updateInsets(
        insets: MapViewportInsets,
        stop: TransitStop?,
        city: TransitCity,
        viewportZoom: Double?,
    ): Boolean {
        val normalized = with(MapViewportPolicy) { insets.normalized() }
        if (normalized == camera.insets || stop == null) return false
        camera.insets = normalized
        camera.next(stop.position, viewportZoom ?: camera.command?.zoom ?: city.defaultZoom)
        return true
    }
}
