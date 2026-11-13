@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.presentation.map.MapPolyline
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendState
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendUi
import com.denis.georgiatransit.shared.presentation.map.StopRouteBadgeUi

@JsExport
class RoutePoint(val latitude: Double, val longitude: Double)

@JsExport
class RoutePolyline(
    val routeId: String,
    val directionId: String?,
    val points: Array<RoutePoint>,
    val color: String,
    val strokeWidth: Double,
    val opacity: Double,
    val isEmphasized: Boolean,
)

@JsExport
class RouteLegend(
    val routeId: String,
    val label: String,
    val color: String,
    val textColor: String,
    val status: String,
    val focused: Boolean,
    val canRetry: Boolean,
)

@JsExport
class StopRouteBadge(
    val routeId: String,
    val label: String,
    val color: String,
    val textColor: String,
    val selected: Boolean,
)

internal fun StopRouteBadgeUi.toJs() = StopRouteBadge(
    routeId = routeId.value,
    label = routeLabel,
    color = backgroundArgb.toCssColor(),
    textColor = textArgb.toCssColor(),
    selected = isSelected,
)

internal fun MapPolyline.toJs() = RoutePolyline(
    routeId = routeId.value,
    directionId = directionId?.value,
    points = points.map { RoutePoint(it.latitude, it.longitude) }.toTypedArray(),
    color = routeColorArgb.toCssColor(),
    strokeWidth = strokeWidth,
    opacity = opacity,
    isEmphasized = isEmphasized,
)

internal fun RouteGeometryLegendUi.toJs() = RouteLegend(
    routeId = routeId.value,
    label = routeLabel,
    color = colorArgb.toCssColor(),
    textColor = textColorArgb.toCssColor(),
    status = when (state) {
        RouteGeometryLegendState.Loading -> "loading"
        RouteGeometryLegendState.Ready -> "ready"
        RouteGeometryLegendState.Partial -> "partial"
        RouteGeometryLegendState.Retryable -> "retryable"
        RouteGeometryLegendState.Unavailable -> "unavailable"
        RouteGeometryLegendState.SelectionOverflow -> "selectionOverflow"
    },
    focused = isFocused,
    canRetry = canRetry,
)
