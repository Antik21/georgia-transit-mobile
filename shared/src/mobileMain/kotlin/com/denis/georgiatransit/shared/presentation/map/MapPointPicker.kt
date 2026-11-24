package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.denis.georgiatransit.shared.domain.model.GeoPoint

internal data class MapPickTarget(
    val mapWidthPx: Int,
    val mapHeightPx: Int,
    val yFraction: Double,
    val markerScale: Float,
) {
    val xFraction: Double get() = 0.5
}

internal fun mapPickTarget(
    mapWidthPx: Int,
    mapHeightPx: Int,
    topOverlayHeightPx: Int,
    bottomOverlayHeightPx: Int,
    markerAboveTipPx: Float,
    markerBelowTipPx: Float,
): MapPickTarget? {
    if (mapWidthPx <= 0 || topOverlayHeightPx <= 0 || bottomOverlayHeightPx <= 0) return null
    val visibleBottom = (mapHeightPx - bottomOverlayHeightPx).toDouble()
    val availableHeight = visibleBottom - topOverlayHeightPx
    if (availableHeight <= 0) return null

    val scale = minOf(1.0, availableHeight / (markerAboveTipPx + markerBelowTipPx))
    val earliestTip = topOverlayHeightPx + markerAboveTipPx * scale
    val latestTip = visibleBottom - markerBelowTipPx * scale
    val tipY = (visibleBottom / 2).coerceAtLeast(earliestTip).coerceAtMost(latestTip)
    return MapPickTarget(mapWidthPx, mapHeightPx, tipY / mapHeightPx, scale.toFloat())
}

/** Projects the same anchor the picker draws, against the live map instead of a saved camera centre. */
@Stable
internal class MapPointPicker {
    var request by mutableStateOf<MapPointRequest?>(null)
        private set
    var cameraMoving by mutableStateOf(false)
        private set

    private var sequence = 0L
    private var pending: PendingPick? = null

    fun canConfirm(target: MapPickTarget?): Boolean = target != null && !cameraMoving && request == null

    fun start(side: JourneyPlanSide, target: MapPickTarget?) {
        if (target == null || !canConfirm(target)) return
        pending = PendingPick(side, target)
        request = MapPointRequest(++sequence, xFraction = target.xFraction, yFraction = target.yFraction)
    }

    fun resolve(event: MapPlatformEvent.MapPointResolved, side: JourneyPlanSide?, target: MapPickTarget?): GeoPoint? {
        if (event.requestId != request?.id) return null
        val point = event.point?.takeIf {
            val expected = pending
            expected != null && side == expected.side && target == expected.target &&
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
        pending = null
    }

    private data class PendingPick(val side: JourneyPlanSide, val target: MapPickTarget)
}
