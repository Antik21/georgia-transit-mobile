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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The MapLibre Android SDK is confined to this adapter. Connectivity is disabled for the local
 * prototype so neither its embedded style nor its synthetic GeoJSON can fall back to a network
 * source.
 */
@Composable
actual fun PlatformMap(viewport: MapViewport, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context.applicationContext, lifecycleOwner) {
        MapLibre.getInstance(context.applicationContext)
        MapLibre.setConnected(false)
        MapView(context).also { it.onCreate(null) }
    }
    val lifecycle = remember(mapView) { MapViewLifecycle(mapView) }
    val controller = remember(mapView) { LocalMapController(mapView) }

    DisposableEffect(lifecycleOwner, lifecycle) {
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
        onRelease = {
            lifecycle.destroy()
            controller.destroy()
        },
        update = { controller.update(viewport) },
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
    private var latestViewport: MapViewport? = null
    private var prototypeInstalled = false
    private var destroyed = false

    init {
        mapView.getMapAsync { mapLibreMap ->
            if (destroyed) return@getMapAsync
            map = mapLibreMap
            mapLibreMap.setStyle(Style.Builder().fromJson(LOCAL_STYLE_JSON)) styleLoaded@{ loadedStyle ->
                if (destroyed) return@styleLoaded
                style = loadedStyle
                latestViewport?.let(::installPrototype)
            }
        }
    }

    fun update(viewport: MapViewport) {
        latestViewport = viewport
        if (style != null) installPrototype(viewport)
    }

    fun destroy() {
        destroyed = true
        map = null
        style = null
        latestViewport = null
        prototypeInstalled = false
    }

    private fun installPrototype(viewport: MapViewport) {
        if (prototypeInstalled) {
            applyViewport(viewport)
            return
        }
        val loadedStyle = style ?: return
        loadedStyle.addSource(GeoJsonSource(MARKER_SOURCE_ID, markerFeature(viewport)))
        loadedStyle.addSource(GeoJsonSource(LINE_SOURCE_ID, lineFeature(viewport)))
        loadedStyle.addLayer(
            LineLayer(LINE_LAYER_ID, LINE_SOURCE_ID).withProperties(
                lineColor("#2A9D8F"),
                lineOpacity(0.9f),
                lineWidth(5f),
            ),
        )
        loadedStyle.addLayer(
            CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                circleColor("#E76F51"),
                circleRadius(8f),
                circleStrokeColor("#264653"),
                circleStrokeWidth(2f),
            ),
        )
        prototypeInstalled = true
        applyViewport(viewport)
    }

    private fun applyViewport(viewport: MapViewport) {
        if (destroyed) return
        val loadedStyle = style ?: return
        val mapLibreMap = map ?: return
        loadedStyle.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)?.setGeoJson(markerFeature(viewport))
        loadedStyle.getSourceAs<GeoJsonSource>(LINE_SOURCE_ID)?.setGeoJson(lineFeature(viewport))
        mapLibreMap.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(viewport.center.latitude, viewport.center.longitude),
                viewport.zoom,
            ),
        )
    }
}

private fun markerFeature(viewport: MapViewport): Feature = Feature.fromGeometry(
    Point.fromLngLat(viewport.center.longitude, viewport.center.latitude),
)

private fun lineFeature(viewport: MapViewport): Feature {
    val center = viewport.center
    return Feature.fromGeometry(
        LineString.fromLngLats(
            listOf(
                Point.fromLngLat(center.longitude - 0.015, center.latitude - 0.010),
                Point.fromLngLat(center.longitude, center.latitude),
                Point.fromLngLat(center.longitude + 0.020, center.latitude + 0.008),
            ),
        ),
    )
}

private const val MARKER_SOURCE_ID = "local-center-marker"
private const val LINE_SOURCE_ID = "local-preview-line"
private const val MARKER_LAYER_ID = "local-center-marker-layer"
private const val LINE_LAYER_ID = "local-preview-line-layer"

/** A deliberately asset-free, local-only MapLibre style. */
private const val LOCAL_STYLE_JSON = """
    {
      "version": 8,
      "name": "Georgia Transit local prototype",
      "sources": {},
      "layers": [
        {
          "id": "local-background",
          "type": "background",
          "paint": { "background-color": "#E7F1EB" }
        }
      ]
    }
"""
