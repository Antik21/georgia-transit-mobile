package com.denis.georgiatransit.shared.presentation.map

import android.graphics.PointF
import android.view.View
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.MapBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Applies common camera commands to MapLibre and reports the settled viewport back to common code. */
internal class MapCameraAdapter(
    private val mapView: MapView,
    private val latestCommand: () -> MapCameraCommand?,
    private val onEvent: (MapPlatformEvent) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var lastAppliedCameraRevision: Long? = null
    private var lastAppliedCameraCommand: MapCameraCommand? = null
    private var lastBottomPadding: Double? = null
    private var coverageBounds: MapBounds? = null
    private var defaultMinZoom = MIN_ZOOM
    private val zoomSteps = MapZoomStepTracker(MIN_ZOOM, MAX_ZOOM)
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener { notifyViewportSettled() }
    private val cameraMoveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) zoomSteps.cameraMovedElsewhere()
        onEvent(MapPlatformEvent.CameraMoveStarted)
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        latestCommand()?.let { command ->
            applyViewportInsets(command)
        }
    }

    init {
        mapView.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun attach(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        defaultMinZoom = mapLibreMap.minZoomLevel
        mapLibreMap.addOnCameraIdleListener(cameraIdleListener)
        mapLibreMap.addOnCameraMoveStartedListener(cameraMoveStartedListener)
    }

    fun applyBounds(bounds: MapBounds?) {
        val currentMap = map ?: return
        if (bounds == coverageBounds) return
        coverageBounds = bounds
        currentMap.setLatLngBoundsForCameraTarget(
            bounds?.let { LatLngBounds.from(it.north, it.east, it.south, it.west) },
        )
        if (bounds == null) currentMap.setMinZoomPreference(defaultMinZoom)
    }

    /** A new style must re-apply the current command even when its revision is unchanged. */
    fun reset() {
        lastAppliedCameraRevision = null
        lastAppliedCameraCommand = null
        lastBottomPadding = null
    }

    fun destroy() {
        map?.removeOnCameraIdleListener(cameraIdleListener)
        map?.removeOnCameraMoveStartedListener(cameraMoveStartedListener)
        mapView.removeOnLayoutChangeListener(layoutChangeListener)
        map = null
        reset()
    }

    fun applyIfNeeded(command: MapCameraCommand) {
        if (lastAppliedCameraRevision == command.revision) {
            applyViewportInsets(command)
            return
        }
        if (!command.center.isMapCoordinate() || !command.zoom.isFinite()) return
        if (mapView.height <= 0) {
            mapView.post { latestCommand()?.let(::applyIfNeeded) }
            return
        }
        val currentMap = map ?: return
        val target = LatLng(command.center.latitude, command.center.longitude)
        val zoom = command.zoom.coerceIn(currentMap.minZoomLevel, MAX_ZOOM)
        val previousCommand = lastAppliedCameraCommand
        val preservedScreenPoint = previousCommand
            ?.takeIf { previous -> !command.animated && shouldPreserveScreenAnchor(previous, command) }
            ?.let { currentMap.projection.toScreenLocation(target) }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(target)
                .zoom(zoom)
                .padding(0.0, 0.0, 0.0, viewportBottomPadding(command))
                .build(),
        )
        if (command.animated) currentMap.animateCamera(update) else currentMap.moveCamera(update)
        preservedScreenPoint?.let { originalPoint ->
            val centeredPoint = currentMap.projection.toScreenLocation(target)
            val compensatedCenter = currentMap.projection.fromScreenLocation(
                PointF(
                    centeredPoint.x * 2f - originalPoint.x,
                    centeredPoint.y * 2f - originalPoint.y,
                ),
            )
            currentMap.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(compensatedCenter)
                        .zoom(zoom)
                        .padding(0.0, 0.0, 0.0, viewportBottomPadding(command))
                        .build(),
                ),
            )
        }
        lastAppliedCameraRevision = command.revision
        lastAppliedCameraCommand = command
        lastBottomPadding = viewportBottomPadding(command)
        zoomSteps.cameraMovedElsewhere()
        if (!command.animated) mapView.post { notifyViewportSettled() }
    }

    /** Change padding around the live camera; never reapply an old command after a user pan. */
    private fun applyViewportInsets(command: MapCameraCommand) {
        val currentMap = map ?: return
        if (mapView.height <= 0 || lastAppliedCameraRevision == null) return
        val bottom = viewportBottomPadding(command)
        if (bottom == lastBottomPadding) return
        lastBottomPadding = bottom
        lastAppliedCameraCommand = command
        currentMap.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, bottom))
    }

    /**
     * Called for every render state, even before getMapAsync completes, or the first tap becomes the baseline.
     * Taps wait until the initial camera command is applied, which would otherwise overwrite their zoom.
     */
    fun applyZoomStep(step: MapZoomStep?) {
        val currentMap = map
        val readyZoom = currentMap?.takeIf { lastAppliedCameraRevision != null }?.cameraPosition?.zoom ?: Double.NaN
        val requestedZoom = zoomSteps.targetZoom(step, readyZoom) ?: return
        if (currentMap == null) return
        val targetZoom = requestedZoom.coerceAtLeast(currentMap.minZoomLevel)
        if (targetZoom != requestedZoom) zoomSteps.cameraMovedElsewhere()
        if (targetZoom != readyZoom) {
            currentMap.animateCamera(CameraUpdateFactory.zoomTo(targetZoom), MapZoomStepTracker.ANIMATION_MILLIS)
        }
    }

    private fun shouldPreserveScreenAnchor(previous: MapCameraCommand, current: MapCameraCommand): Boolean =
        previous.viewportInsets.bottomOcclusionFraction > 0.0 &&
            current.viewportInsets.bottomOcclusionFraction == 0.0 &&
            previous.center == current.center &&
            previous.zoom == current.zoom

    private fun viewportBottomPadding(command: MapCameraCommand): Double {
        val fraction = command.viewportInsets.bottomOcclusionFraction
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, MAX_BOTTOM_OCCLUSION_FRACTION)
            ?: 0.0
        return mapView.height * fraction
    }

    private fun notifyViewportSettled() {
        val currentMap = map ?: return
        val position = currentMap.cameraPosition
        val target = position.target ?: return
        val region = currentMap.projection.visibleRegion
        coverageBounds?.let { bounds ->
            val coverage = position.constrainToCoverage(region, bounds, defaultMinZoom, currentMap.maxZoomLevel)
            if (abs(currentMap.minZoomLevel - coverage.minimumZoom) > 0.001) {
                currentMap.setMinZoomPreference(coverage.minimumZoom)
            }
            coverage.correctedCamera?.let { corrected ->
                currentMap.moveCamera(CameraUpdateFactory.newCameraPosition(corrected))
                return
            }
        }
        val center = GeoPoint(target.latitude, target.longitude)
        val radius = listOfNotNull(region.farLeft, region.farRight, region.nearLeft, region.nearRight)
            .maxOfOrNull { corner -> center.distanceMetersTo(GeoPoint(corner.latitude, corner.longitude)) }
            ?.takeIf(Double::isFinite)
            ?: return
        onEvent(
            MapPlatformEvent.ViewportSettled(
                MapViewport(
                    center = center,
                    radiusMeters = ceil(radius).toInt().coerceAtLeast(1),
                    zoom = currentMap.cameraPosition.zoom,
                ),
            ),
        )
    }
}

private fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val latitudeDelta = Math.toRadians(other.latitude - latitude)
    val longitudeDelta = Math.toRadians(other.longitude - longitude)
    val firstLatitude = Math.toRadians(latitude)
    val secondLatitude = Math.toRadians(other.latitude)
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0).let { it * it }
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private const val MIN_ZOOM = 0.0
private const val MAX_ZOOM = 22.0
private const val MAX_BOTTOM_OCCLUSION_FRACTION = 0.75
private const val EARTH_RADIUS_METERS = 6_371_008.8
