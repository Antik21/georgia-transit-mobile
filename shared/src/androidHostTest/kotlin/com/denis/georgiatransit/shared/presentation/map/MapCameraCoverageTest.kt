package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.MapBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.VisibleRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCameraCoverageTest {
    private val area = MapBounds(west = 40.0, south = 41.0, east = 42.0, north = 43.0)

    @Test
    fun minimumZoomKeepsTheWholeViewportInsideCoverage() {
        val coverage = camera(42.0, 41.0, 9.0).constrainToCoverage(
            region(west = 38.0, south = 39.0, east = 44.0, north = 45.0),
            area,
            minZoom = 0.0,
            maxZoom = 22.0,
        )
        assertTrue(coverage.minimumZoom > 10.5)
        assertEquals(coverage.minimumZoom, assertNotNull(coverage.correctedCamera).zoom)
    }

    @Test
    fun cameraKeepsItsZoomWhenMovedBackFromAnEdge() {
        val coverage = camera(42.0, 41.9, 13.0).constrainToCoverage(
            region(west = 41.6, south = 41.8, east = 42.2, north = 42.2),
            area,
            minZoom = 0.0,
            maxZoom = 22.0,
        )
        val corrected = assertNotNull(coverage.correctedCamera)
        assertEquals(13.0, corrected.zoom)
        assertEquals(41.7, assertNotNull(corrected.target).longitude, absoluteTolerance = 0.00001)
        val inside = camera(42.0, 41.7, 13.0).constrainToCoverage(
            region(west = 41.4, south = 41.8, east = 42.0, north = 42.2),
            area,
            minZoom = 0.0,
            maxZoom = 22.0,
        )
        assertNull(inside.correctedCamera)
    }

    private fun camera(lat: Double, lon: Double, zoom: Double) =
        CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()

    private fun region(west: Double, south: Double, east: Double, north: Double) = VisibleRegion(
        LatLng(north, west),
        LatLng(north, east),
        LatLng(south, west),
        LatLng(south, east),
        LatLngBounds.from(north, east, south, west),
    )
}
