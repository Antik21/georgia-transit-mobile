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
    private val zoomSteps = MapZoomStepTracker(MIN_ZOOM, MAX_ZOOM)
    private val coverage = MapCoverageConstraint(mapView)
    private var coverageUpdatePosted = false
    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener { scheduleCoverageUpdate() }
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
        scheduleCoverageUpdate()
        notifyViewportSettled()
    }
    private val cameraMoveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        val gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
        if (gesture) zoomSteps.cameraMovedElsewhere()
        onEvent(if (gesture) MapPlatformEvent.UserCameraMoveStarted else MapPlatformEvent.CameraMoveStarted)
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        latestCommand()?.let(::applyViewportInsets)
        scheduleCoverageUpdate()
    }

    init {
        mapView.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun attach(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        mapLibreMap.addOnCameraIdleListener(cameraIdleListener)
        mapLibreMap.addOnCameraMoveListener(cameraMoveListener)
        mapLibreMap.addOnCameraMoveStartedListener(cameraMoveStartedListener)
    }

    fun applyBounds(bounds: MapBounds?) {
        map?.let { coverage.update(it, bounds) }
    }

    /** A new style must re-apply the current command even when its revision is unchanged. */
    fun reset() {
        lastAppliedCameraRevision = null
        lastAppliedCameraCommand = null
        lastBottomPadding = null
    }

    fun destroy() {
        map?.removeOnCameraIdleListener(cameraIdleListener)
        map?.removeOnCameraMoveListener(cameraMoveListener)
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
        if (mapView.width <= 0 || mapView.height <= 0) {
            mapView.post { latestCommand()?.let(::applyIfNeeded) }
            return
        }
        val currentMap = map ?: return
        coverage.prepareCameraMove(currentMap)
        command.routeBounds?.let { bounds ->
            if (!fitRoute(currentMap, bounds, command)) return
            coverage.constrain(currentMap)
            recordApplied(command)
            return
        }
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
        coverage.constrain(currentMap)
        recordApplied(command)
    }

    private fun fitRoute(map: MapLibreMap, bounds: MapRouteBounds, command: MapCameraCommand): Boolean {
        val area = LatLngBounds.Builder()
            .include(LatLng(bounds.southwest.latitude, bounds.southwest.longitude))
            .include(LatLng(bounds.northeast.latitude, bounds.northeast.longitude))
            .build()
        val padding = (ROUTE_FRAME_MARGIN_DP * mapView.resources.displayMetrics.density).toInt()
            .coerceAtMost(minOf(mapView.width, mapView.height) / 4)
        val camera = map.getCameraForLatLngBounds(
            area,
            intArrayOf(padding, padding, padding, padding + viewportBottomPadding(command).toInt()),
        ) ?: return false
        map.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(camera).zoom(camera.zoom.coerceAtMost(MAX_ROUTE_FRAME_ZOOM)).build(),
        ))
        return true
    }

    private fun recordApplied(command: MapCameraCommand) {
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
        val readyZoom = map?.takeIf { lastAppliedCameraRevision != null }?.cameraPosition?.zoom ?: Double.NaN
        val next = zoomSteps.targetZoom(step, readyZoom) ?: return
        val current = map ?: return
        val zoom = next.coerceAtLeast(current.minZoomLevel)
        if (zoom != next) zoomSteps.cameraMovedElsewhere()
        if (zoom == readyZoom) return
        val update = CameraUpdateFactory.zoomTo(zoom)
        if (coverage.isActive) {
            current.moveCamera(update)
            coverage.constrain(current)
        } else {
            current.animateCamera(update, MapZoomStepTracker.ANIMATION_MILLIS)
        }
    }

    private fun scheduleCoverageUpdate() {
        if (!coverage.isActive || lastAppliedCameraRevision == null || coverageUpdatePosted) return
        coverageUpdatePosted = true
        // Camera changes made here must run outside the SDK's camera callback.
        mapView.postOnAnimation {
            coverageUpdatePosted = false
            map?.let(coverage::constrain)
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
private const val ROUTE_FRAME_MARGIN_DP = 52
private const val MAX_ROUTE_FRAME_ZOOM = 16.0
private const val MAX_BOTTOM_OCCLUSION_FRACTION = 0.75
private const val EARTH_RADIUS_METERS = 6_371_008.8
