package com.denis.georgiatransit.shared.presentation.map

import android.graphics.PointF
import android.view.View
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener { notifyViewportSettled() }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        latestCommand()?.let { command ->
            map?.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, viewportBottomPadding(command)))
        }
    }

    init {
        mapView.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun attach(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        mapLibreMap.addOnCameraIdleListener(cameraIdleListener)
    }

    /** A new style must re-apply the current command even when its revision is unchanged. */
    fun reset() {
        lastAppliedCameraRevision = null
        lastAppliedCameraCommand = null
    }

    fun destroy() {
        map?.removeOnCameraIdleListener(cameraIdleListener)
        mapView.removeOnLayoutChangeListener(layoutChangeListener)
        map = null
        reset()
    }

    fun applyIfNeeded(command: MapCameraCommand) {
        if (lastAppliedCameraRevision == command.revision) return
        if (!command.center.isMapCoordinate() || !command.zoom.isFinite()) return
        if (mapView.height <= 0) {
            mapView.post { latestCommand()?.let(::applyIfNeeded) }
            return
        }
        val currentMap = map ?: return
        val target = LatLng(command.center.latitude, command.center.longitude)
        val previousCommand = lastAppliedCameraCommand
        val preservedScreenPoint = previousCommand
            ?.takeIf { previous -> !command.animated && shouldPreserveScreenAnchor(previous, command) }
            ?.let { currentMap.projection.toScreenLocation(target) }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(target)
                .zoom(command.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
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
                        .zoom(command.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
                        .padding(0.0, 0.0, 0.0, viewportBottomPadding(command))
                        .build(),
                ),
            )
        }
        lastAppliedCameraRevision = command.revision
        lastAppliedCameraCommand = command
        if (!command.animated) mapView.post { notifyViewportSettled() }
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
        val target = currentMap.cameraPosition.target ?: return
        val center = GeoPoint(target.latitude, target.longitude)
        val region = currentMap.projection.visibleRegion
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
