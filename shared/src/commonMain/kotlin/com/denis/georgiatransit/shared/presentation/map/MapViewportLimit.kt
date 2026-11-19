package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.MapBounds
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Limits the whole projected screen, including heading and any native camera padding. */
internal class MapViewportLimit(coverage: MapBounds) {
    private val west = coverage.west
    private val east = coverage.east
    private val south = mercator(coverage.south)
    private val north = mercator(coverage.north)

    fun evaluate(
        center: GeoPoint,
        zoom: Double,
        corners: List<GeoPoint>,
        minZoom: Double = 0.0,
        maxZoom: Double = 22.0,
    ): LimitedMapCamera? {
        if (!zoom.isFinite() || corners.isEmpty() || corners.any { !it.isMapCoordinate() }) return null
        val lon = center.longitude
        val lat = mercator(center.latitude)
        val windowWest = corners.minOf(GeoPoint::longitude) - lon
        val windowEast = corners.maxOf(GeoPoint::longitude) - lon
        val windowSouth = corners.minOf { mercator(it.latitude) } - lat
        val windowNorth = corners.maxOf { mercator(it.latitude) } - lat
        val scale = maxOf(
            (windowEast - windowWest) / (east - west),
            (windowNorth - windowSouth) / (north - south),
        )
        if (!scale.isFinite() || scale <= 0.0) return null

        val minimumZoom = (zoom + log2(scale)).coerceIn(minZoom, maxZoom)
        val clampedZoom = zoom.coerceAtLeast(minimumZoom)
        val factor = 2.0.pow(zoom - clampedZoom)
        val minLon = west - windowWest * factor
        val maxLon = (east - windowEast * factor).coerceAtLeast(minLon)
        val minLat = south - windowSouth * factor
        val maxLat = (north - windowNorth * factor).coerceAtLeast(minLat)
        return LimitedMapCamera(
            minimumZoom = minimumZoom,
            zoom = clampedZoom,
            center = GeoPoint(latitude(lat.coerceIn(minLat, maxLat)), lon.coerceIn(minLon, maxLon)),
            centerBounds = MapCameraCenterBounds(minLon, latitude(minLat), maxLon, latitude(maxLat)),
        )
    }
}

internal data class LimitedMapCamera(
    val minimumZoom: Double,
    val zoom: Double,
    val center: GeoPoint,
    val centerBounds: MapCameraCenterBounds,
)

/** A viewport that fits exactly can have only one allowed center on an axis. */
internal data class MapCameraCenterBounds(val west: Double, val south: Double, val east: Double, val north: Double)

private fun mercator(latitude: Double): Double = asinh(tan(latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180))
private fun latitude(mercator: Double): Double = atan(sinh(mercator)) * 180 / PI
