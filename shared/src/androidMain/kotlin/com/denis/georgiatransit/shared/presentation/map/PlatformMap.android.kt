package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre remains entirely inside this Android adapter. Connectivity stays disabled unless the
 * common render contract supplies a validated, same-BFF style URL.
 */
@Composable
actual fun PlatformMap(
    renderState: MapRenderState,
    onEvent: (MapPlatformEvent) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context.applicationContext, lifecycleOwner) {
        MapLibre.getInstance(context.applicationContext)
        MapLibre.setConnected(false)
        MapView(context).also { it.onCreate(null) }
    }
    val currentOnEvent = rememberUpdatedState(onEvent)
    val controller = remember(mapView) { LocalMapController(mapView) { currentOnEvent.value(it) } }
    val lifecycle = remember(mapView, controller) { MapViewLifecycle(mapView, controller::setLifecycleResumed) }

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

/** Wires MapLibre callbacks to the per-responsibility renderers and keeps them in step with style reloads. */
private class LocalMapController(
    private val mapView: MapView,
    private val onEvent: (MapPlatformEvent) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var latestState: MapRenderState? = null
    private var layersInstalled = false
    private var destroyed = false
    private val camera = MapCameraAdapter(mapView, latestCommand = { latestState?.camera }, onEvent = onEvent)
    private val overlaySources = MapOverlaySources()
    private val vehicleBadges = VehicleBadgeLayer(mapView.resources)
    private val attentionPulse = VehicleAttentionPulse(mapView) { styleLoader.style }
    private val styleLoader = MapStyleLoader(
        mapView = mapView,
        onStyleReset = ::clearRenderedLayerState,
        onStyleAccepted = ::onStyleAccepted,
    )
    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener {
        vehicleBadges.updateLabelOrientation(styleLoader.style, map?.cameraPosition?.bearing)
    }
    private val mapClickListener = MapLibreMap.OnMapClickListener { point ->
        val event = map?.hitTestEntity(point, mapView.resources.displayMetrics.density)
        event?.let(onEvent)
        event != null
    }

    init {
        mapView.getMapAsync { mapLibreMap ->
            if (destroyed) return@getMapAsync
            // ADR 0015 forbids speculative requests while the BFF fronts public OSM tiles.
            mapLibreMap.setPrefetchZoomDelta(0)
            map = mapLibreMap
            camera.attach(mapLibreMap)
            mapLibreMap.addOnCameraMoveListener(cameraMoveListener)
            mapLibreMap.addOnMapClickListener(mapClickListener)
            styleLoader.attach(mapLibreMap)
        }
    }

    fun update(renderState: MapRenderState) {
        if (destroyed) return
        latestState = renderState
        styleLoader.request(renderState.bffStyleUrl)
        styleLoader.style?.let { installAndRender(it, renderState) }
    }

    fun setLifecycleResumed(resumed: Boolean) {
        attentionPulse.setLifecycleResumed(resumed)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        map?.removeOnCameraMoveListener(cameraMoveListener)
        map?.removeOnMapClickListener(mapClickListener)
        camera.destroy()
        styleLoader.destroy()
        map = null
        latestState = null
        attentionPulse.destroy()
        clearRenderedLayerState()
    }

    private fun onStyleAccepted(loadedStyle: Style) {
        latestState?.let { state ->
            if (styleLoader.requestedBffStyleUrl != state.bffStyleUrl) {
                styleLoader.request(state.bffStyleUrl)
            } else {
                installAndRender(loadedStyle, state)
            }
        }
    }

    private fun installAndRender(loadedStyle: Style, renderState: MapRenderState) {
        if (!layersInstalled) {
            installSourcesAndLayers(
                loadedStyle,
                vehicleLayer = vehicleBadges.layer(map?.cameraPosition?.bearing ?: 0.0),
                attentionLayer = attentionPulse.layer(),
            )
            layersInstalled = true
        }
        overlaySources.render(loadedStyle, renderState)
        vehicleBadges.render(loadedStyle, renderState)
        attentionPulse.render(loadedStyle, renderState)
        camera.applyIfNeeded(renderState.camera)
        attentionPulse.refresh()
    }

    /** Every base-style load drops all sources, layers and images, so renderer caches must restart too. */
    private fun clearRenderedLayerState() {
        layersInstalled = false
        camera.reset()
        overlaySources.clear()
        vehicleBadges.clear()
        attentionPulse.clear()
    }
}
