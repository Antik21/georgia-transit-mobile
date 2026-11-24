package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.MapBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapViewportLimitTest {
    private val coverage = MapBounds(42.57, 42.17, 42.77, 42.35)
    private val policy = MapViewportLimit(coverage)

    @Test
    fun shiftsRouteFrameOffTheSouthEdgeWithoutChangingItsZoom() {
        val proposed = GeoPoint(42.232, 42.662)
        val fullFrame = assertNotNull(policy.evaluate(proposed, 12.0, rectangle(42.155, 42.602, 42.308, 42.722)))

        assertEquals(12.0, fullFrame.zoom)
        assertEquals(proposed.longitude, fullFrame.center.longitude)
        assertTrue(fullFrame.center.latitude > proposed.latitude)
        assertEquals(fullFrame.centerBounds.south, fullFrame.center.latitude)
        assertTrue(fullFrame.center.latitude < 42.26)
    }

    @Test
    fun limitsZoomUsingTheTallerDimensionOfPortraitScreens() {
        val camera = GeoPoint(42.26, 42.67)
        val result = assertNotNull(policy.evaluate(camera, 10.0, rectangle(42.0, 42.55, 42.52, 42.79)))

        assertTrue(result.minimumZoom > 11.0)
        assertEquals(result.minimumZoom, result.zoom)
        assertEquals(result.centerBounds.south, result.centerBounds.north, 1e-8)
        assertTrue(result.centerBounds.west < result.centerBounds.east)
    }

    @Test
    fun offsetsCenterLimitsByTheEntireScreenAndPreservesAnInBoundsView() {
        val camera = GeoPoint(42.25, 42.67)
        val result = assertNotNull(policy.evaluate(camera, 14.0, rectangle(42.22, 42.64, 42.28, 42.70)))

        assertEquals(camera.latitude, result.center.latitude, 1e-8)
        assertEquals(camera.longitude, result.center.longitude, 1e-8)
        assertTrue(result.centerBounds.south > coverage.south && result.centerBounds.north < coverage.north)
        assertEquals(42.60, result.centerBounds.west, 1e-8)
        assertEquals(42.74, result.centerBounds.east, 1e-8)
    }

    private fun rectangle(south: Double, west: Double, north: Double, east: Double) =
        listOf(GeoPoint(south, west), GeoPoint(south, east), GeoPoint(north, west), GeoPoint(north, east))
}
