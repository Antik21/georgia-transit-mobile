package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Owns marker frames, route-motion cache and native source revisions for one Map entry. */
internal class MapVehiclePresentation(
    private val realtimeClock: VehicleRealtimeClock,
    private val realtimeTickerPolicy: VehicleRealtimeTickerPolicy,
) {
    var vehicleRealtimeState = VehicleRealtimeState()
        private set
    var vehicleMarkers: PersistentList<MapVehicleMarker> = persistentListOf()
        private set
    var vehicleFrameRevision = 0L
        private set
    var vehicleBadgeRevision = 0L
        private set
    private var currentCity: TransitCity? = null
    private var selectedVehicleRoutes: List<TransitRoute> = emptyList()
    private var selectedRouteProjection = RouteSelectionProjection()
    private var realtimeVisibleAndStarted = false
    private var routeGeometrySnapshot = RouteGeometrySnapshot()
    private var vehicleRoutePathRevision: Long? = null
    private var vehicleRoutePaths: Map<RouteId, List<PreparedVehicleRoutePath>> = emptyMap()
    private val vehicleRouteMotions = mutableMapOf<VehicleId, CachedVehicleRouteMotion>()

    fun updateInputs(
        city: TransitCity?,
        routes: List<TransitRoute>,
        projection: RouteSelectionProjection,
        visibleAndStarted: Boolean,
        geometry: RouteGeometrySnapshot,
    ) {
        currentCity = city
        selectedVehicleRoutes = routes
        selectedRouteProjection = projection
        realtimeVisibleAndStarted = visibleAndStarted
        routeGeometrySnapshot = geometry
    }

    fun reset() {
        vehicleRealtimeState = VehicleRealtimeState()
        vehicleRouteMotions.clear()
        val vehicleSourceChanged = vehicleMarkers.isNotEmpty()
        vehicleMarkers = persistentListOf()
        if (vehicleSourceChanged) nextVehicleFrameRevision()
        if (vehicleSourceChanged) nextVehicleBadgeRevision()
    }

    fun markLoading(routeId: RouteId) {
        vehicleRealtimeState = VehicleRealtimeReducer.markLoading(vehicleRealtimeState, routeId)
    }

    fun acceptResult(
        routeId: RouteId,
        result: TransitLoadResult<VehiclePage>,
    ) {
        when (result) {
            is TransitLoadResult.Data -> when (
                val reduction = VehicleRealtimeReducer.acceptPage(
                    state = vehicleRealtimeState,
                    requestedRouteId = routeId,
                    page = result.value,
                    freshness = result.freshness,
                    wallReceivedAt = realtimeClock.wallNow(),
                    monotonicNowMillis = realtimeClock.monotonicNowMillis(),
                )
            ) {
                is VehiclePageReduction.Applied -> vehicleRealtimeState = reduction.state
                is VehiclePageReduction.IgnoredOlder -> vehicleRealtimeState = reduction.state
                is VehiclePageReduction.Rejected -> vehicleRealtimeState = reduction.state
            }
            is TransitLoadResult.Empty -> {
                vehicleRealtimeState = VehicleRealtimeReducer.acceptEmpty(vehicleRealtimeState, routeId, result.freshness)
            }
            is TransitLoadResult.Failure -> {
                vehicleRealtimeState = VehicleRealtimeReducer.handleFailure(vehicleRealtimeState, routeId, result.error)
            }
        }
    }

    private companion object {
        const val MAX_COMMON_VEHICLE_MARKERS = 1_000
        const val MAX_SMOOTHLY_ANIMATED_VEHICLES = 250
        const val HIGH_DENSITY_FRAME_INTERVAL_MILLIS = 100L
    }

    private fun nextVehicleFrameRevision(): Long {
        vehicleFrameRevision = if (vehicleFrameRevision == Long.MAX_VALUE) 1L else vehicleFrameRevision + 1L
        return vehicleFrameRevision
    }

    private fun nextVehicleBadgeRevision(): Long {
        vehicleBadgeRevision = if (vehicleBadgeRevision == Long.MAX_VALUE) 1L else vehicleBadgeRevision + 1L
        return vehicleBadgeRevision
    }

    fun updateVehicleFrames(forceSourceCheck: Boolean = false): VehicleFrameUpdate {
        val wallNow = realtimeClock.wallNow()
        val monotonicNow = realtimeClock.monotonicNowMillis()
        val unexpired = VehicleRealtimeReducer.expire(vehicleRealtimeState, wallNow)
        val tracksChanged = unexpired != vehicleRealtimeState
        vehicleRealtimeState = unexpired
        val routes = selectedVehicleRoutes.associateBy(TransitRoute::id)
        val visibleTracks = vehicleRealtimeState.tracks.asSequence()
            .filter { it.routeId in routes }
            .sortedWith(compareBy<VehicleTrack> { it.routeId.value }.thenBy { it.id.value })
            .take(MAX_COMMON_VEHICLE_MARKERS)
            .toList()
        if (tracksChanged || forceSourceCheck) {
            vehicleRouteMotions.keys.retainAll(visibleTracks.mapTo(mutableSetOf(), VehicleTrack::id))
        }
        val publishedMarkersById = vehicleMarkers.associateBy(MapVehicleMarker::id)
        val pendingTerminalTrackIds = visibleTracks
            .asSequence()
            .filter { track -> track.needsTerminalFrame(monotonicNow, publishedMarkersById[track.id]) }
            .take(MAX_SMOOTHLY_ANIMATED_VEHICLES)
            .mapTo(mutableSetOf(), VehicleTrack::id)
        val animatedTrackIds = pendingTerminalTrackIds.toMutableSet()
        visibleTracks.asSequence()
            .filter { track -> track.isInterpolationActiveAt(monotonicNow) }
            // Keep common source updates bounded. At high density every remaining marker is
            // published once per vehicle snapshot at its target position, rather than at 20 fps.
            .map(VehicleTrack::id)
            .filter { it !in animatedTrackIds }
            .take((MAX_SMOOTHLY_ANIMATED_VEHICLES - animatedTrackIds.size).coerceAtLeast(0))
            .forEach(animatedTrackIds::add)
        val hasFrameToRender = animatedTrackIds.isNotEmpty()
        if (!forceSourceCheck && !tracksChanged && !hasFrameToRender) {
            return VehicleFrameUpdate(accessibilityChanged = false, renderedSourceChanged = false)
        }
        val reusesStaticMetadata = !forceSourceCheck && !tracksChanged && vehicleMarkers.matches(visibleTracks)
        val nextMarkers = if (reusesStaticMetadata) {
            val tracksById = visibleTracks.associateBy(VehicleTrack::id)
            vehicleMarkers.map { marker ->
                val track = tracksById[marker.id]
                if (track != null && marker.id in animatedTrackIds) {
                    val nextPosition = vehicleMarkerPosition(track, monotonicNow, interpolate = true)
                    val nextBearing = vehicleMarkerBearing(track, monotonicNow, interpolate = true)
                    if (nextPosition == marker.position && nextBearing == marker.bearingDegrees) {
                        marker
                    } else {
                        marker.copy(position = nextPosition, bearingDegrees = nextBearing)
                    }
                } else {
                    marker
                }
            }.toPersistentList()
        } else {
            visibleTracks.asSequence()
                .mapNotNull { track ->
                    routes[track.routeId]?.let { route ->
                        track.toMarker(
                            route = route,
                            monotonicNowMillis = monotonicNow,
                            frameRevision = 0L,
                            interpolate = track.id in animatedTrackIds,
                        )
                    }
                }
                .toList()
                .toPersistentList()
        }
        if (nextMarkers.hasSameRenderedVehicleSourceAs(vehicleMarkers)) {
            return VehicleFrameUpdate(accessibilityChanged = tracksChanged, renderedSourceChanged = false)
        }
        val revision = nextVehicleFrameRevision()
        if (!nextMarkers.hasSameBadgeStylesAs(vehicleMarkers)) nextVehicleBadgeRevision()
        vehicleMarkers = if (reusesStaticMetadata) {
            nextMarkers.mapIndexed { index, marker ->
                if (marker === vehicleMarkers[index]) marker else marker.copy(frameRevision = revision)
            }.toPersistentList()
        } else {
            nextMarkers.map { it.copy(frameRevision = revision) }.toPersistentList()
        }
        return VehicleFrameUpdate(accessibilityChanged = tracksChanged, renderedSourceChanged = true)
    }

    private fun VehicleTrack.toMarker(
        route: TransitRoute,
        monotonicNowMillis: Long,
        frameRevision: Long,
        interpolate: Boolean,
    ): MapVehicleMarker? {
        val routeSelection = selectedRouteProjection.byId[route.id] ?: return null
        return MapVehicleMarker(
            id = id,
            routeId = routeId,
            directionId = directionId,
            position = vehicleMarkerPosition(this, monotonicNowMillis, interpolate),
            routeColorArgb = routeSelection.backgroundArgb,
            bearingDegrees = vehicleMarkerBearing(this, monotonicNowMillis, interpolate),
            positionKind = positionKind,
            freshness = freshness,
            routeLabel = routeSelection.displayLabel.sanitizedRouteBadgeLabel(),
            routeTextColorArgb = VEHICLE_ROUTE_TEXT_COLOR_ARGB,
            isStale = isStale,
            frameRevision = frameRevision,
        )
    }

    /**
     * Route geometry is prepared once per source revision, while each vehicle movement is
     * projected once per observation. The 20 fps frame path then only walks the prepared line.
     */
    private fun vehicleMarkerPosition(
        track: VehicleTrack,
        monotonicNowMillis: Long,
        interpolate: Boolean,
    ): GeoPoint {
        val motion = vehicleRouteMotion(track)
        if (motion == null) {
            // Provider GPS can be outside the route or the shape can be genuinely unusable. Keep
            // temporal continuity in that case: a short direct fallback is less disruptive than
            // teleporting the marker, while valid projections still follow the street geometry.
            return if (interpolate) VehicleRealtimeReducer.frame(track, monotonicNowMillis) else track.to
        }
        if (!interpolate) return motion.endPoint
        val startedAt = track.interpolationStartedAtMonotonicMillis ?: return motion.endPoint
        return motion.positionAt(interpolationFraction(startedAt, monotonicNowMillis))
    }

    /** Rotation is deliberately separate so it can never gate or replace the position frame. */
    private fun vehicleMarkerBearing(
        track: VehicleTrack,
        monotonicNowMillis: Long,
        interpolate: Boolean,
    ): Double? {
        val fallback = track.directTravelBearing ?: track.bearingDegrees
        val motion = vehicleRouteMotion(track) ?: return fallback
        val startedAt = track.interpolationStartedAtMonotonicMillis
        val fraction = if (interpolate && startedAt != null) interpolationFraction(startedAt, monotonicNowMillis) else 1.0
        return motion.bearingAt(fraction) ?: fallback
    }

    private fun vehicleRouteMotion(track: VehicleTrack): VehicleRouteMotion? {
        refreshVehicleRoutePaths()
        val routePaths = vehicleRoutePaths[track.routeId].orEmpty()
        val cached = vehicleRouteMotions[track.id]
        val motion = if (cached?.matches(track) == true) {
            cached.motion
        } else {
            VehicleRoutePathInterpolator.motion(
                from = track.from,
                to = track.to,
                directionId = track.directionId,
                candidates = routePaths,
                previousMotion = cached?.continuityMotionFor(track),
            ).also { resolved ->
                vehicleRouteMotions[track.id] = CachedVehicleRouteMotion.from(track, resolved)
            }
        }
        return motion
    }

    private fun interpolationFraction(startedAt: Long, monotonicNowMillis: Long): Double =
        (monotonicNowMillis - startedAt).coerceAtLeast(0L).toDouble() / VEHICLE_INTERPOLATION_DURATION_MILLIS

    private fun refreshVehicleRoutePaths() {
        val revision = routeGeometrySnapshot.polylineSourceRevision
        if (vehicleRoutePathRevision == revision) return
        vehicleRoutePathRevision = revision
        vehicleRoutePaths = VehicleRoutePathInterpolator.prepare(routeGeometrySnapshot.polylines)
        vehicleRouteMotions.clear()
    }

    /**
     * Typical maps animate at 20 fps. 251--1000 visible markers use 10 fps while only the first
     * 250 stable vehicle IDs move between snapshots, protecting the grouped GeoJSON source from
     * replacing a high-density collection every 50 ms.
     */
    fun vehicleFrameDelayMillis(): Long = when {
        vehicleRealtimeState.tracks.size > MAX_SMOOTHLY_ANIMATED_VEHICLES ->
            maxOf(realtimeTickerPolicy.frameIntervalMillis, HIGH_DENSITY_FRAME_INTERVAL_MILLIS)

        else -> realtimeTickerPolicy.frameIntervalMillis
    }

    private fun VehicleTrack.isInterpolationActiveAt(monotonicNowMillis: Long): Boolean =
        interpolationStartedAtMonotonicMillis?.let { started ->
            monotonicNowMillis - started in 0L..VEHICLE_INTERPOLATION_DURATION_MILLIS
        } == true

    /** A delayed ticker must still publish the route-projected endpoint exactly once. */
    private fun VehicleTrack.needsTerminalFrame(
        monotonicNowMillis: Long,
        publishedMarker: MapVehicleMarker?,
    ): Boolean = interpolationStartedAtMonotonicMillis?.let { started ->
        monotonicNowMillis - started > VEHICLE_INTERPOLATION_DURATION_MILLIS &&
            publishedMarker?.position != vehicleMarkerPosition(this, monotonicNowMillis, interpolate = false)
    } == true

    /** The fast frame path can retain immutable badge/style objects for every non-moving marker. */
    private fun List<MapVehicleMarker>.matches(tracks: List<VehicleTrack>): Boolean =
        size == tracks.size && zip(tracks).all { (marker, track) ->
            marker.id == track.id && marker.routeId == track.routeId && marker.directionId == track.directionId
        }

    fun vehicleAccessibilityItems(): List<VehicleRouteAccessibilityUi> = selectedVehicleRoutes
        .mapNotNull { route ->
            val selection = selectedRouteProjection.byId[route.id] ?: return@mapNotNull null
            VehicleRouteAccessibilityUi(
                routeId = route.id,
                routeLabel = selection.displayLabel.sanitizedRouteBadgeLabel(),
                vehicleCount = vehicleRealtimeState.tracks.count { it.routeId == route.id },
                layerState = vehicleLayerStateFor(route.id),
                backgroundArgb = selection.backgroundArgb,
                textArgb = selection.textArgb,
            )
        }

    fun initialVehicleLayerState(city: TransitCity?, routes: List<TransitRoute>): VehicleLayerState = when {
        city == null || routes.isEmpty() || !realtimeVisibleAndStarted -> VehicleLayerState.Hidden
        !city.capabilities.vehiclePositions -> VehicleLayerState.Unavailable
        else -> VehicleLayerState.Loading
    }

    fun vehicleLayerState(): VehicleLayerState {
        val city = currentCity
        if (city == null || selectedVehicleRoutes.isEmpty() || !realtimeVisibleAndStarted) return VehicleLayerState.Hidden
        if (!city.capabilities.vehiclePositions) return VehicleLayerState.Unavailable
        val phases = selectedVehicleRoutes.map { vehicleRealtimeState.routePhases[it.id] ?: VehicleRoutePhase.Loading }
        return when (phases.distinct()) {
            listOf(VehicleRoutePhase.Loading) -> VehicleLayerState.Loading
            listOf(VehicleRoutePhase.Live) -> VehicleLayerState.Live
            listOf(VehicleRoutePhase.Stale) -> VehicleLayerState.Stale
            listOf(VehicleRoutePhase.Retryable) -> VehicleLayerState.Retryable
            listOf(VehicleRoutePhase.Unavailable) -> VehicleLayerState.Unavailable
            else -> VehicleLayerState.Mixed
        }
    }

    private fun vehicleLayerStateFor(routeId: RouteId): VehicleLayerState {
        if (!realtimeVisibleAndStarted) return VehicleLayerState.Hidden
        if (currentCity?.capabilities?.vehiclePositions != true) return VehicleLayerState.Unavailable
        return when (vehicleRealtimeState.routePhases[routeId] ?: VehicleRoutePhase.Loading) {
            VehicleRoutePhase.Loading -> VehicleLayerState.Loading
            VehicleRoutePhase.Live -> VehicleLayerState.Live
            VehicleRoutePhase.Stale -> VehicleLayerState.Stale
            VehicleRoutePhase.Retryable -> VehicleLayerState.Retryable
            VehicleRoutePhase.Unavailable -> VehicleLayerState.Unavailable
        }
    }

}

