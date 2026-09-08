package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.location.accuracyPolygon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * MapLibre remains entirely inside this Android adapter. Connectivity is explicitly disabled:
 * without an approved BFF asset contract the local fallback style must fail closed.
 */
@Composable
actual fun PlatformMap(renderState: MapRenderState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context.applicationContext, lifecycleOwner) {
        MapLibre.getInstance(context.applicationContext)
        MapLibre.setConnected(false)
        MapView(context).also { it.onCreate(null) }
    }
    val lifecycle = remember(mapView) { MapViewLifecycle(mapView) }
    val controller = remember(mapView) { LocalMapController(mapView) }

    DisposableEffect(lifecycleOwner, lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> lifecycle.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        lifecycle.syncTo(lifecycleOwner.lifecycle.currentState)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycle.destroy()
            controller.destroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { controller.update(renderState) },
        onRelease = {
            lifecycle.destroy()
            controller.destroy()
        },
    )
}

private class MapViewLifecycle(private val mapView: MapView) {
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun syncTo(state: Lifecycle.State) {
        if (state == Lifecycle.State.DESTROYED) {
            destroy()
            return
        }
        if (state.isAtLeast(Lifecycle.State.STARTED)) start()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        pause()
        stop()
        mapView.onDestroy()
        destroyed = true
    }

    private fun start() {
        if (!destroyed && !started) {
            mapView.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!destroyed && !resumed) {
            start()
            mapView.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (!destroyed && resumed) {
            mapView.onPause()
            resumed = false
        }
    }

    private fun stop() {
        if (!destroyed && started) {
            pause()
            mapView.onStop()
            started = false
        }
    }
}

private class LocalMapController(mapView: MapView) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var latestState: MapRenderState? = null
    private var layersInstalled = false
    private var lastAppliedCameraRevision: Long? = null
    private var destroyed = false

    init {
        mapView.getMapAsync { mapLibreMap ->
            if (destroyed) return@getMapAsync
            map = mapLibreMap
            mapLibreMap.setStyle(Style.Builder().fromJson(LOCAL_STYLE_JSON)) styleLoaded@{ loadedStyle ->
                if (destroyed) return@styleLoaded
                style = loadedStyle
                layersInstalled = false
                lastAppliedCameraRevision = null
                latestState?.let(::installAndRender)
            }
        }
    }

    fun update(renderState: MapRenderState) {
        if (destroyed) return
        latestState = renderState
        if (style != null) installAndRender(renderState)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        map = null
        style = null
        latestState = null
        layersInstalled = false
        lastAppliedCameraRevision = null
    }

    private fun installAndRender(renderState: MapRenderState) {
        val loadedStyle = style ?: return
        if (!layersInstalled) {
            installSourcesAndLayers(loadedStyle)
            layersInstalled = true
        }
        updateSources(loadedStyle, renderState)
        applyCameraIfNeeded(renderState)
    }

