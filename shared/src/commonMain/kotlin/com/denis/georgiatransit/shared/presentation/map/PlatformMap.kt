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

/**
 * A deliberate, programmatic camera move. Adapters must only move the camera when [revision]
 * changes, which keeps user pan and zoom intact while layers or location status update.
 */
@Immutable
data class MapCameraCommand(
    val center: GeoPoint,
    val zoom: Double,
    val revision: Long,
)

/** A compact SDK-free map marker; labels and stop details stay in future product flows. */
@Immutable
data class MapStopMarker(
    val id: StopId,
    val position: GeoPoint,
) {
    /** iOS-safe bridge key; [id] remains the authoritative typed identifier in common code. */
    val stableId: String get() = id.value
}

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
    ) : this(routeId, directionId, points.toPersistentList(), routeColorArgb, freshness)
}

/**
 * The complete renderer boundary shared by Android and iOS. It intentionally contains no native
 * map objects, provider DTOs, URLs, keys, or service lookup. Empty layer lists are valid.
 */
@Immutable
data class MapRenderState(
    val camera: MapCameraCommand,
    val stops: PersistentList<MapStopMarker> = persistentListOf(),
    val vehicles: PersistentList<MapVehicleMarker> = persistentListOf(),
    val polylines: PersistentList<MapPolyline> = persistentListOf(),
    val userLocation: UserLocationFix? = null,
) {
    /**
     * Converts arbitrary caller-owned iterables into persistent snapshots, which is the convenient
     * entry point for mappers receiving regular lists from a future BFF-backed use case.
     */
    companion object {
        fun from(
            camera: MapCameraCommand,
            stops: Iterable<MapStopMarker> = emptyList(),
            vehicles: Iterable<MapVehicleMarker> = emptyList(),
            polylines: Iterable<MapPolyline> = emptyList(),
            userLocation: UserLocationFix? = null,
        ): MapRenderState = MapRenderState(
            camera = camera,
            stops = stops.toPersistentList(),
            vehicles = vehicles.toPersistentList(),
            polylines = polylines.toPersistentList(),
            userLocation = userLocation,
        )
    }
}

/**
 * Native renderer boundary. A re-created native view receives the current state and applies its
 * camera command once; later layer-only updates must not reset a user-controlled camera.
 */
@Composable
expect fun PlatformMap(
    renderState: MapRenderState,
    modifier: Modifier = Modifier,
)
