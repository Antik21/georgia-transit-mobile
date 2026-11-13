package com.denis.georgiatransit.shared.presentation.map

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.View
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Planner lines, the long-press pin, and A/B markers; mirrors the web planner layers. */
internal class JourneyMapLayers(private val metrics: DisplayMetrics) {
    private var renderedOverlay: MapJourneyOverlay? = null
    private var hasRendered = false
    private var fittedRevision: Long? = null

    fun install(style: Style, belowLayerId: String) {
        style.addSource(GeoJsonSource(LINES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(POINTS_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        style.addImage(ORIGIN_IMAGE_ID, endpointBitmap("A", ORIGIN_COLOR))
        style.addImage(DESTINATION_IMAGE_ID, endpointBitmap("B", DESTINATION_COLOR))
        val kind = Expression.get(KIND_PROPERTY)
        style.addLayerBelow(
            LineLayer(WALK_LAYER_ID, LINES_SOURCE_ID)
                .withFilter(Expression.eq(kind, Expression.literal(KIND_WALK)))
                .withProperties(
                    lineColor(WALK_COLOR), lineWidth(4f), lineDasharray(arrayOf(0.6f, 1.6f)),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            belowLayerId,
        )
        style.addLayerBelow(
            LineLayer(CASING_LAYER_ID, LINES_SOURCE_ID)
                .withFilter(Expression.eq(kind, Expression.literal(KIND_TRANSIT)))
                .withProperties(
                    lineColor(CASING_COLOR), lineWidth(10f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            belowLayerId,
        )
        style.addLayerBelow(
            LineLayer(TRANSIT_LAYER_ID, LINES_SOURCE_ID)
                .withFilter(Expression.eq(kind, Expression.literal(KIND_TRANSIT)))
                .withProperties(
                    lineColor(Expression.get(COLOR_PROPERTY)), lineWidth(6f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            belowLayerId,
        )
        style.addLayer(
            CircleLayer(PIN_LAYER_ID, POINTS_SOURCE_ID)
                .withFilter(Expression.eq(kind, Expression.literal(KIND_PIN)))
                .withProperties(circleColor(PIN_COLOR), circleRadius(7f), circleStrokeColor(CASING_COLOR), circleStrokeWidth(3f)),
        )
        style.addLayer(
            SymbolLayer(ENDPOINTS_LAYER_ID, POINTS_SOURCE_ID)
                .withFilter(Expression.neq(kind, Expression.literal(KIND_PIN)))
                .withProperties(iconImage(Expression.get(IMAGE_PROPERTY)), iconAllowOverlap(true), iconIgnorePlacement(true)),
        )
        hasRendered = false
    }

    fun render(style: Style, overlay: MapJourneyOverlay?) {
        val content = overlay?.copy(fitRevision = 0L)
        if (hasRendered && content == renderedOverlay) return
        style.getSourceAs<GeoJsonSource>(LINES_SOURCE_ID)?.setGeoJson(lineFeatures(content))
        style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID)?.setGeoJson(pointFeatures(content))
        renderedOverlay = content
        hasRendered = true
    }

    /** Frames the plan once per user intent; a re-created view frames the visible plan again. */
    fun fitIfNeeded(map: MapLibreMap, overlay: MapJourneyOverlay?, view: View) {
        if (overlay == null || overlay.lines.isEmpty() || fittedRevision == overlay.fitRevision) return
        fittedRevision = overlay.fitRevision
        // Results resize the planner panel in the same frame, so frame after the map is re-laid out.
        view.postDelayed({ if (fittedRevision == overlay.fitRevision && view.height > 0) fit(map, overlay) }, FIT_DELAY_MILLIS)
    }

    private fun fit(map: MapLibreMap, overlay: MapJourneyOverlay) {
        val points = (overlay.lines.flatMap { it.points } + listOfNotNull(overlay.origin, overlay.destination))
            .filter(GeoPoint::isMapCoordinate)
            .map { LatLng(it.latitude, it.longitude) }
            .distinct()
        if (points.size < 2) return
        val padding = (FIT_PADDING_DP * metrics.density).toInt()
        val camera = map.getCameraForLatLngBounds(
            LatLngBounds.Builder().includes(points).build(),
            intArrayOf(padding, padding, padding, padding),
        ) ?: return
        val target = camera.target ?: return
        val update = CameraUpdateFactory.newLatLngZoom(target, camera.zoom.coerceAtMost(MAX_FIT_ZOOM))
        if (ValueAnimator.areAnimatorsEnabled()) map.animateCamera(update, FIT_DURATION_MILLIS) else map.moveCamera(update)
    }

    fun clearRendered() {
        renderedOverlay = null
        hasRendered = false
    }

    private fun lineFeatures(overlay: MapJourneyOverlay?) = FeatureCollection.fromFeatures(
        overlay?.lines.orEmpty()
            .filter { line -> line.points.count(GeoPoint::isMapCoordinate) >= 2 }
            .map { line ->
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        line.points.filter(GeoPoint::isMapCoordinate).map { Point.fromLngLat(it.longitude, it.latitude) },
                    ),
                ).also { feature ->
                    val isWalk = line.kind == JourneyPlanLineKind.Walk
                    feature.addStringProperty(KIND_PROPERTY, if (isWalk) KIND_WALK else KIND_TRANSIT)
                    feature.addStringProperty(COLOR_PROPERTY, "#%06X".format(line.colorArgb and 0xFFFFFF))
                }
            },
    )

    private fun pointFeatures(overlay: MapJourneyOverlay?) = FeatureCollection.fromFeatures(
        listOfNotNull(
            overlay?.origin?.let { it to ORIGIN_IMAGE_ID },
            overlay?.destination?.let { it to DESTINATION_IMAGE_ID },
            overlay?.pin?.let { it to null },
        ).filter { it.first.isMapCoordinate() }.map { (point, image) ->
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).also { feature ->
                feature.addStringProperty(KIND_PROPERTY, if (image == null) KIND_PIN else KIND_ENDPOINT)
                image?.let { feature.addStringProperty(IMAGE_PROPERTY, it) }
            }
        },
    )

    private fun endpointBitmap(letter: String, color: Int): Bitmap {
        val density = metrics.density
        val size = (ENDPOINT_DIAMETER_DP * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        val stroke = ENDPOINT_STROKE_DP * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = CASING_COLOR_INT
        canvas.drawCircle(radius, radius, radius, paint)
        paint.color = color
        canvas.drawCircle(radius, radius, radius - stroke, paint)
        paint.color = CASING_COLOR_INT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.5f
        canvas.drawText(letter, radius, radius - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }
}

private const val LINES_SOURCE_ID = "gt-journey-lines-source"
private const val POINTS_SOURCE_ID = "gt-journey-points-source"
private const val WALK_LAYER_ID = "gt-journey-walk-layer"
private const val CASING_LAYER_ID = "gt-journey-transit-casing-layer"
private const val TRANSIT_LAYER_ID = "gt-journey-transit-layer"
private const val PIN_LAYER_ID = "gt-journey-pin-layer"
private const val ENDPOINTS_LAYER_ID = "gt-journey-endpoints-layer"
private const val ORIGIN_IMAGE_ID = "gt-journey-origin"
private const val DESTINATION_IMAGE_ID = "gt-journey-destination"
private const val KIND_PROPERTY = "kind"
private const val COLOR_PROPERTY = "color"
private const val IMAGE_PROPERTY = "image"
private const val KIND_WALK = "walk"
private const val KIND_TRANSIT = "transit"
private const val KIND_PIN = "pin"
private const val KIND_ENDPOINT = "endpoint"
private const val WALK_COLOR = "#6B7280"
private const val CASING_COLOR = "#FFFFFF"
private const val CASING_COLOR_INT = 0xFFFFFFFF.toInt()
private const val PIN_COLOR = "#263238"
private const val ORIGIN_COLOR = 0xFF2E7D32.toInt()
private const val DESTINATION_COLOR = 0xFF8E1B2D.toInt()
private const val ENDPOINT_DIAMETER_DP = 28f
private const val ENDPOINT_STROKE_DP = 3f
private const val FIT_PADDING_DP = 64f
private const val FIT_DELAY_MILLIS = 250L
private const val MAX_FIT_ZOOM = 17.0
private const val FIT_DURATION_MILLIS = 400