    private fun installSourcesAndLayers(loadedStyle: Style) {
        loadedStyle.addSource(GeoJsonSource(STOPS_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(POLYLINES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(USER_ACCURACY_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))

        loadedStyle.addLayer(
            LineLayer(POLYLINES_LAYER_ID, POLYLINES_SOURCE_ID).withProperties(
                lineColor(Expression.get(ROUTE_COLOR_PROPERTY)),
                lineOpacity(0.9f),
                lineWidth(5f),
            ),
        )
        loadedStyle.addLayer(
            FillLayer(USER_ACCURACY_FILL_LAYER_ID, USER_ACCURACY_SOURCE_ID).withProperties(
                fillColor("#1976D2"),
                fillOpacity(0.18f),
            ),
        )
        loadedStyle.addLayer(
            LineLayer(USER_ACCURACY_STROKE_LAYER_ID, USER_ACCURACY_SOURCE_ID).withProperties(
                lineColor("#0D47A1"),
                lineOpacity(0.75f),
                lineWidth(2f),
            ),
        )
        loadedStyle.addLayer(
            CircleLayer(STOPS_LAYER_ID, STOPS_SOURCE_ID).withProperties(
                circleColor("#2A9D8F"),
                circleRadius(5f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
        loadedStyle.addLayer(
            CircleLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                circleColor(Expression.get(ROUTE_COLOR_PROPERTY)),
                circleRadius(7f),
                circleStrokeColor("#263238"),
                circleStrokeWidth(2f),
            ),
        )
        loadedStyle.addLayer(
            CircleLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                circleColor("#1565C0"),
                circleRadius(7f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
    }

    /** Updates each stable, grouped source once per state; no native view is created per feature. */
    private fun updateSources(loadedStyle: Style, renderState: MapRenderState) {
        loadedStyle.getSourceAs<GeoJsonSource>(STOPS_SOURCE_ID)?.setGeoJson(stopFeatures(renderState.stops))
        loadedStyle.getSourceAs<GeoJsonSource>(VEHICLES_SOURCE_ID)?.setGeoJson(vehicleFeatures(renderState.vehicles))
        loadedStyle.getSourceAs<GeoJsonSource>(POLYLINES_SOURCE_ID)?.setGeoJson(polylineFeatures(renderState.polylines))
        loadedStyle.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.setGeoJson(userLocationFeatures(renderState.userLocation))
        loadedStyle.getSourceAs<GeoJsonSource>(USER_ACCURACY_SOURCE_ID)?.setGeoJson(userAccuracyFeatures(renderState.userLocation))
    }

    private fun applyCameraIfNeeded(renderState: MapRenderState) {
        if (lastAppliedCameraRevision == renderState.camera.revision) return
        val command = renderState.camera
        if (!command.center.isMapCoordinate() || !command.zoom.isFinite()) return
        map?.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(command.center.latitude, command.center.longitude),
                command.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            ),
        )
        lastAppliedCameraRevision = command.revision
    }
}

private fun stopFeatures(stops: List<MapStopMarker>): FeatureCollection = FeatureCollection.fromFeatures(
    stops.asSequence()
        .filter { it.id.value.isNotBlank() && it.position.isMapCoordinate() }
        .sortedBy { it.id.value }
        .take(MAX_STOP_MARKERS)
        .map { marker ->
            Feature.fromGeometry(marker.position.asMapPoint()).also { it.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value) }
        }
        .toList(),
)

private fun vehicleFeatures(vehicles: List<MapVehicleMarker>): FeatureCollection = FeatureCollection.fromFeatures(
    vehicles.asSequence()
        .filter { it.id.value.isNotBlank() && it.routeId.value.isNotBlank() && it.position.isMapCoordinate() }
        .sortedBy { it.id.value }
        .take(MAX_VEHICLE_MARKERS)
        .map { marker ->
            Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                feature.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value)
                feature.addStringProperty(ROUTE_COLOR_PROPERTY, marker.routeColorArgb.asMapColor())
                feature.addStringProperty(POSITION_KIND_PROPERTY, marker.positionKind.name)
                marker.bearingDegrees?.takeIf(Double::isFinite)?.let { feature.addNumberProperty(BEARING_PROPERTY, it.normalizedBearing()) }
            }
        }
        .toList(),
)

private fun polylineFeatures(polylines: List<MapPolyline>): FeatureCollection = FeatureCollection.fromFeatures(
    polylines.asSequence()
        .filter { it.routeId.value.isNotBlank() }
        .sortedWith(compareBy<MapPolyline> { it.routeId.value }.thenBy { it.directionId?.value.orEmpty() })
        .take(MAX_POLYLINES)
        .mapNotNull { line ->
            val points = line.points.filter(GeoPoint::isMapCoordinate)
            points.takeIf(::isNonDegenerateLine)?.let { validPoints ->
                Feature.fromGeometry(LineString.fromLngLats(validPoints.map(GeoPoint::asMapPoint))).also { feature ->
                    feature.addStringProperty(FEATURE_ID_PROPERTY, line.routeId.value)
                    feature.addStringProperty(ROUTE_COLOR_PROPERTY, line.routeColorArgb.asMapColor())
                }
            }
        }
        .toList(),
)

private fun userLocationFeatures(location: UserLocationFix?): FeatureCollection = FeatureCollection.fromFeatures(
    location?.takeIf { it.precision == LocationPrecision.Precise && it.point.isMapCoordinate() }?.let {
        listOf(Feature.fromGeometry(it.point.asMapPoint()))
    }.orEmpty(),
)

private fun userAccuracyFeatures(location: UserLocationFix?): FeatureCollection = FeatureCollection.fromFeatures(
    location?.takeIf { it.point.isMapCoordinate() && it.accuracyMeters.isFinite() && it.accuracyMeters >= 0.0 }?.let { fix ->
        val ring = accuracyPolygon(fix).filter(GeoPoint::isMapCoordinate)
        if (ring.size >= MIN_POLYGON_POINTS) listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring.map(GeoPoint::asMapPoint))))) else emptyList()
    }.orEmpty(),
)

private fun GeoPoint.isMapCoordinate(): Boolean = latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun GeoPoint.asMapPoint(): Point = Point.fromLngLat(longitude, latitude)

private fun isNonDegenerateLine(points: List<GeoPoint>): Boolean = points.size >= 2 && points.zipWithNext().any { (first, second) -> first != second }

private fun Long.asMapColor(): String = "#%06X".format(this and 0xFFFFFF)

private fun Double.normalizedBearing(): Double = ((this % 360.0) + 360.0) % 360.0

private const val STOPS_SOURCE_ID = "gt-stops-source"
private const val VEHICLES_SOURCE_ID = "gt-vehicles-source"
private const val POLYLINES_SOURCE_ID = "gt-polylines-source"
private const val USER_LOCATION_SOURCE_ID = "gt-user-location-source"
private const val USER_ACCURACY_SOURCE_ID = "gt-user-accuracy-source"
private const val STOPS_LAYER_ID = "gt-stops-layer"
private const val VEHICLES_LAYER_ID = "gt-vehicles-layer"
private const val POLYLINES_LAYER_ID = "gt-polylines-layer"
private const val USER_LOCATION_LAYER_ID = "gt-user-location-layer"
private const val USER_ACCURACY_FILL_LAYER_ID = "gt-user-accuracy-fill-layer"
private const val USER_ACCURACY_STROKE_LAYER_ID = "gt-user-accuracy-stroke-layer"
private const val FEATURE_ID_PROPERTY = "featureId"
private const val ROUTE_COLOR_PROPERTY = "routeColor"
private const val BEARING_PROPERTY = "bearing"
private const val POSITION_KIND_PROPERTY = "positionKind"
private const val MAX_STOP_MARKERS = 1_000
private const val MAX_VEHICLE_MARKERS = 2_000
private const val MAX_POLYLINES = 256
private const val MIN_POLYGON_POINTS = 4
private const val MIN_ZOOM = 0.0
private const val MAX_ZOOM = 22.0

/** A deliberately asset-free, local-only MapLibre style. */
private const val LOCAL_STYLE_JSON = """
    {
      "version": 8,
      "name": "Georgia Transit local fallback",
      "sources": {},
      "layers": [
        {
          "id": "gt-local-background",
          "type": "background",
          "paint": { "background-color": "#E7F1EB" }
        }
      ]
    }
"""
