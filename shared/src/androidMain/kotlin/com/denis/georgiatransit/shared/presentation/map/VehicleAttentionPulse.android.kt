package com.denis.georgiatransit.shared.presentation.map

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import kotlin.math.cos

/** Route-coloured pulse rings under vehicle badges, ticked on the map view while the map is resumed. */
internal class VehicleAttentionPulse(
    private val view: View,
    private val currentStyle: () -> Style?,
) {
    private var lastVehicleSourceRevision: Long? = null
    private var hasMarkers = false
    private var lifecycleResumed = false
    private var tickerScheduled = false
    private var destroyed = false
    private val tick = Runnable {
        tickerScheduled = false
        if (shouldAnimate()) {
            renderFrame(SystemClock.uptimeMillis())
            scheduleTick()
        } else {
            renderFrame(null)
        }
    }

    fun layer(): CircleLayer = CircleLayer(ATTENTION_LAYER_ID, ATTENTION_SOURCE_ID).withProperties(
        circleColor(Expression.get(ATTENTION_COLOR_PROPERTY)),
        circleRadius(ATTENTION_MIN_RADIUS),
        circleOpacity(ATTENTION_MIN_SIZE_OPACITY),
    )

    fun render(loadedStyle: Style, renderState: MapRenderState) {
        if (lastVehicleSourceRevision == renderState.vehicleSourceRevision) return
        val markers = attentionMarkers(renderState.vehicles)
        loadedStyle.setSourceFeatures(ATTENTION_SOURCE_ID, attentionFeatures(markers))
        hasMarkers = markers.isNotEmpty()
        lastVehicleSourceRevision = renderState.vehicleSourceRevision
    }

    fun setLifecycleResumed(resumed: Boolean) {
        if (lifecycleResumed == resumed) return
        lifecycleResumed = resumed
        refresh()
    }

    fun refresh() {
        if (destroyed || currentStyle() == null) return
        if (!shouldAnimate()) {
            stopTicker()
            renderFrame(null)
            return
        }
        renderFrame(SystemClock.uptimeMillis())
        scheduleTick()
    }

    fun destroy() {
        destroyed = true
        stopTicker()
    }

    fun clear() {
        lastVehicleSourceRevision = null
        hasMarkers = false
    }

    /** Android's global animator scale is the accessible Reduce Motion signal for this adapter. */
    private fun shouldAnimate(): Boolean = lifecycleResumed && ValueAnimator.areAnimatorsEnabled() &&
        ValueAnimator.getDurationScale() > 0f && hasMarkers

    private fun scheduleTick() {
        if (!tickerScheduled && !destroyed && shouldAnimate()) {
            tickerScheduled = true
            view.postDelayed(tick, ATTENTION_TICK_MILLIS)
        }
    }

    private fun stopTicker() {
        view.removeCallbacks(tick)
        tickerScheduled = false
    }

    /** Smooth cosine interpolation grows and shrinks the ring without a turn-around discontinuity. */
    private fun renderFrame(nowMillis: Long?) {
        val loadedStyle = currentStyle() ?: return
        val ring = loadedStyle.getLayerAs<CircleLayer>(ATTENTION_LAYER_ID)
        if (!hasMarkers) {
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
}

/** A deterministic bounded subset keeps the decoration cost independent of fleet size. */
private fun attentionMarkers(vehicles: List<MapVehicleMarker>): List<MapVehicleMarker> = vehicles.asSequence()
    .filter(MapVehicleMarker::isRenderable)
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

private const val ATTENTION_COLOR_PROPERTY = "attentionColor"
private const val MAX_ATTENTION_MARKERS = 32
private const val BADGE_DIAMETER_PX = 80
private const val ATTENTION_MAX_FRAMES_PER_SECOND = 15L
private const val ATTENTION_TICK_MILLIS =
    (1_000L + ATTENTION_MAX_FRAMES_PER_SECOND - 1L) / ATTENTION_MAX_FRAMES_PER_SECOND
private const val ATTENTION_CYCLE_MILLIS = 1_600L
private const val TWO_PI = Math.PI * 2.0
private const val ATTENTION_RADIUS_REDUCTION_FACTOR = 1.5f
private const val ATTENTION_ADDITIONAL_RADIUS_SCALE = 0.85f
private const val ATTENTION_MIN_RADIUS =
    BADGE_DIAMETER_PX / 2f * 1.15f / ATTENTION_RADIUS_REDUCTION_FACTOR * ATTENTION_ADDITIONAL_RADIUS_SCALE
private const val ATTENTION_MAX_RADIUS =
    BADGE_DIAMETER_PX / 2f * 2f / ATTENTION_RADIUS_REDUCTION_FACTOR * ATTENTION_ADDITIONAL_RADIUS_SCALE
private const val ATTENTION_MIN_SIZE_OPACITY = 0.50f
private const val ATTENTION_MAX_SIZE_OPACITY = 0.10f
