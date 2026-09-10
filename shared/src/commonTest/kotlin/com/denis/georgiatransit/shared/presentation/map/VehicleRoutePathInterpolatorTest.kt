package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleRoutePathInterpolatorTest {
    @Test
    fun `mid-frame follows an L-shaped route instead of cutting across the corner`() {
        val path = prepared(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )
        val motion = assertNotNull(
            VehicleRoutePathInterpolator.motion(
                from = GeoPoint(0.0, 0.0),
                to = GeoPoint(0.001, 0.001),
                directionId = directionId,
                candidates = listOf(path),
            ),
        )

        val midpoint = motion.positionAt(0.5)
        assertNear(0.0, midpoint.latitude)
        assertNear(0.001, midpoint.longitude)
        assertTrue(abs(midpoint.latitude - 0.0005) > 0.0004, "the marker must not use a straight diagonal")
    }

    @Test
    fun `loop movement crosses the route seam without travelling around the whole loop`() {
        val path = prepared(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
            GeoPoint(0.001, 0.0),
            GeoPoint(0.0, 0.0),
        )
        val motion = assertNotNull(
            VehicleRoutePathInterpolator.motion(
                from = GeoPoint(0.0002, 0.0),
                to = GeoPoint(0.0, 0.0002),
                directionId = directionId,
                candidates = listOf(path),
            ),
        )

        val midpoint = motion.positionAt(0.5)
        assertNear(0.0, midpoint.latitude, tolerance = 0.00002)
        assertNear(0.0, midpoint.longitude, tolerance = 0.00002)
        assertTrue(abs(motion.deltaMeters) < 60.0)
    }

    @Test
    fun `far GPS points do not get falsely snapped to a route`() {
        val path = prepared(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

        assertNull(
            VehicleRoutePathInterpolator.motion(
                from = GeoPoint(0.01, 0.0),
                to = GeoPoint(0.01, 0.001),
                directionId = directionId,
                candidates = listOf(path),
            ),
        )
    }

    @Test
    fun `overlapping outbound and return segments still produce a route motion`() {
        val path = outAndBackPath()

        val motion = assertNotNull(
            VehicleRoutePathInterpolator.motion(
                from = GeoPoint(0.00001, 0.0005),
                to = GeoPoint(0.00001, 0.001),
                directionId = directionId,
                candidates = listOf(path),
            ),
        )

        assertTrue(abs(motion.deltaMeters) in 50.0..65.0)
        val midpoint = motion.positionAt(0.5)
        assertNear(0.00001, midpoint.latitude, tolerance = 0.00002)
        assertNear(0.00075, midpoint.longitude, tolerance = 0.00002)
    }

    @Test
    fun `previous route progress keeps an ambiguous vehicle on the same branch`() {
        val path = outAndBackPath()
        val previous = VehicleRouteMotion(
            path = path,
            startAlongMeters = 10.0,
            deltaMeters = 45.0,
            score = 0.0,
        )

        val motion = assertNotNull(
            VehicleRoutePathInterpolator.motion(
                from = GeoPoint(0.00001, 0.0005),
                to = GeoPoint(0.00001, 0.001),
                directionId = directionId,
                candidates = listOf(path),
                previousMotion = previous,
            ),
        )

        assertTrue(motion.startAlongMeters < 100.0)
        assertTrue(motion.deltaMeters > 0.0)
    }

    private fun prepared(vararg points: GeoPoint): PreparedVehicleRoutePath = assertNotNull(
        PreparedVehicleRoutePath.from(
            MapPolyline(
                routeId = routeId,
                directionId = directionId,
                points = points.toList(),
                routeColorArgb = 0xFF0000,
                freshness = TransitFreshness.Network,
            ),
        ),
    )

    private fun outAndBackPath(): PreparedVehicleRoutePath = prepared(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 0.002),
        GeoPoint(0.00002, 0.002),
        GeoPoint(0.00002, 0.0),
    )

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 0.00001) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, actual $actual")
    }

    private companion object {
        val routeId = RouteId("route")
        val directionId = DirectionId("outbound")
    }
}
