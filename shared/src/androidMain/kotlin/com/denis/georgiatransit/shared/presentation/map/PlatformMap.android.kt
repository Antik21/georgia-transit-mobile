package com.denis.georgiatransit.shared.presentation.map

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.location.accuracyPolygon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

private class MapViewLifecycle(
    private val mapView: MapView,
    private val onResumedChanged: (Boolean) -> Unit,
) {
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
        onResumedChanged(false)
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
            onResumedChanged(true)
        }
    }

    private fun pause() {
        if (!destroyed && resumed) {
            mapView.onPause()
            resumed = false
            onResumedChanged(false)
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

private class LocalMapController(
    private val mapView: MapView,
    private val onEvent: (MapPlatformEvent) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var requestedBffStyleUrl: String? = null
    private var styleRequestGeneration = 0L
    private var acceptedStyleGeneration = 0L
    private var loadingRemoteStyle = false
    private var latestState: MapRenderState? = null
    private var layersInstalled = false
    private var lastAppliedCameraRevision: Long? = null
    private var lastStops: List<MapStopMarker>? = null
    private var lastStopClusters: List<MapStopCluster>? = null
    private var lastStopSourceRevision: Long? = null
    private var lastPolylineSourceRevision: Long? = null
    private var lastUserLocation: UserLocationFix? = null
    private var lastVehicleSourceRevision: Long? = null
    private var lastVehicleBadgeRevision: Long? = null
    private var lastAttentionVehicleSourceRevision: Long? = null
    private val badgeImageIds = linkedMapOf<VehicleBadgeStyle, String>()
    private var nextBadgeImageIndex = 0L
    private var hasAttentionMarkers = false
    private var mapLifecycleResumed = false
    private var attentionTickerScheduled = false
    private val attentionTick = Runnable {
        attentionTickerScheduled = false
        if (shouldAnimateAttention()) {
            renderAttentionFrame(SystemClock.uptimeMillis())
            scheduleAttentionTick()
        } else {
            renderAttentionFrame(null)
        }
    }
    private var destroyed = false
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener { notifyViewportSettled() }
    private val mapClickListener = MapLibreMap.OnMapClickListener { point -> onMapClick(point) }
    private val styleLoadedListener = MapView.OnDidFinishLoadingStyleListener {
        val loadedStyle = map?.style ?: return@OnDidFinishLoadingStyleListener
        val uri = loadedStyle.uri.takeIf(String::isNotBlank)
        if (loadingRemoteStyle && uri != requestedBffStyleUrl) return@OnDidFinishLoadingStyleListener
        if (!loadingRemoteStyle && uri != null) return@OnDidFinishLoadingStyleListener
        acceptStyle(loadedStyle)
    }
    private val styleFailureListener = MapView.OnDidFailLoadingMapListener {
        if (destroyed || !loadingRemoteStyle) return@OnDidFailLoadingMapListener
        // Keep the last requested URL to avoid an update-frame retry loop; a recreated map can
        // attempt it again. Transit overlays are reinstalled on the local style below.
        MapLibre.setConnected(false)
        map?.let { loadStyle(it, Style.Builder().fromJson(LOCAL_STYLE_JSON), remote = false) }
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        latestState?.camera?.let { command ->
            map?.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, viewportBottomPadding(command)))
        }
    }

    init {
        mapView.addOnLayoutChangeListener(layoutChangeListener)
        mapView.addOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.addOnDidFailLoadingMapListener(styleFailureListener)
        mapView.getMapAsync { mapLibreMap ->
            if (destroyed) return@getMapAsync
            map = mapLibreMap
            mapLibreMap.addOnCameraIdleListener(cameraIdleListener)
            mapLibreMap.addOnMapClickListener(mapClickListener)
            loadStyle(mapLibreMap, Style.Builder().fromJson(LOCAL_STYLE_JSON), remote = false)
        }
    }

    fun update(renderState: MapRenderState) {
        if (destroyed) return
        latestState = renderState
        updateBaseStyle(renderState.bffStyleUrl)
        if (style != null) installAndRender(renderState)
    }

    fun setLifecycleResumed(resumed: Boolean) {
        if (mapLifecycleResumed == resumed) return
        mapLifecycleResumed = resumed
        refreshAttentionAnimation()
    }

    private fun updateBaseStyle(bffStyleUrl: String?) {
        if (requestedBffStyleUrl == bffStyleUrl || destroyed) return
        val mapLibreMap = map ?: return
        requestedBffStyleUrl = bffStyleUrl
        // The URL is derived by BffEndpointConfiguration after strict validation. No upstream
        // provider URL is ever accepted by this native adapter.
        MapLibre.setConnected(bffStyleUrl != null)
        val builder = bffStyleUrl?.let { Style.Builder().fromUri(it) } ?: Style.Builder().fromJson(LOCAL_STYLE_JSON)
        loadStyle(mapLibreMap, builder, remote = bffStyleUrl != null)
    }

    private fun loadStyle(mapLibreMap: MapLibreMap, builder: Style.Builder, remote: Boolean) {
        val generation = ++styleRequestGeneration
        loadingRemoteStyle = remote
        style = null
        layersInstalled = false
        lastAppliedCameraRevision = null
        clearRenderedLayerState()
        mapLibreMap.setStyle(builder) styleLoaded@{ loadedStyle ->
            if (destroyed || generation != styleRequestGeneration) return@styleLoaded
            acceptStyle(loadedStyle)
        }
    }

    private fun acceptStyle(loadedStyle: Style) {
        if (destroyed || acceptedStyleGeneration == styleRequestGeneration) return
        acceptedStyleGeneration = styleRequestGeneration
        loadingRemoteStyle = false
        style = loadedStyle
        latestState?.let { state ->
            if (requestedBffStyleUrl != state.bffStyleUrl) {
                updateBaseStyle(state.bffStyleUrl)
            } else {
                installAndRender(state)
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        map?.removeOnCameraIdleListener(cameraIdleListener)
        map?.removeOnMapClickListener(mapClickListener)
        mapView.removeOnLayoutChangeListener(layoutChangeListener)
        mapView.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.removeOnDidFailLoadingMapListener(styleFailureListener)
        map = null
        style = null
        styleRequestGeneration++
        acceptedStyleGeneration = styleRequestGeneration
        loadingRemoteStyle = false
        latestState = null
        stopAttentionTicker()
        layersInstalled = false
        lastAppliedCameraRevision = null
        clearRenderedLayerState()
    }

    private fun installAndRender(renderState: MapRenderState) {
        val loadedStyle = style ?: return
        if (!layersInstalled) {
            installSourcesAndLayers(loadedStyle)
            layersInstalled = true
        }
        updateSources(loadedStyle, renderState)
        applyCameraIfNeeded(renderState)
        refreshAttentionAnimation()
    }

    private fun installSourcesAndLayers(loadedStyle: Style) {
        loadedStyle.addSource(GeoJsonSource(STOPS_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(SELECTED_STOP_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(ATTENTION_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(POLYLINES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        loadedStyle.addSource(GeoJsonSource(USER_ACCURACY_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))

        loadedStyle.addLayer(
            LineLayer(POLYLINES_LAYER_ID, POLYLINES_SOURCE_ID).withProperties(
                lineColor(Expression.get(ROUTE_COLOR_PROPERTY)),
                lineOpacity(Expression.get(ROUTE_OPACITY_PROPERTY)),
                lineWidth(Expression.get(ROUTE_WIDTH_PROPERTY)),
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
                circleColor(Expression.get(MARKER_COLOR_PROPERTY)),
                circleRadius(Expression.get(MARKER_RADIUS_PROPERTY)),
                circleStrokeColor(Expression.get(MARKER_STROKE_COLOR_PROPERTY)),
                circleStrokeWidth(Expression.get(MARKER_STROKE_WIDTH_PROPERTY)),
            ),
        )
        loadedStyle.addLayer(
            SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                iconImage(Expression.get(VEHICLE_BADGE_IMAGE_PROPERTY)),
                iconOpacity(Expression.get(VEHICLE_OPACITY_PROPERTY)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        // The route-coloured pulse renders below the stable badge and is never hit-tested.
        loadedStyle.addLayerBelow(
            CircleLayer(ATTENTION_LAYER_ID, ATTENTION_SOURCE_ID).withProperties(
                circleColor(Expression.get(ATTENTION_COLOR_PROPERTY)),
                circleRadius(ATTENTION_MIN_RADIUS),
                circleOpacity(ATTENTION_MIN_SIZE_OPACITY),
            ),
            VEHICLES_LAYER_ID,
        )
        loadedStyle.addLayer(
            CircleLayer(SELECTED_STOP_LAYER_ID, SELECTED_STOP_SOURCE_ID).withProperties(
                circleColor(SELECTED_STOP_COLOR),
                circleRadius(SELECTED_STOP_RADIUS.toFloat()),
                circleStrokeColor("#FFFFFF"),
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

    /** Vehicle animation replaces only its grouped GeoJSON source; bitmap badge styles are cached separately. */
    private fun updateSources(loadedStyle: Style, renderState: MapRenderState) {
        if (
            lastStops != renderState.stops || lastStopClusters != renderState.stopClusters ||
            lastStopSourceRevision != renderState.stopSourceRevision
        ) {
            loadedStyle.getSourceAs<GeoJsonSource>(STOPS_SOURCE_ID)?.setGeoJson(ordinaryStopFeatures(renderState))
            loadedStyle.getSourceAs<GeoJsonSource>(SELECTED_STOP_SOURCE_ID)?.setGeoJson(selectedStopFeatures(renderState))
            lastStops = renderState.stops
            lastStopClusters = renderState.stopClusters
            lastStopSourceRevision = renderState.stopSourceRevision
        }
        if (lastVehicleSourceRevision != renderState.vehicleSourceRevision) {
            if (lastVehicleBadgeRevision != renderState.vehicleBadgeRevision) {
                updateBadgeImages(loadedStyle, renderState.vehicles.badgeRenderInput())
                lastVehicleBadgeRevision = renderState.vehicleBadgeRevision
            }
            loadedStyle.getSourceAs<GeoJsonSource>(VEHICLES_SOURCE_ID)?.setGeoJson(
                vehicleFeatures(renderState.vehicles, renderState.vehicleSourceRevision),
            )
            lastVehicleSourceRevision = renderState.vehicleSourceRevision
        }
        if (lastAttentionVehicleSourceRevision != renderState.vehicleSourceRevision) {
            val attentionMarkers = attentionMarkers(renderState.vehicles)
            loadedStyle.getSourceAs<GeoJsonSource>(ATTENTION_SOURCE_ID)?.setGeoJson(attentionFeatures(attentionMarkers))
            hasAttentionMarkers = attentionMarkers.isNotEmpty()
            lastAttentionVehicleSourceRevision = renderState.vehicleSourceRevision
        }
        if (lastPolylineSourceRevision != renderState.polylineSourceRevision) {
            loadedStyle.getSourceAs<GeoJsonSource>(POLYLINES_SOURCE_ID)?.setGeoJson(polylineFeatures(renderState.polylines))
            lastPolylineSourceRevision = renderState.polylineSourceRevision
        }
        if (lastUserLocation != renderState.userLocation) {
            loadedStyle.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.setGeoJson(userLocationFeatures(renderState.userLocation))
            loadedStyle.getSourceAs<GeoJsonSource>(USER_ACCURACY_SOURCE_ID)?.setGeoJson(userAccuracyFeatures(renderState.userLocation))
            lastUserLocation = renderState.userLocation
        }
    }

    private fun applyCameraIfNeeded(renderState: MapRenderState) {
        if (lastAppliedCameraRevision == renderState.camera.revision) return
        val command = renderState.camera
        if (!command.center.isMapCoordinate() || !command.zoom.isFinite()) return
        if (mapView.height <= 0) {
            mapView.post { latestState?.let(::applyCameraIfNeeded) }
            return
        }
        map?.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(command.center.latitude, command.center.longitude))
                    .zoom(command.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
                    .padding(0.0, 0.0, 0.0, viewportBottomPadding(command))
                    .build(),
            ),
        )
        lastAppliedCameraRevision = command.revision
        mapView.post { notifyViewportSettled() }
    }

    private fun viewportBottomPadding(command: MapCameraCommand): Double {
        val fraction = command.viewportInsets.bottomOcclusionFraction
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, MAX_BOTTOM_OCCLUSION_FRACTION)
            ?: 0.0
        return mapView.height * fraction
    }

    private fun notifyViewportSettled() {
        if (destroyed) return
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

    private fun onMapClick(point: LatLng): Boolean {
        val currentMap = map ?: return false
        val screenPoint = currentMap.projection.toScreenLocation(point)
        val halfTarget = MIN_STOP_TARGET_DP / 2f * mapView.resources.displayMetrics.density
        val hitRect = RectF(
            screenPoint.x - halfTarget,
            screenPoint.y - halfTarget,
            screenPoint.x + halfTarget,
            screenPoint.y + halfTarget,
        )
        findEntityFeature(currentMap, hitRect, SELECTED_STOP_LAYER_ID, FEATURE_KIND_STOP)?.let { feature ->
            onEvent(MapPlatformEvent.StopTapped(StopId(feature.first), feature.second))
            return true
        }
        findEntityFeature(currentMap, hitRect, STOPS_LAYER_ID, FEATURE_KIND_STOP)?.let { feature ->
            onEvent(MapPlatformEvent.StopTapped(StopId(feature.first), feature.second))
            return true
        }
        findEntityFeature(currentMap, hitRect, VEHICLES_LAYER_ID, FEATURE_KIND_VEHICLE)?.let { feature ->
            onEvent(MapPlatformEvent.VehicleTapped(VehicleId(feature.first), feature.second))
            return true
        }
        return false
    }

    private fun findEntityFeature(
        currentMap: MapLibreMap,
        hitRect: RectF,
        layerId: String,
        expectedKind: String,
    ): Pair<String, Long>? = currentMap.queryRenderedFeatures(hitRect, layerId)
        .asSequence()
        .filter { it.getStringProperty(FEATURE_KIND_PROPERTY) == expectedKind }
        .mapNotNull { feature ->
            val id = feature.getStringProperty(FEATURE_ID_PROPERTY)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val revision = feature.getStringProperty(SOURCE_REVISION_PROPERTY)?.toLongOrNull() ?: return@mapNotNull null
            id to revision
        }
        .firstOrNull()

    private fun vehicleFeatures(
        vehicles: List<MapVehicleMarker>,
        sourceRevision: Long,
    ): FeatureCollection = FeatureCollection.fromFeatures(
        vehicles.asSequence()
            .filter { it.id.value.isNotBlank() && it.routeId.value.isNotBlank() && it.position.isMapCoordinate() }
            .sortedBy { it.id.value }
            .take(MAX_VEHICLE_MARKERS)
            .map { marker ->
                Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                    feature.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value)
                    feature.addStringProperty(FEATURE_KIND_PROPERTY, FEATURE_KIND_VEHICLE)
                    feature.addStringProperty(SOURCE_REVISION_PROPERTY, sourceRevision.toString())
                    feature.addStringProperty(VEHICLE_BADGE_IMAGE_PROPERTY, badgeImageIds[marker.badgeStyle()] ?: OVERFLOW_BADGE_IMAGE_ID)
                    feature.addNumberProperty(VEHICLE_OPACITY_PROPERTY, if (marker.isStale) STALE_VEHICLE_OPACITY else 1.0)
                    feature.addStringProperty(POSITION_KIND_PROPERTY, marker.positionKind.name)
                    marker.bearingDegrees?.takeIf(Double::isFinite)?.let { feature.addNumberProperty(BEARING_PROPERTY, it.normalizedBearing()) }
                }
            }
            .toList(),
    )

    private fun updateBadgeImages(style: Style, renderInput: VehicleBadgeRenderInput) {
        val styles = renderInput.styles
        val obsolete = badgeImageIds.keys.filter { it !in styles }
        obsolete.forEach { badgeStyle ->
            badgeImageIds.remove(badgeStyle)?.let(style::removeImage)
        }
        styles.forEach { badgeStyle ->
            if (badgeStyle !in badgeImageIds) {
                val imageId = "gt-vehicle-badge-${++nextBadgeImageIndex}"
                style.addImage(imageId, badgeStyle.toBitmap())
                badgeImageIds[badgeStyle] = imageId
            }
        }
        if (renderInput.needsOverflow && badgeImageIds.values.none { it == OVERFLOW_BADGE_IMAGE_ID }) {
            style.addImage(OVERFLOW_BADGE_IMAGE_ID, VehicleBadgeStyle("?", 0xFF455A64, 0xFFFFFFFF, false).toBitmap())
        }
    }

    /** A deterministic bounded subset keeps the decoration cost independent of fleet size. */
    private fun attentionMarkers(vehicles: List<MapVehicleMarker>): List<MapVehicleMarker> = vehicles.asSequence()
        .filter {
            it.id.value.isNotBlank() && it.routeId.value.isNotBlank() && it.position.isMapCoordinate()
        }
        .sortedBy(MapVehicleMarker::stableId)
        .take(MAX_ATTENTION_MARKERS)
        .toList()

    private fun attentionFeatures(markers: List<MapVehicleMarker>): FeatureCollection = FeatureCollection.fromFeatures(
        markers.map { marker ->
            Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                val color = marker.routeColorArgb or 0xFF000000L
                feature.addStringProperty(ATTENTION_COLOR_PROPERTY, color.asMapColor())
            }
        },
    )

    private fun refreshAttentionAnimation() {
        if (destroyed || style == null) return
        if (!shouldAnimateAttention()) {
            stopAttentionTicker()
            renderAttentionFrame(null)
            return
        }
        renderAttentionFrame(SystemClock.uptimeMillis())
        scheduleAttentionTick()
    }

    /** Android's global animator scale is the accessible Reduce Motion signal for this adapter. */
    private fun shouldAnimateAttention(): Boolean = mapLifecycleResumed && ValueAnimator.areAnimatorsEnabled() &&
        ValueAnimator.getDurationScale() > 0f && hasAttentionMarkers

    private fun scheduleAttentionTick() {
        if (!attentionTickerScheduled && !destroyed && shouldAnimateAttention()) {
            attentionTickerScheduled = true
            mapView.postDelayed(attentionTick, ATTENTION_TICK_MILLIS)
        }
    }

    private fun stopAttentionTicker() {
        mapView.removeCallbacks(attentionTick)
        attentionTickerScheduled = false
    }

    /** Smooth cosine interpolation grows and shrinks the ring without a turn-around discontinuity. */
    private fun renderAttentionFrame(nowMillis: Long?) {
        val loadedStyle = style ?: return
        val ring = loadedStyle.getLayerAs<CircleLayer>(ATTENTION_LAYER_ID)
        if (!hasAttentionMarkers) {
            ring?.setProperties(circleOpacity(0f))
            return
        }
        if (nowMillis == null) {
            ring?.setProperties(
                circleRadius(ATTENTION_MIN_RADIUS),
                circleOpacity(ATTENTION_MIN_SIZE_OPACITY),
            )
            return
        }
        val phase = (nowMillis % ATTENTION_CYCLE_MILLIS).toFloat() / ATTENTION_CYCLE_MILLIS
        val progress = ((1.0 - cos(phase * TWO_PI)) / 2.0).toFloat()
        ring?.setProperties(
            circleRadius(ATTENTION_MIN_RADIUS + (ATTENTION_MAX_RADIUS - ATTENTION_MIN_RADIUS) * progress),
            circleOpacity(
                ATTENTION_MIN_SIZE_OPACITY -
                    (ATTENTION_MIN_SIZE_OPACITY - ATTENTION_MAX_SIZE_OPACITY) * progress,
            ),
        )
    }

    private fun clearRenderedLayerState() {
        lastStops = null
        lastStopClusters = null
        lastStopSourceRevision = null
        lastPolylineSourceRevision = null
        lastUserLocation = null
        lastVehicleSourceRevision = null
        lastVehicleBadgeRevision = null
        lastAttentionVehicleSourceRevision = null
        badgeImageIds.clear()
        nextBadgeImageIndex = 0L
        hasAttentionMarkers = false
    }
}

private fun ordinaryStopFeatures(renderState: MapRenderState): FeatureCollection = FeatureCollection.fromFeatures(
    (
        renderState.stops.asSequence()
        .filter { !it.isSelected && it.id.value.isNotBlank() && it.position.isMapCoordinate() }
        // Common metadata declares route-highlight priority; preserve it before the renderer cap.
        .sortedWith(compareBy<MapStopMarker> { !it.routeHighlight.isHighlighted }.thenBy { it.id.value })
        .take(MAX_STOP_MARKERS)
        .map { marker ->
            Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                feature.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value)
                feature.addStringProperty(FEATURE_KIND_PROPERTY, FEATURE_KIND_STOP)
                feature.addStringProperty(SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString())
                feature.addStringProperty(ACCESSIBILITY_LABEL_PROPERTY, marker.accessibilityLabel)
                feature.addStringProperty(MARKER_COLOR_PROPERTY, marker.routeHighlight.backgroundArgb.asMapColor())
                feature.addNumberProperty(MARKER_RADIUS_PROPERTY, marker.routeHighlight.markerRadius)
                feature.addStringProperty(MARKER_STROKE_COLOR_PROPERTY, marker.routeHighlight.textArgb.asMapColor())
                feature.addNumberProperty(MARKER_STROKE_WIDTH_PROPERTY, marker.routeHighlight.markerStrokeWidth)
            }
        }
        + renderState.stopClusters.asSequence()
            .filter { it.stableId.isNotBlank() && it.position.isMapCoordinate() && it.stopCount > 1 }
            .sortedBy { it.stableId }
            .take(MAX_STOP_MARKERS)
            .map { cluster ->
                Feature.fromGeometry(cluster.position.asMapPoint()).also { feature ->
                    feature.addStringProperty(FEATURE_ID_PROPERTY, cluster.stableId)
                    feature.addStringProperty(FEATURE_KIND_PROPERTY, FEATURE_KIND_CLUSTER)
                    feature.addStringProperty(SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString())
                    feature.addStringProperty(ACCESSIBILITY_LABEL_PROPERTY, cluster.accessibilityLabel)
                feature.addStringProperty(MARKER_COLOR_PROPERTY, CLUSTER_COLOR)
                feature.addNumberProperty(MARKER_RADIUS_PROPERTY, CLUSTER_RADIUS)
                feature.addStringProperty(MARKER_STROKE_COLOR_PROPERTY, DEFAULT_STOP_STROKE_COLOR)
                feature.addNumberProperty(MARKER_STROKE_WIDTH_PROPERTY, DEFAULT_STOP_STROKE_WIDTH)
                    feature.addNumberProperty(CLUSTER_COUNT_PROPERTY, cluster.stopCount)
                }
            }
    ).toList(),
)

private fun selectedStopFeatures(renderState: MapRenderState): FeatureCollection = FeatureCollection.fromFeatures(
    renderState.stops.asSequence()
        .filter { it.isSelected && it.id.value.isNotBlank() && it.position.isMapCoordinate() }
        .sortedBy { it.id.value }
        .take(1)
        .map { marker ->
            Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                feature.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value)
                feature.addStringProperty(FEATURE_KIND_PROPERTY, FEATURE_KIND_STOP)
                feature.addStringProperty(SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString())
                feature.addStringProperty(ACCESSIBILITY_LABEL_PROPERTY, marker.accessibilityLabel)
            }
        }
        .toList(),
)

private fun polylineFeatures(polylines: List<MapPolyline>): FeatureCollection = FeatureCollection.fromFeatures(
    polylines
        .filter { it.routeId.value.isNotBlank() }
        .take(MAX_POLYLINES)
        .mapIndexedNotNull { index, line ->
            // Preserve the authoritative common snapshot order. Both adapters apply exactly the
            // same valid-point filter and caps before replacing their one grouped source.
            val points = line.points.asSequence().filter(GeoPoint::isMapCoordinate).take(MAX_POLYLINE_POINTS).toList()
            points.takeIf(::isNonDegenerateLine)?.let { validPoints ->
                Feature.fromGeometry(LineString.fromLngLats(validPoints.map(GeoPoint::asMapPoint))).also { feature ->
                    feature.addStringProperty(FEATURE_ID_PROPERTY, "polyline-$index")
                    feature.addStringProperty(ROUTE_COLOR_PROPERTY, line.routeColorArgb.asMapColor())
                    feature.addNumberProperty(ROUTE_WIDTH_PROPERTY, line.strokeWidth.safePolylineWidth())
                    feature.addNumberProperty(ROUTE_OPACITY_PROPERTY, line.opacity.safePolylineOpacity())
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

private fun GeoPoint.asMapPoint(): Point = Point.fromLngLat(longitude, latitude)

private data class VehicleBadgeStyle(
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val stale: Boolean,
)

/** Exact badge inputs are independent from geometry so animation frames never rebuild bitmaps. */
private data class VehicleBadgeRenderInput(
    val styles: Set<VehicleBadgeStyle>,
    val needsOverflow: Boolean,
)

private fun List<MapVehicleMarker>.badgeRenderInput(): VehicleBadgeRenderInput {
    val allStyles = asSequence()
        .filter { it.id.value.isNotBlank() && it.routeId.value.isNotBlank() && it.position.isMapCoordinate() }
        .map(MapVehicleMarker::badgeStyle)
        .distinct()
        .sortedWith(compareBy<VehicleBadgeStyle> { it.label }.thenBy { it.backgroundArgb }.thenBy { it.textArgb }.thenBy { it.stale })
        .toList()
    return VehicleBadgeRenderInput(
        styles = allStyles.take(MAX_VEHICLE_BADGE_IMAGES).toSet(),
        needsOverflow = allStyles.size > MAX_VEHICLE_BADGE_IMAGES,
    )
}

private fun MapVehicleMarker.badgeStyle(): VehicleBadgeStyle = VehicleBadgeStyle(
    label = routeLabel.asNativeBadgeLabel(),
    backgroundArgb = routeColorArgb or 0xFF000000L,
    textArgb = routeTextColorArgb or 0xFF000000L,
    stale = isStale,
)

private fun String.asNativeBadgeLabel(): String = asSequence()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
    .joinToString(separator = "")
    .trim()
    .take(MAX_BADGE_LABEL_LENGTH)
    .ifBlank { "?" }

private fun VehicleBadgeStyle.toBitmap(): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textArgb.toInt()
        textSize = BADGE_TEXT_SIZE_PX
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    if (textPaint.measureText(label) > BADGE_TEXT_MAX_WIDTH_PX) {
        textPaint.textSize *= BADGE_TEXT_MAX_WIDTH_PX / textPaint.measureText(label)
    }
    val bitmap = Bitmap.createBitmap(BADGE_DIAMETER_PX, BADGE_DIAMETER_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb.toInt() }
    val center = BADGE_DIAMETER_PX / 2f
    val radius = center - BADGE_WHITE_STROKE_WIDTH_DP / 2f
    canvas.drawCircle(center, center, radius, background)
    val whiteBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = BADGE_WHITE_STROKE_WIDTH_DP
    }
    canvas.drawCircle(center, center, radius, whiteBorder)
    val baseline = center - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(label, center, baseline, textPaint)
    if (stale) {
        // Hatching is deliberately shape-based, so stale is not communicated by opacity/color alone.
        val cue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textArgb.toInt()
            alpha = STALE_CUE_ALPHA
            strokeWidth = STALE_CUE_STROKE_PX
        }
        canvas.drawLine(
            BADGE_STALE_INSET_PX,
            BADGE_DIAMETER_PX - BADGE_STALE_INSET_PX,
            BADGE_DIAMETER_PX - BADGE_STALE_INSET_PX,
            BADGE_STALE_INSET_PX,
            cue,
        )
        canvas.drawLine(
            BADGE_DIAMETER_PX / 2f,
            BADGE_DIAMETER_PX - BADGE_STALE_INSET_PX,
            BADGE_DIAMETER_PX + BADGE_DIAMETER_PX / 2f - BADGE_STALE_INSET_PX,
            BADGE_STALE_INSET_PX,
            cue,
        )
    }
    return bitmap
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

private fun isNonDegenerateLine(points: List<GeoPoint>): Boolean = points.size >= 2 && points.zipWithNext().any { (first, second) -> first != second }

private fun Long.asMapColor(): String = "#%06X".format(this and 0xFFFFFF)

private fun Double.safePolylineWidth(): Double = takeIf(Double::isFinite)?.coerceIn(MIN_POLYLINE_WIDTH, MAX_POLYLINE_WIDTH)
    ?: DEFAULT_POLYLINE_WIDTH

/** Route colors are contrast-validated on land only when every emitted line is fully opaque. */
private fun Double.safePolylineOpacity(): Double = DEFAULT_POLYLINE_OPACITY

private fun Double.normalizedBearing(): Double = ((this % 360.0) + 360.0) % 360.0

private const val STOPS_SOURCE_ID = "gt-stops-source"
private const val SELECTED_STOP_SOURCE_ID = "gt-selected-stop-source"
private const val VEHICLES_SOURCE_ID = "gt-vehicles-source"
private const val ATTENTION_SOURCE_ID = "gt-vehicle-attention-source"
private const val POLYLINES_SOURCE_ID = "gt-polylines-source"
private const val USER_LOCATION_SOURCE_ID = "gt-user-location-source"
private const val USER_ACCURACY_SOURCE_ID = "gt-user-accuracy-source"
private const val STOPS_LAYER_ID = "gt-stops-layer"
private const val SELECTED_STOP_LAYER_ID = "gt-selected-stop-layer"
private const val VEHICLES_LAYER_ID = "gt-vehicles-layer"
private const val ATTENTION_LAYER_ID = "gt-vehicle-attention-layer"
private const val POLYLINES_LAYER_ID = "gt-polylines-layer"
private const val USER_LOCATION_LAYER_ID = "gt-user-location-layer"
private const val USER_ACCURACY_FILL_LAYER_ID = "gt-user-accuracy-fill-layer"
private const val USER_ACCURACY_STROKE_LAYER_ID = "gt-user-accuracy-stroke-layer"
private const val FEATURE_ID_PROPERTY = "featureId"
private const val FEATURE_KIND_PROPERTY = "featureKind"
private const val SOURCE_REVISION_PROPERTY = "sourceRevision"
private const val ACCESSIBILITY_LABEL_PROPERTY = "accessibilityLabel"
private const val MARKER_COLOR_PROPERTY = "markerColor"
private const val MARKER_RADIUS_PROPERTY = "markerRadius"
private const val MARKER_STROKE_COLOR_PROPERTY = "markerStrokeColor"
private const val MARKER_STROKE_WIDTH_PROPERTY = "markerStrokeWidth"
private const val CLUSTER_COUNT_PROPERTY = "clusterCount"
private const val FEATURE_KIND_STOP = "stop"
private const val FEATURE_KIND_CLUSTER = "cluster"
private const val FEATURE_KIND_VEHICLE = "vehicle"
private const val SELECTED_STOP_COLOR = "#E76F51"
private const val CLUSTER_COLOR = "#264653"
private const val SELECTED_STOP_RADIUS = 9
private const val CLUSTER_RADIUS = 12
private const val DEFAULT_STOP_STROKE_COLOR = "#FFFFFF"
private const val DEFAULT_STOP_STROKE_WIDTH = 2.0
private const val ROUTE_COLOR_PROPERTY = "routeColor"
private const val ROUTE_WIDTH_PROPERTY = "routeWidth"
private const val ROUTE_OPACITY_PROPERTY = "routeOpacity"
private const val BEARING_PROPERTY = "bearing"
private const val POSITION_KIND_PROPERTY = "positionKind"
private const val VEHICLE_BADGE_IMAGE_PROPERTY = "vehicleBadgeImage"
private const val VEHICLE_OPACITY_PROPERTY = "vehicleOpacity"
private const val ATTENTION_COLOR_PROPERTY = "attentionColor"
private const val MAX_STOP_MARKERS = 1_000
private const val MAX_VEHICLE_MARKERS = 2_000
private const val MAX_VEHICLE_BADGE_IMAGES = 256
private const val MAX_ATTENTION_MARKERS = 32
private const val MAX_POLYLINES = 256
private const val MAX_POLYLINE_POINTS = 20_000
private const val MIN_POLYLINE_WIDTH = 1.0
private const val MAX_POLYLINE_WIDTH = 10.0
private const val DEFAULT_POLYLINE_WIDTH = 4.0
private const val DEFAULT_POLYLINE_OPACITY = 1.0
private const val MIN_POLYGON_POINTS = 4
private const val MIN_ZOOM = 0.0
private const val MAX_ZOOM = 22.0
private const val MAX_BOTTOM_OCCLUSION_FRACTION = 0.75
private const val MIN_STOP_TARGET_DP = 48f
private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val OVERFLOW_BADGE_IMAGE_ID = "gt-vehicle-badge-overflow"
private const val MAX_BADGE_LABEL_LENGTH = 8
private const val BADGE_TEXT_SIZE_PX = 20f
private const val BADGE_DIAMETER_PX = 40
private const val BADGE_TEXT_MAX_WIDTH_PX = 32f
private const val BADGE_WHITE_STROKE_WIDTH_DP = 1f
private const val BADGE_STALE_INSET_PX = 5f
private const val STALE_CUE_STROKE_PX = 2f
private const val STALE_CUE_ALPHA = 180
private const val STALE_VEHICLE_OPACITY = 0.62
private const val ATTENTION_MAX_FRAMES_PER_SECOND = 15L
private const val ATTENTION_TICK_MILLIS =
    (1_000L + ATTENTION_MAX_FRAMES_PER_SECOND - 1L) / ATTENTION_MAX_FRAMES_PER_SECOND
private const val ATTENTION_CYCLE_MILLIS = 1_600L
private const val TWO_PI = Math.PI * 2.0
private const val ATTENTION_MIN_RADIUS = BADGE_DIAMETER_PX / 2f * 1.15f
private const val ATTENTION_MAX_RADIUS = BADGE_DIAMETER_PX / 2f * 2f
private const val ATTENTION_MIN_SIZE_OPACITY = 0.50f
private const val ATTENTION_MAX_SIZE_OPACITY = 0.10f

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
