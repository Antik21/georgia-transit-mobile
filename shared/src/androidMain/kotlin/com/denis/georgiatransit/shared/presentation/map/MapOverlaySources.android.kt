package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.location.accuracyPolygon
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon

/** Stop, cluster, route-polyline and user-location sources, each replaced only when its input changes. */
internal class MapOverlaySources {
    private val stopPinImages = StopPinImages()
    private var lastStops: List<MapStopMarker>? = null
    private var lastStopClusters: List<MapStopCluster>? = null
    private var lastStopSourceRevision: Long? = null
    private var lastPolylineSourceRevision: Long? = null
    private var lastUserLocation: UserLocationFix? = null

    fun render(loadedStyle: Style, renderState: MapRenderState) {
        if (
            lastStops != renderState.stops || lastStopClusters != renderState.stopClusters ||
            lastStopSourceRevision != renderState.stopSourceRevision
        ) {
            stopPinImages.update(loadedStyle, renderState.stops)
            loadedStyle.setSourceFeatures(STOPS_SOURCE_ID, ordinaryStopFeatures(renderState))
            loadedStyle.setSourceFeatures(SELECTED_STOP_SOURCE_ID, selectedStopFeatures(renderState))
            lastStops = renderState.stops
            lastStopClusters = renderState.stopClusters
            lastStopSourceRevision = renderState.stopSourceRevision
        }
        if (lastPolylineSourceRevision != renderState.polylineSourceRevision) {
            loadedStyle.setSourceFeatures(POLYLINES_SOURCE_ID, polylineFeatures(renderState.polylines, emphasized = false))
            loadedStyle.setSourceFeatures(
                EMPHASIZED_POLYLINES_SOURCE_ID,
                polylineFeatures(renderState.polylines, emphasized = true),
            )
            lastPolylineSourceRevision = renderState.polylineSourceRevision
        }
        if (lastUserLocation != renderState.userLocation) {
            loadedStyle.setSourceFeatures(USER_LOCATION_SOURCE_ID, userLocationFeatures(renderState.userLocation))
            loadedStyle.setSourceFeatures(USER_ACCURACY_SOURCE_ID, userAccuracyFeatures(renderState.userLocation))
            lastUserLocation = renderState.userLocation
        }
    }

    fun clear() {
        lastStops = null
        lastStopClusters = null
        lastStopSourceRevision = null
        lastPolylineSourceRevision = null
        lastUserLocation = null
        stopPinImages.clear()
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
                    feature.addStringProperty(STOP_PIN_PROPERTY, marker.routeHighlight.pinArtwork.imageId)
                    feature.addNumberProperty(STOP_PIN_SCALE_PROPERTY, marker.pinScale)
                    feature.addStringProperty(MARKER_COLOR_PROPERTY, TRANSPARENT_STOP_COLOR)
                    feature.addNumberProperty(MARKER_RADIUS_PROPERTY, marker.routeHighlight.markerRadius)
                    feature.addStringProperty(MARKER_STROKE_COLOR_PROPERTY, TRANSPARENT_STOP_COLOR)
                    feature.addNumberProperty(MARKER_STROKE_WIDTH_PROPERTY, marker.routeHighlight.markerStrokeWidth)
                }
            } +
            renderState.stopClusters.asSequence()
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
                feature.addStringProperty(STOP_PIN_PROPERTY, marker.routeHighlight.pinArtwork.imageId)
                feature.addNumberProperty(STOP_PIN_SCALE_PROPERTY, marker.pinScale)
                feature.addStringProperty(MARKER_COLOR_PROPERTY, TRANSPARENT_STOP_COLOR)
                feature.addStringProperty(MARKER_STROKE_COLOR_PROPERTY, TRANSPARENT_STOP_COLOR)
            }
        }
        .toList(),
)

private fun polylineFeatures(
    polylines: List<MapPolyline>,
    emphasized: Boolean,
): FeatureCollection = FeatureCollection.fromFeatures(
    polylines
        .take(MAX_POLYLINES)
        .filter { it.isEmphasized == emphasized && it.routeId.value.isNotBlank() }
        .mapIndexedNotNull { index, line ->
            // Preserve the authoritative common snapshot order. Both adapters apply the global
            // cap before splitting into base and focused layers, then use the same point filter.
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

private fun isNonDegenerateLine(points: List<GeoPoint>): Boolean = points.size >= 2 && points.zipWithNext().any { (first, second) -> first != second }

private fun Double.safePolylineWidth(): Double = takeIf(Double::isFinite)?.coerceIn(MIN_POLYLINE_WIDTH, MAX_POLYLINE_WIDTH)
    ?: DEFAULT_POLYLINE_WIDTH

/** Route colors are contrast-validated on land only when every emitted line is fully opaque. */
private fun Double.safePolylineOpacity(): Double = DEFAULT_POLYLINE_OPACITY

private const val ACCESSIBILITY_LABEL_PROPERTY = "accessibilityLabel"
private const val CLUSTER_COUNT_PROPERTY = "clusterCount"
private const val TRANSPARENT_STOP_COLOR = "rgba(0,0,0,0)"
private const val CLUSTER_COLOR = "#264653"
private const val CLUSTER_RADIUS = 12
private const val DEFAULT_STOP_STROKE_COLOR = "#FFFFFF"
private const val DEFAULT_STOP_STROKE_WIDTH = 2.0
private const val MAX_STOP_MARKERS = 1_000
private const val MAX_POLYLINES = 256
private const val MAX_POLYLINE_POINTS = 20_000
private const val MIN_POLYLINE_WIDTH = 1.0
private const val MAX_POLYLINE_WIDTH = 10.0
private const val DEFAULT_POLYLINE_WIDTH = 4.0
private const val DEFAULT_POLYLINE_OPACITY = 1.0
private const val MIN_POLYGON_POINTS = 4
