package com.denis.georgiatransit.shared.presentation.map

import android.graphics.PointF
import com.denis.georgiatransit.shared.domain.model.MapBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.abs

/** MapLibre Android constrains the target. Inset that target area by the actual screen footprint. */
internal class MapCoverageConstraint(private val view: MapView) {
    private var coverage: MapBounds? = null
    private var limit: MapViewportLimit? = null
    private var targetConstraint: MapCameraCenterBounds? = null
    private var nativeMinimumZoom: Double? = null
    private var updating = false

    val isActive: Boolean get() = coverage != null

    fun update(map: MapLibreMap, coverage: MapBounds?) {
        if (this.coverage == coverage) return
        this.coverage = coverage
        limit = coverage?.let(::MapViewportLimit)
        if (coverage == null) {
            map.setLatLngBoundsForCameraTarget(null)
            nativeMinimumZoom?.let(map::setMinZoomPreference)
            nativeMinimumZoom = null
            targetConstraint = null
        } else {
            if (nativeMinimumZoom == null) nativeMinimumZoom = map.minZoomLevel
            prepareCameraMove(map)
        }
    }

    /** A programmatic fit can start at a different zoom from the previous viewport. */
    fun prepareCameraMove(map: MapLibreMap) {
        val area = coverage ?: return
        setBounds(map, MapCameraCenterBounds(area.west, area.south, area.east, area.north))
    }

    fun constrain(map: MapLibreMap) {
        val policy = limit ?: return
        if (updating || view.width <= 0 || view.height <= 0) return
        val camera = map.cameraPosition
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val projection = map.projection
        val corners = listOf(PointF(0f, 0f), PointF(width, 0f), PointF(width, height), PointF(0f, height))
            .map(projection::fromScreenLocation)
        val constrained = camera.constrainToCoverage(corners, policy, nativeMinimumZoom ?: 0.0, map.maxZoomLevel) ?: return
        updating = true
        try {
            if (abs(map.minZoomLevel - constrained.minimumZoom) > ZoomEpsilon) {
                map.setMinZoomPreference(constrained.minimumZoom)
            }
            setBounds(map, constrained.centerBounds)
            constrained.correctedCamera?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
        } finally {
            updating = false
        }
    }

    private fun setBounds(map: MapLibreMap, area: MapCameraCenterBounds) {
        val previous = targetConstraint
        if (previous != null && abs(previous.west - area.west) < CoordinateEpsilon &&
            abs(previous.east - area.east) < CoordinateEpsilon &&
            abs(previous.north - area.north) < CoordinateEpsilon &&
            abs(previous.south - area.south) < CoordinateEpsilon
        ) return
        targetConstraint = area
        map.setLatLngBoundsForCameraTarget(LatLngBounds.from(area.north, area.east, area.south, area.west))
    }

    private companion object {
        const val ZoomEpsilon = 0.001
        const val CoordinateEpsilon = 0.000001
    }
}
