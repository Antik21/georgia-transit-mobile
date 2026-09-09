package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * A deliberate, programmatic camera move. Adapters must only move the camera when [revision]
 * changes, which keeps user pan and zoom intact while layers or location status update.
 */
@Immutable
data class MapCameraCommand(
    val center: GeoPoint,
    val zoom: Double,
    val revision: Long,
    /** Semantic map occlusion; native adapters convert it to their own pixel/point padding. */
    val viewportInsets: MapViewportInsets = MapViewportInsets.None,
)

/**
 * SDK-free viewport padding expressed as a stable fraction of the map height. Fractions avoid
 * leaking Android pixels or UIKit points and remain deterministic across density and font scale.
 */
@Immutable
data class MapViewportInsets(
    val bottomOcclusionFraction: Double,
) {
    companion object {
        val None = MapViewportInsets(bottomOcclusionFraction = 0.0)

        /** ModalBottomSheet does not expose portable settled bounds, so use one semantic inset. */
        val StopArrivalsSheet = MapViewportInsets(bottomOcclusionFraction = 0.42)
    }
}

/** The settled visible area reported by a native map without leaking an SDK viewport type. */
@Immutable
data class MapViewport(
    val center: GeoPoint,
    val radiusMeters: Int,
    val zoom: Double,
)

/** Native map input is reduced to product-level viewport and revision-bound entity interactions. */
sealed interface MapPlatformEvent {
    data class ViewportSettled(val viewport: MapViewport) : MapPlatformEvent
    data class StopTapped(val stopId: StopId, val sourceRevision: Long) : MapPlatformEvent
    data class VehicleTapped(val vehicleId: VehicleId, val sourceRevision: Long) : MapPlatformEvent
}

/** A compact SDK-free map marker; labels and stop details stay in future product flows. */
@Immutable
data class MapStopMarker(
    val id: StopId,
    val position: GeoPoint,
    val accessibilityLabel: String = "",
    val isSelected: Boolean = false,
    /** Computed entirely in common presentation from nearby stop route IDs and committed selection. */
    val routeHighlight: StopRouteHighlightUi = StopRouteHighlightUi(),
) {
    /** iOS-safe bridge key; [id] remains the authoritative typed identifier in common code. */
    val stableId: String get() = id.value
}

/** Common clustering output. Its key is stable for a zoom bucket and projected grid cell. */
@Immutable
data class MapStopCluster(
    val stableId: String,
    val position: GeoPoint,
    val stopCount: Int,
    val accessibilityLabel: String = "",
)

/**
 * Presentation-only vehicle geometry. The adapter may use [bearingDegrees] and [positionKind]
 * when that can be rendered safely, but never owns freshness or provider interpretation.
 */
@Immutable
data class MapVehicleMarker(
    val id: VehicleId,
    val routeId: RouteId,
    val directionId: DirectionId?,
    val position: GeoPoint,
    val routeColorArgb: Long,
    val bearingDegrees: Double?,
    val positionKind: VehiclePositionKind,
    val freshness: TransitFreshness,
    /** Sanitized route short name used by native, local bitmap badge renderers. */
    val routeLabel: String,
    /** Chosen in common code to guarantee a WCAG 4.5:1 badge text contrast ratio. */
    val routeTextColorArgb: Long,
    /** Non-colour stale cue is rendered by each adapter in addition to reduced opacity. */
    val isStale: Boolean,
    /** Monotonic frame/source revision; it changes without touching [MapCameraCommand.revision]. */
    val frameRevision: Long,
) {
    /** iOS-safe bridge keys; opaque typed identifiers remain available to common presentation. */
    val stableId: String get() = id.value
    val stableRouteId: String get() = routeId.value
    val stableDirectionId: String? get() = directionId?.value
}

