package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import kotlin.math.ceil
import kotlin.math.round

/** Shared map viewport and sheet-inset normalization, independent of request state. */
internal object MapViewportPolicy {
    fun initialViewport(city: TransitCity): MapViewport = MapViewport(
        center = city.center,
        radiusMeters = initialRadiusMeters(city.defaultZoom),
        zoom = city.defaultZoom,
    )

    fun MapViewport.normalized(): MapViewport = copy(
        center = GeoPoint(
            latitude = center.latitude.roundTo(VIEWPORT_COORDINATE_DECIMALS),
            longitude = center.longitude.roundTo(VIEWPORT_COORDINATE_DECIMALS),
        ),
        radiusMeters = (ceil(radiusMeters / VIEWPORT_RADIUS_STEP_METERS.toDouble()).toInt() *
            VIEWPORT_RADIUS_STEP_METERS).coerceIn(MIN_NEARBY_RADIUS_METERS, MAX_NEARBY_RADIUS_METERS),
        zoom = zoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM).roundTo(VIEWPORT_ZOOM_DECIMALS),
    )

    private fun initialRadiusMeters(zoom: Double): Int = when {
        zoom >= 16.0 -> 1_500
        zoom >= 14.0 -> 3_000
        zoom >= 12.0 -> 7_500
        zoom >= 10.0 -> 20_000
        else -> MAX_NEARBY_RADIUS_METERS
    }

    fun Double.roundTo(decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return round(this * factor) / factor
    }

    fun MapViewportInsets.normalized(): MapViewportInsets = MapViewportInsets(
        bottomOcclusionFraction = bottomOcclusionFraction
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, MAX_BOTTOM_OCCLUSION_FRACTION)
            ?: 0.0,
    )

    private const val VIEWPORT_COORDINATE_DECIMALS = 5
    private const val MIN_NEARBY_RADIUS_METERS = 1
    private const val MAX_NEARBY_RADIUS_METERS = 50_000
    private const val MIN_MAP_ZOOM = 0.0
    private const val MAX_MAP_ZOOM = 22.0
    private const val VIEWPORT_ZOOM_DECIMALS = 2
    private const val VIEWPORT_RADIUS_STEP_METERS = 25
    private const val MAX_BOTTOM_OCCLUSION_FRACTION = 0.75
}
