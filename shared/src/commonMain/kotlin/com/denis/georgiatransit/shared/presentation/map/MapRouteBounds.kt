package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import kotlin.math.max
import kotlin.math.min

@Immutable
data class MapRouteBounds(val southwest: GeoPoint, val northeast: GeoPoint) {
    val center: GeoPoint get() = GeoPoint(
        (southwest.latitude + northeast.latitude) / 2,
        (southwest.longitude + northeast.longitude) / 2,
    )
}

private val KutaisiId = CityId("kutaisi")

internal val TransitCity.supportsRouteFraming: Boolean
    get() = id == KutaisiId && capabilities.routes && capabilities.routeGeometry && capabilities.stops

internal data class MapRouteFrameData(
    val city: TransitCity,
    val selectedIds: Set<RouteId>,
    val routes: List<TransitRoute>,
    val lines: List<MapPolyline>,
    val stops: Map<SelectedRouteStopKey, List<TransitStop>>,
    val visible: Boolean,
) {
    fun bounds(): MapRouteBounds? {
        if (!city.supportsRouteFraming || routes.mapTo(mutableSetOf(), TransitRoute::id) != selectedIds) return null
        val directions = routes.directionKeys().toSet()
        val linesByDirection = lines.mapNotNull { line ->
            line.directionId?.let { SelectedRouteStopKey(line.routeId, it) to line }
        }.toMap()
        if (directions.isEmpty() ||
            !linesByDirection.keys.containsAll(directions) || !stops.keys.containsAll(directions)) return null

        val points = directions.asSequence().flatMap { linesByDirection.getValue(it).points.asSequence() } +
            directions.asSequence().flatMap { key -> stops.getValue(key).asSequence().map(TransitStop::position) }
        var south = Double.POSITIVE_INFINITY
        var west = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        for (point in points.filter(GeoPoint::isMapCoordinate)) {
            south = min(south, point.latitude)
            west = min(west, point.longitude)
            north = max(north, point.latitude)
            east = max(east, point.longitude)
        }
        if (!south.isFinite() || south == north && west == east) return null
        return MapRouteBounds(GeoPoint(south, west), GeoPoint(north, east))
    }
}
