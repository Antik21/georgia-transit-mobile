package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** The transit overlay stack in z-order; the whole stack is rebuilt after every base-style load. */
internal fun installSourcesAndLayers(loadedStyle: Style, vehicleLayer: SymbolLayer, attentionLayer: CircleLayer) {
    loadedStyle.addSource(GeoJsonSource(STOPS_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(SELECTED_STOP_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(ATTENTION_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(POLYLINES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(EMPHASIZED_POLYLINES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    loadedStyle.addSource(GeoJsonSource(USER_ACCURACY_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))

    loadedStyle.addLayer(
        LineLayer(POLYLINES_LAYER_ID, POLYLINES_SOURCE_ID).withProperties(
            lineColor(Expression.get(ROUTE_COLOR_PROPERTY)),
            lineOpacity(Expression.get(ROUTE_OPACITY_PROPERTY)),
            lineWidth(Expression.get(ROUTE_WIDTH_PROPERTY)),
        ),
    )
    // A separate layer guarantees that the focused route is drawn over any overlapping route.
    loadedStyle.addLayer(
        LineLayer(EMPHASIZED_POLYLINES_LAYER_ID, EMPHASIZED_POLYLINES_SOURCE_ID).withProperties(
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
    loadedStyle.addLayer(stopPinLayer(STOPS_SOURCE_ID))
    loadedStyle.addLayer(vehicleLayer)
    // The route-coloured pulse renders below the stable badge and is never hit-tested.
    loadedStyle.addLayerBelow(attentionLayer, VEHICLES_LAYER_ID)
    loadedStyle.addLayer(
        CircleLayer(SELECTED_STOP_LAYER_ID, SELECTED_STOP_SOURCE_ID).withProperties(
            circleColor(Expression.get(MARKER_COLOR_PROPERTY)),
            circleRadius(SELECTED_STOP_RADIUS.toFloat()),
            circleStrokeColor(Expression.get(MARKER_STROKE_COLOR_PROPERTY)),
            circleStrokeWidth(2f),
        ),
    )
    loadedStyle.addLayer(stopPinLayer(SELECTED_STOP_SOURCE_ID))
    loadedStyle.addLayer(
        CircleLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            circleColor("#1565C0"),
            circleRadius(7f),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(3f),
        ),
    )
}

internal fun Style.setSourceFeatures(sourceId: String, features: FeatureCollection) {
    getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(features)
}

internal fun GeoPoint.asMapPoint(): Point = Point.fromLngLat(longitude, latitude)

internal fun Long.asMapColor(): String = "#%06X".format(this and 0xFFFFFF)

internal fun Double.normalizedBearing(): Double = ((this % 360.0) + 360.0) % 360.0

internal const val STOPS_SOURCE_ID = "gt-stops-source"
internal const val SELECTED_STOP_SOURCE_ID = "gt-selected-stop-source"
internal const val VEHICLES_SOURCE_ID = "gt-vehicles-source"
internal const val ATTENTION_SOURCE_ID = "gt-vehicle-attention-source"
internal const val POLYLINES_SOURCE_ID = "gt-polylines-source"
internal const val EMPHASIZED_POLYLINES_SOURCE_ID = "gt-emphasized-polylines-source"
internal const val USER_LOCATION_SOURCE_ID = "gt-user-location-source"
internal const val USER_ACCURACY_SOURCE_ID = "gt-user-accuracy-source"
private const val STOPS_LAYER_ID = "gt-stops-layer"
private const val SELECTED_STOP_LAYER_ID = "gt-selected-stop-layer"
internal const val VEHICLES_LAYER_ID = "gt-vehicles-layer"
internal const val ATTENTION_LAYER_ID = "gt-vehicle-attention-layer"
private const val POLYLINES_LAYER_ID = "gt-polylines-layer"
private const val EMPHASIZED_POLYLINES_LAYER_ID = "gt-emphasized-polylines-layer"
private const val USER_LOCATION_LAYER_ID = "gt-user-location-layer"
private const val USER_ACCURACY_FILL_LAYER_ID = "gt-user-accuracy-fill-layer"
private const val USER_ACCURACY_STROKE_LAYER_ID = "gt-user-accuracy-stroke-layer"
internal const val FEATURE_ID_PROPERTY = "featureId"
internal const val FEATURE_KIND_PROPERTY = "featureKind"
internal const val SOURCE_REVISION_PROPERTY = "sourceRevision"
internal const val MARKER_COLOR_PROPERTY = "markerColor"
internal const val MARKER_RADIUS_PROPERTY = "markerRadius"
internal const val MARKER_STROKE_COLOR_PROPERTY = "markerStrokeColor"
internal const val MARKER_STROKE_WIDTH_PROPERTY = "markerStrokeWidth"
internal const val ROUTE_COLOR_PROPERTY = "routeColor"
internal const val ROUTE_WIDTH_PROPERTY = "routeWidth"
internal const val ROUTE_OPACITY_PROPERTY = "routeOpacity"
internal const val FEATURE_KIND_STOP = "stop"
internal const val FEATURE_KIND_CLUSTER = "cluster"
internal const val FEATURE_KIND_VEHICLE = "vehicle"
private const val SELECTED_STOP_RADIUS = 9
