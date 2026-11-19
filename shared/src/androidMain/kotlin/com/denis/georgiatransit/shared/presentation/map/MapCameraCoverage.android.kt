package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.MapBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.VisibleRegion
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** MapLibre Android constrains the target. Account for the viewport around it at the settled zoom. */
internal fun CameraPosition.constrainToCoverage(
    visible: VisibleRegion,
    coverage: MapBounds,
    minZoom: Double,
    maxZoom: Double,
): CameraCoverage {
    val center = target ?: return CameraCoverage(minZoom)
    val corners = listOfNotNull(visible.farLeft, visible.farRight, visible.nearLeft, visible.nearRight)
    if (corners.size != 4) return CameraCoverage(minZoom)
    val longitude = center.longitude
    val latitude = mercatorY(center.latitude)
    val horizontal = projectedSpan(corners.map { it.longitude - longitude })
    val vertical = projectedSpan(corners.map { mercatorY(it.latitude) - latitude })
    val west = coverage.west
    val east = coverage.east
    val south = mercatorY(coverage.south)
    val north = mercatorY(coverage.north)
    val requiredScale = maxOf(horizontal.size / (east - west), vertical.size / (north - south))
    if (requiredScale <= 0.0 || !requiredScale.isFinite()) return CameraCoverage(minZoom)

    val minimum = (zoom + log2(requiredScale * CoverageInset)).coerceIn(minZoom, maxZoom)
    val fittedZoom = zoom.coerceAtLeast(minimum)
    val scale = 2.0.pow(fittedZoom - zoom)
    val fittedLongitude = horizontal.clampCenter(longitude, west, east, scale)
    val fittedLatitude = vertical.clampCenter(latitude, south, north, scale)
    if (fittedZoom - zoom <= ZoomTolerance &&
        abs(fittedLongitude - longitude) <= horizontal.size * PositionTolerance &&
        abs(fittedLatitude - latitude) <= vertical.size * PositionTolerance
    ) {
        return CameraCoverage(minimum)
    }
    val camera = CameraPosition.Builder(this)
        .target(LatLng(Math.toDegrees(atan(sinh(fittedLatitude))), fittedLongitude))
        .zoom(fittedZoom)
        .build()
    return CameraCoverage(minimum, camera)
}

internal data class CameraCoverage(val minimumZoom: Double, val correctedCamera: CameraPosition? = null)

private data class ProjectedSpan(val min: Double, val max: Double) {
    val size get() = max - min

    fun clampCenter(center: Double, lower: Double, upper: Double, scale: Double): Double {
        val first = lower - min / scale
        val last = upper - max / scale
        return if (first <= last) center.coerceIn(first, last) else (first + last) / 2.0
    }
}

private fun projectedSpan(values: List<Double>) = ProjectedSpan(values.min(), values.max())

private fun mercatorY(latitude: Double): Double = asinh(tan(Math.toRadians(latitude.coerceIn(-85.051129, 85.051129))))

private const val CoverageInset = 1.01
private const val PositionTolerance = 0.001
private const val ZoomTolerance = 0.001
