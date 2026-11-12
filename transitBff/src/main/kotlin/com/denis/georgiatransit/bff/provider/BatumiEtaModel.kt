package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

internal const val EtaMaxProjectionMeters = 150.0
internal const val EtaPassedStopToleranceMeters = 150.0
internal const val EtaDwellSeconds = 60.0
/** Passenger-board horizon: farther candidates are deliberately omitted rather than guessed. */
internal const val EtaMaximumSeconds = 45 * 60L
internal data class PolylineProjection(val alongMeters: Double, val distanceMeters: Double)
internal val BatumiMovingKmhByRawRoute = mapOf(
    "60acde9ffcc7a224160c587c" to 23.7,
    "648994879327c728200b3f52" to 22.7,
    "5ed2bd4f657784b5a98a8c7e" to 24.2,
    "5ed6077c340f60873ff9e1be" to 25.1,
    "5ed60865340f60873ff9e1bf" to 23.0,
    "67ae3607e37f0ecf8032068f" to 21.1,
    "5f9f0704eb714303efce5631" to 32.2,
    "6740347dd04d3a04c35d84e4" to 23.6,
    "67402e01d04d3a04c35d84c2" to 23.0,
    "5ed60aa5340f60873ff9e1c4" to 26.2,
    "5ed60b25340f60873ff9e1c5" to 26.5,
    "64901c96e25b40c6e2150b34" to 29.6,
    "5ed60be3340f60873ff9e1c6" to 31.8,
    "6941c100f141b1861c3958a0" to 22.5,
    "5ed60d70340f60873ff9e1c8" to 26.5,
    "648996c69327c728200b3f63" to 23.8,
    "615182e95dc186cad86e0fd2" to 21.5,
    "5ed60e97340f60873ff9e1cb" to 24.4,
    "5ed60f32340f60873ff9e1cc" to 28.6,
    "675bf5b960b00f8db1965a52" to 23.8,
    "66de91d980df11324f155751" to 33.5,
    "69429301f141b1861c395bec" to 26.9,
    "67d92ac719ba93b977b83df4" to 30.5,
    "67d9522119ba93b977b83e6f" to 27.5,
    "6724af35cabac5486baeb625" to 33.7,
)
internal const val BatumiFallbackMovingKmh = 25.1
internal fun projectOntoPolyline(points: List<GeoPoint>, target: GeoPoint): PolylineProjection? {
    if (points.size < 2) return null
    val latScale = 111_320.0
    val lonScale = 111_320.0 * cos(target.latitude * PI / 180.0)
    var total = 0.0
    var best: PolylineProjection? = null
    points.zipWithNext().forEach { (a, b) ->
        val ax = (a.longitude - target.longitude) * lonScale; val ay = (a.latitude - target.latitude) * latScale
        val bx = (b.longitude - target.longitude) * lonScale; val by = (b.latitude - target.latitude) * latScale
        val dx = bx - ax; val dy = by - ay; val segment = hypot(dx, dy)
        if (segment > 0.01) {
            val t = ((-ax * dx - ay * dy) / (segment * segment)).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            val candidate = PolylineProjection(total + t * segment, distance)
            if (best?.distanceMeters == null || candidate.distanceMeters < best.distanceMeters) best = candidate
            total += segment
        }
    }
    return best
}

internal fun chainMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = (b.latitude - a.latitude) * PI / 180.0; val lon = (b.longitude - a.longitude) * PI / 180.0
    val sinLat = kotlin.math.sin(lat / 2); val sinLon = kotlin.math.sin(lon / 2)
    val h = sinLat * sinLat + cos(a.latitude * PI / 180.0) * cos(b.latitude * PI / 180.0) * sinLon * sinLon
    return 12_742_000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

internal fun projectVehicle(
    catalog: BatumiCatalog,
    routeId: String,
    cycle: RouteCycle,
    live: BatumiLiveVehicle,
): PolylineProjection? {
    if (projectOntoPolyline(catalog.geometry(routeId), live.vehicle.position)?.distanceMeters?.let { it > EtaMaxProjectionMeters } != false) {
        return null
    }
    val status = live.status ?: cycle.statuses.singleOrNull() ?: return null
    if (status < 0) return null
    var best: PolylineProjection? = null
    cycle.chain.indices.forEach { index ->
        val start = cycle.chain[index]
        val end = cycle.chain[(index + 1) % cycle.chain.size]
        if (start.status != status || end.status != status) return@forEach
        val candidate = projectSegment(
            start.stop.position,
            end.stop.position,
            live.vehicle.position,
            start.cumulativeMeters,
        )
        if (best == null || candidate.distanceMeters < requireNotNull(best).distanceMeters) best = candidate
    }
    return best?.takeIf { it.distanceMeters <= EtaMaxProjectionMeters }
}

internal fun projectSegment(
    start: GeoPoint,
    end: GeoPoint,
    target: GeoPoint,
    cumulativeMeters: Double,
): PolylineProjection {
    val latScale = 110_540.0
    val lonScale = 111_320.0 * cos(target.latitude * PI / 180.0)
    val ax = (start.longitude - target.longitude) * lonScale
    val ay = (start.latitude - target.latitude) * latScale
    val bx = (end.longitude - target.longitude) * lonScale
    val by = (end.latitude - target.latitude) * latScale
    val dx = bx - ax
    val dy = by - ay
    val segment = hypot(dx, dy)
    if (segment <= 0.01) return PolylineProjection(cumulativeMeters, hypot(ax, ay))
    val ratio = ((-ax * dx - ay * dy) / (segment * segment)).coerceIn(0.0, 1.0)
    return PolylineProjection(
        cumulativeMeters + ratio * chainMeters(start, end),
        hypot(ax + ratio * dx, ay + ratio * dy),
    )
}

internal fun forwardCycleDistance(from: Double, to: Double, total: Double): Double {
    val direct = to - from
    return when {
        direct < -EtaPassedStopToleranceMeters -> direct + total
        direct < 0.0 -> 0.0
        else -> direct
    }.coerceIn(0.0, total)
}

/** ADR 0012 moving-time plus intermediate-stop dwell; shared by the board and the journey planner. */
internal fun stopChainSeconds(distance: Double, metersPerSecond: Double, dwellStops: Int): Double =
    distance / metersPerSecond + dwellStops * EtaDwellSeconds

internal fun etaMinutes(distance: Double, metersPerSecond: Double, dwellStops: Int): Int? {
    val seconds = stopChainSeconds(distance, metersPerSecond, dwellStops)
    if (!seconds.isFinite() || seconds > EtaMaximumSeconds) return null
    return kotlin.math.round(seconds / 60.0).toInt().coerceAtLeast(1)
}