/** An already-decoded, presentation-ready route geometry. */
@Immutable
data class MapPolyline(
    val routeId: RouteId,
    val directionId: DirectionId?,
    val points: PersistentList<GeoPoint>,
    val routeColorArgb: Long,
    val freshness: TransitFreshness,
    /** Common visual priority avoids platform-specific focus rules or opaque-ID ordering. */
    val isEmphasized: Boolean = false,
    val strokeWidth: Double = 4.0,
    /** Route colors are contrast-validated against the fixed land fill at full opacity. */
    val opacity: Double = 1.0,
) {
    /** iOS-safe bridge keys; opaque typed identifiers remain available to common presentation. */
    val stableRouteId: String get() = routeId.value
    val stableDirectionId: String? get() = directionId?.value

    /** Converts caller-owned collections before retaining them in immutable presentation state. */
    constructor(
        routeId: RouteId,
        directionId: DirectionId?,
        points: List<GeoPoint>,
        routeColorArgb: Long,
        freshness: TransitFreshness,
        isEmphasized: Boolean = false,
        strokeWidth: Double = 4.0,
        opacity: Double = 1.0,
    ) : this(routeId, directionId, points.toPersistentList(), routeColorArgb, freshness, isEmphasized, strokeWidth, opacity)
}

/**
 * The complete renderer boundary shared by Android and iOS. It intentionally contains no native
 * map objects, provider DTOs, URLs, keys, or service lookup. Empty layer lists are valid.
 */
@Immutable
data class MapRenderState(
    val camera: MapCameraCommand,
    val stops: PersistentList<MapStopMarker> = persistentListOf(),
    val stopClusters: PersistentList<MapStopCluster> = persistentListOf(),
    val vehicles: PersistentList<MapVehicleMarker> = persistentListOf(),
    val polylines: PersistentList<MapPolyline> = persistentListOf(),
    /** Lets adapters skip all KMP polyline traversal during unrelated render frames. */
    val polylineSourceRevision: Long = 0L,
    val userLocation: UserLocationFix? = null,
    /** Identifies the exact stop/cluster source currently safe for native hit callbacks. */
    val stopSourceRevision: Long = 0L,
    /** Lets adapters replace only the grouped vehicle source during interpolation/expiry ticks. */
    val vehicleSourceRevision: Long = 0L,
    /** Changes only when a native route-badge bitmap style can change, never for frame geometry. */
    val vehicleBadgeRevision: Long = 0L,
) {
    /**
     * Converts arbitrary caller-owned iterables into persistent snapshots, which is the convenient
     * entry point for mappers receiving regular lists from a future BFF-backed use case.
     */
    companion object {
        fun from(
            camera: MapCameraCommand,
            stops: Iterable<MapStopMarker> = emptyList(),
            stopClusters: Iterable<MapStopCluster> = emptyList(),
            vehicles: Iterable<MapVehicleMarker> = emptyList(),
            polylines: Iterable<MapPolyline> = emptyList(),
            polylineSourceRevision: Long = 0L,
            userLocation: UserLocationFix? = null,
            stopSourceRevision: Long = 0L,
            vehicleSourceRevision: Long = 0L,
            vehicleBadgeRevision: Long = 0L,
        ): MapRenderState = MapRenderState(
            camera = camera,
            stops = stops.toPersistentList(),
            stopClusters = stopClusters.toPersistentList(),
            vehicles = vehicles.toPersistentList(),
            polylines = polylines.toPersistentList(),
            polylineSourceRevision = polylineSourceRevision,
            userLocation = userLocation,
            stopSourceRevision = stopSourceRevision,
            vehicleSourceRevision = vehicleSourceRevision,
            vehicleBadgeRevision = vehicleBadgeRevision,
        )
    }
}

@Immutable
data class ClusteredStops(
    val stops: PersistentList<MapStopMarker>,
    val clusters: PersistentList<MapStopCluster>,
)

/**
 * Deterministic linear-time Web-Mercator grid clustering. The projected cell size grows with
 * density, while zoom changes the world scale; high zoom always exposes individual stops.
 */
