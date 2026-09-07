package com.denis.georgiatransit.shared.presentation.location

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Closed WGS84-coordinate ring whose surface distance from the fix is its reported accuracy. */
internal fun accuracyPolygon(fix: UserLocationFix, segmentCount: Int = 64): List<GeoPoint> {
    val latitude = fix.point.latitude.toRadians()
    val longitude = fix.point.longitude.toRadians()
    val angularDistance = fix.accuracyMeters / WGS84_MEAN_RADIUS_METERS
    val points = (0 until segmentCount).map { index ->
        val bearing = 2.0 * PI * index / segmentCount
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        GeoPoint(
            latitude = destinationLatitude.toDegrees(),
            longitude = normalizeLongitude(destinationLongitude.toDegrees()),
        )
    }
    return points + points.first()
}

private fun Double.toRadians(): Double = this * PI / 180.0
private fun Double.toDegrees(): Double = this * 180.0 / PI
private fun normalizeLongitude(longitude: Double): Double = ((longitude + 540.0) % 360.0) - 180.0

private const val WGS84_MEAN_RADIUS_METERS = 6_371_008.8
