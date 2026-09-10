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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

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
 * map objects, provider DTOs, upstream URLs, keys, or service lookup. [bffStyleUrl], when set,
 * is a validated same-BFF URL injected by host composition; empty layer lists are valid.
 */
@Immutable
data class MapRenderState(
    val camera: MapCameraCommand,
    val bffStyleUrl: String? = null,
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
            bffStyleUrl: String? = null,
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
            bffStyleUrl = bffStyleUrl,
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

/**
 * Keeps the map legible without hiding selected-route or genuinely nearby stops. The zoom rule
 * uses a threshold rather than equality because native maps report continuous fractional zoom.
 */
internal fun visibleStopMarkers(
    markers: List<MapStopMarker>,
    userLocation: GeoPoint?,
    zoom: Double,
): PersistentList<MapStopMarker> {
    val showViewportStops = zoom.isFinite() && zoom >= ALL_STOPS_MIN_ZOOM
    val validLocation = userLocation?.takeIf(GeoPoint::isMapCoordinate)
    return markers.asSequence()
        .filter { it.id.value.isNotBlank() && it.position.isMapCoordinate() }
        .filter { marker ->
            showViewportStops || marker.isSelected || marker.routeHighlight.isHighlighted ||
                validLocation?.let { marker.position.distanceMetersTo(it) <= USER_STOP_RADIUS_METERS } == true
        }
        .sortedBy(MapStopMarker::stableId)
        .toPersistentList()
}

private fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val latitudeDelta = (other.latitude - latitude) * PI / 180.0
    val longitudeDelta = (other.longitude - longitude) * PI / 180.0
    val firstLatitude = latitude * PI / 180.0
    val secondLatitude = other.latitude * PI / 180.0
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0).let { it * it }
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
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

internal const val ALL_STOPS_MIN_ZOOM = 15.0
internal const val USER_STOP_RADIUS_METERS = 1_000.0
private const val EARTH_RADIUS_METERS = 6_371_008.8