internal fun clusterStops(markers: List<MapStopMarker>, zoom: Double): ClusteredStops {
    val valid = markers.asSequence()
        .filter { it.id.value.isNotBlank() && it.position.isMapCoordinate() }
        .sortedBy(MapStopMarker::stableId)
        .toList()
    val zoomBucket = floor(zoom.coerceIn(MIN_CLUSTER_ZOOM, MAX_CLUSTER_ZOOM)).toInt()
    if (zoomBucket >= INDIVIDUAL_STOP_ZOOM || valid.size < 2) {
        return ClusteredStops(valid.toPersistentList(), persistentListOf())
    }

    val cellPixels = when {
        valid.size >= HIGH_DENSITY_STOP_COUNT -> HIGH_DENSITY_CELL_PIXELS
        valid.size >= MEDIUM_DENSITY_STOP_COUNT -> MEDIUM_DENSITY_CELL_PIXELS
        else -> DEFAULT_CELL_PIXELS
    }
    val worldPixels = TILE_SIZE * 2.0.pow(zoomBucket)
    // Selected sheets always win hit/visual priority. Route-highlighted stops remain individually
    // tappable too, so a selected route never disappears into an ordinary nearby-stop cluster.
    val prioritized = valid.filter { marker -> marker.isSelected || marker.routeHighlight.isHighlighted }
    val cells = linkedMapOf<GridCell, MutableList<MapStopMarker>>()
    valid.asSequence().filterNot { marker -> marker.isSelected || marker.routeHighlight.isHighlighted }.forEach { marker ->
        val point = marker.position.toProjectedPoint(worldPixels)
        val cell = GridCell(floor(point.first / cellPixels).toLong(), floor(point.second / cellPixels).toLong())
        cells.getOrPut(cell, ::mutableListOf).add(marker)
    }

    val individuals = prioritized.toMutableList()
    val clusters = mutableListOf<MapStopCluster>()
    cells.entries.sortedWith(compareBy<Map.Entry<GridCell, MutableList<MapStopMarker>>> { it.key.x }.thenBy { it.key.y })
        .forEach { (cell, members) ->
        if (members.size == 1) {
            individuals += members.single()
        } else {
            clusters += MapStopCluster(
                stableId = "cluster:$zoomBucket:${cellPixels.toInt()}:${cell.x}:${cell.y}",
                position = GeoPoint(
                    latitude = members.sumOf { it.position.latitude } / members.size,
                    longitude = members.sumOf { it.position.longitude } / members.size,
                ),
                stopCount = members.size,
            )
        }
        }
    return ClusteredStops(
        stops = individuals.sortedBy(MapStopMarker::stableId).toPersistentList(),
        clusters = clusters.sortedBy(MapStopCluster::stableId).toPersistentList(),
    )
}

private data class GridCell(val x: Long, val y: Long)

private fun GeoPoint.toProjectedPoint(worldPixels: Double): Pair<Double, Double> {
    val x = (longitude + 180.0) / 360.0 * worldPixels
    val latitudeRadians = latitude.coerceIn(-MERCATOR_LATITUDE_LIMIT, MERCATOR_LATITUDE_LIMIT) * PI / 180.0
    val y = (0.5 - ln((1.0 + sin(latitudeRadians)) / (1.0 - sin(latitudeRadians))) / (4.0 * PI)) * worldPixels
    return x to y
}

internal fun GeoPoint.isMapCoordinate(): Boolean = latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0

/**
 * Native renderer boundary. A re-created native view receives the current state and applies its
 * camera command once; later layer-only updates must not reset a user-controlled camera.
 */
@Composable
expect fun PlatformMap(
    renderState: MapRenderState,
    onEvent: (MapPlatformEvent) -> Unit,
    modifier: Modifier = Modifier,
)

private const val MIN_CLUSTER_ZOOM = 0.0
private const val MAX_CLUSTER_ZOOM = 22.0
private const val INDIVIDUAL_STOP_ZOOM = 17
private const val TILE_SIZE = 256.0
private const val DEFAULT_CELL_PIXELS = 56.0
private const val MEDIUM_DENSITY_CELL_PIXELS = 68.0
private const val HIGH_DENSITY_CELL_PIXELS = 80.0
private const val MEDIUM_DENSITY_STOP_COUNT = 40
private const val HIGH_DENSITY_STOP_COUNT = 80
private const val MERCATOR_LATITUDE_LIMIT = 85.05112878