/** Badge labels are local display text only; cap them before they cross the native image boundary. */
private fun String.sanitizedRouteBadgeLabel(): String = asSequence()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
    .joinToString(separator = "")
    .trim()
    .replace(WhitespacePattern, " ")
    .take(MAX_ROUTE_BADGE_LABEL_LENGTH)
    .ifBlank { "?" }

/** Matches exactly the vehicle properties consumed by the native grouped GeoJSON sources. */
private fun List<MapVehicleMarker>.hasSameRenderedVehicleSourceAs(other: List<MapVehicleMarker>): Boolean =
    size == other.size && zip(other).all { (next, current) ->
        next.id == current.id && next.position == current.position && next.bearingDegrees == current.bearingDegrees &&
            next.positionKind == current.positionKind && next.routeLabel == current.routeLabel &&
            next.routeColorArgb == current.routeColorArgb && next.routeTextColorArgb == current.routeTextColorArgb &&
            next.isStale == current.isStale
    }

/** Badge bitmap keys deliberately exclude geometry/frame revisions so animation never touches them. */
private fun List<MapVehicleMarker>.hasSameBadgeStylesAs(other: List<MapVehicleMarker>): Boolean =
    asSequence()
        .map { marker -> VehicleBadgeRenderStyle(marker.routeLabel, marker.routeColorArgb, marker.routeTextColorArgb, marker.isStale) }
        .toSet() == other.asSequence()
        .map { marker -> VehicleBadgeRenderStyle(marker.routeLabel, marker.routeColorArgb, marker.routeTextColorArgb, marker.isStale) }
        .toSet()

