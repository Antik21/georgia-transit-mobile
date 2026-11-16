package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.denis.georgiatransit.shared.domain.model.GeoPoint

/** Resolves the marker on demand instead of retaining a possibly stale camera center. */
@Stable
internal class MapPointPicker {
    var request by mutableStateOf<MapPointRequest?>(null)
        private set
    var cameraMoving by mutableStateOf(false)
        private set

    private var sequence = 0L
    private var pendingSide: JourneyPlanSide? = null
    private var pendingMapHeight = 0
    private var pendingPanelHeight = 0

    fun canConfirm(mapHeight: Int, panelHeight: Int): Boolean =
        mapHeight > 0 && panelHeight in 1 until mapHeight && !cameraMoving && request == null

    fun start(side: JourneyPlanSide, mapHeight: Int, panelHeight: Int) {
        if (!canConfirm(mapHeight, panelHeight)) return
        pendingSide = side
        pendingMapHeight = mapHeight
        pendingPanelHeight = panelHeight
        request = MapPointRequest(++sequence, xFraction = 0.5, yFraction = (mapHeight - panelHeight) / (2.0 * mapHeight))
    }

    fun resolve(event: MapPlatformEvent.MapPointResolved, side: JourneyPlanSide?, mapHeight: Int, panelHeight: Int): GeoPoint? {
        if (event.requestId != request?.id) return null
        val point = event.point?.takeIf {
            side != null && side == pendingSide && mapHeight == pendingMapHeight && panelHeight == pendingPanelHeight &&
                !cameraMoving && it.isMapCoordinate()
        }
        cancel()
        return point
    }

    fun motionStarted() {
        cameraMoving = true
        cancel()
    }

    fun motionSettled() { cameraMoving = false }

    fun cancel() {
        request = null
        pendingSide = null
    }
}
