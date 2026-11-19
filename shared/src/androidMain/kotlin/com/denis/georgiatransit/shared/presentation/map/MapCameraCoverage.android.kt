package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs

internal fun CameraPosition.constrainToCoverage(
    corners: List<LatLng>,
    coverage: MapViewportLimit,
    minZoom: Double,
    maxZoom: Double,
): CameraCoverage? {
    val center = target?.let { GeoPoint(it.latitude, it.longitude) } ?: return null
    val fit = coverage.evaluate(center, zoom, corners.map { GeoPoint(it.latitude, it.longitude) }, minZoom, maxZoom)
        ?: return null
    val camera = if (abs(fit.zoom - zoom) > 0.001 ||
        abs(fit.center.latitude - center.latitude) > 0.000001 ||
        abs(fit.center.longitude - center.longitude) > 0.000001
    ) {
        CameraPosition.Builder(this)
            .target(LatLng(fit.center.latitude, fit.center.longitude))
            .zoom(fit.zoom)
            .build()
    } else {
        null
    }
    return CameraCoverage(fit.minimumZoom, fit.centerBounds, camera)
}

internal data class CameraCoverage(
    val minimumZoom: Double,
    val centerBounds: MapCameraCenterBounds,
    val correctedCamera: CameraPosition?,
)
