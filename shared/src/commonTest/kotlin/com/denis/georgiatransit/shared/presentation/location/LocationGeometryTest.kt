package com.denis.georgiatransit.shared.presentation.location

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationGeometryTest {
    @Test
    fun accuracyPolygonIsClosedFiniteAndMatchesRequestedRadius() {
        val fix = fix(point = GeoPoint(41.7151, 44.8271), accuracyMeters = 5_000.0)

        val polygon = accuracyPolygon(fix)

        assertEquals(65, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        polygon.dropLast(1).forEach { point ->
            assertTrue(point.latitude.isFinite())
            assertTrue(point.latitude in -90.0..90.0)
            assertTrue(point.longitude.isFinite())
            assertTrue(point.longitude in -180.0..<180.0)
            assertEquals(fix.accuracyMeters, distanceMeters(fix.point, point), absoluteTolerance = 0.01)
        }
    }

    @Test
    fun accuracyPolygonNormalizesLongitudesAcrossAntimeridian() {
        val polygon = accuracyPolygon(
            fix(point = GeoPoint(0.0, 179.999), accuracyMeters = 5_000.0),
            segmentCount = 16,
        )

        assertEquals(17, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        assertTrue(polygon.all { it.longitude in -180.0..<180.0 })
        assertTrue(polygon.any { it.longitude < 0.0 })
        assertTrue(polygon.any { it.longitude > 0.0 })
    }

    private fun fix(point: GeoPoint, accuracyMeters: Double) = UserLocationFix(
        point = point,
        accuracyMeters = accuracyMeters,
        precision = LocationPrecision.Approximate,
        capturedAtEpochMillis = 1L,
        expiresAtEpochMillis = 2L,
    )

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val latitudeDelta = (second.latitude - first.latitude).toRadians()
        val longitudeDelta = (second.longitude - first.longitude).toRadians()
        val startLatitude = first.latitude.toRadians()
        val endLatitude = second.latitude.toRadians()
        val haversine = sin(latitudeDelta / 2.0).let { it * it } +
            cos(startLatitude) * cos(endLatitude) *
            sin(longitudeDelta / 2.0).let { it * it }
        return 2.0 * WGS84_MEAN_RADIUS_METERS * asin(sqrt(haversine))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val WGS84_MEAN_RADIUS_METERS = 6_371_008.8
    }
}