private data class VehicleBadgeRenderStyle(
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val stale: Boolean,
)

private data class CachedVehicleRouteMotion(
    val routeId: RouteId,
    val directionId: com.denis.georgiatransit.shared.domain.model.DirectionId?,
    val observationEpochMillis: Long,
    val from: GeoPoint,
    val to: GeoPoint,
    val motion: VehicleRouteMotion?,
) {
    fun matches(track: VehicleTrack): Boolean =
        routeId == track.routeId && directionId == track.directionId &&
            observationEpochMillis == track.observationEpochMillis && from == track.from && to == track.to

    fun continuityMotionFor(track: VehicleTrack): VehicleRouteMotion? = motion?.takeIf {
        routeId == track.routeId && directionId == track.directionId &&
            observationEpochMillis < track.observationEpochMillis
    }

    companion object {
        fun from(track: VehicleTrack, motion: VehicleRouteMotion?) = CachedVehicleRouteMotion(
            routeId = track.routeId,
            directionId = track.directionId,
            observationEpochMillis = track.observationEpochMillis,
            from = track.from,
            to = track.to,
            motion = motion,
        )
    }
}

private const val MAX_ROUTE_BADGE_LABEL_LENGTH = 8
private const val VEHICLE_ROUTE_TEXT_COLOR_ARGB = 0xFFFFFFFF
private val WhitespacePattern = Regex("\\s+")

/** Ticker publication is driven by observable vehicle output, never by an otherwise idle frame. */
internal data class VehicleFrameUpdate(
    val accessibilityChanged: Boolean,
    val renderedSourceChanged: Boolean,
) {
    val shouldPublish: Boolean get() = accessibilityChanged || renderedSourceChanged
}
