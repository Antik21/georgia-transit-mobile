@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.presentation.map.MapVehicleMarker
import com.denis.georgiatransit.shared.presentation.map.VehicleLayerState
import com.denis.georgiatransit.shared.presentation.map.VehicleRouteAccessibilityUi

@JsExport
class VehicleItem(
    val id: String,
    val routeId: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Double?,
    val positionKind: String,
    val label: String,
    val color: String,
    val textColor: String,
    val stale: Boolean,
)

/** [revision] changes on every animation frame; [badgeRevision] only when badge artwork can change. */
@JsExport
class VehicleLayer(
    val revision: String,
    val badgeRevision: String,
    val vehicles: Array<VehicleItem>,
)

@JsExport
class VehicleRouteSummary(val routeId: String, val label: String, val vehicleCount: Int, val status: String)

internal fun VehicleRouteAccessibilityUi.toJs() = VehicleRouteSummary(
    routeId = routeId.value,
    label = routeLabel,
    vehicleCount = vehicleCount,
    status = layerState.toJs(),
)

internal fun MapVehicleMarker.toJs() = VehicleItem(
    id = id.value,
    routeId = routeId.value,
    latitude = position.latitude,
    longitude = position.longitude,
    bearing = bearingDegrees?.takeIf(Double::isFinite),
    positionKind = when (positionKind) {
        VehiclePositionKind.Gps -> "gps"
        VehiclePositionKind.Estimated -> "estimated"
        VehiclePositionKind.Unknown -> "unknown"
    },
    label = routeLabel,
    color = routeColorArgb.toCssColor(),
    textColor = routeTextColorArgb.toCssColor(),
    stale = isStale,
)

internal fun VehicleLayerState.toJs(): String = when (this) {
    VehicleLayerState.Hidden -> "hidden"
    VehicleLayerState.Loading -> "loading"
    VehicleLayerState.Live -> "live"
    VehicleLayerState.Stale -> "stale"
    VehicleLayerState.Retryable -> "retryable"
    VehicleLayerState.Unavailable -> "unavailable"
    VehicleLayerState.Mixed -> "mixed"
}
