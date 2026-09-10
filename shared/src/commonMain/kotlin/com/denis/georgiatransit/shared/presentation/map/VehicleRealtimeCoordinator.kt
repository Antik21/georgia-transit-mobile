package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.canUseLastKnownGood
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/** Injectable time seam: wall time drives BFF TTL, monotonic time only drives interpolation. */
interface VehicleRealtimeClock {
    fun wallNow(): Instant
    fun monotonicNowMillis(): Long
}

class SystemVehicleRealtimeClock : VehicleRealtimeClock {
    private val started = TimeSource.Monotonic.markNow()

    override fun wallNow(): Instant = Clock.System.now()

    override fun monotonicNowMillis(): Long = started.elapsedNow().toLong(DurationUnit.MILLISECONDS)
}

/** The polling and animation cadence are deliberately injected instead of being implicit timing. */
interface VehicleRealtimeTickerPolicy {
    val pollIntervalMillis: Long
    val frameIntervalMillis: Long
}

data object DefaultVehicleRealtimeTickerPolicy : VehicleRealtimeTickerPolicy {
    override val pollIntervalMillis: Long = 8_000L
    override val frameIntervalMillis: Long = 50L // 20 fps maximum
}

@Immutable
internal enum class VehicleRoutePhase { Loading, Live, Stale, Retryable, Unavailable }

@Immutable
internal data class VehicleTrack(
    /** Retained across a guarded nearest-neighbour stitch so map identity does not flicker. */
    val id: VehicleId,
    val routeId: RouteId,
    val directionId: DirectionId?,
    val from: GeoPoint,
    val to: GeoPoint,
    val bearingDegrees: Double?,
    val positionKind: VehiclePositionKind,
    /** Original provider observation time, never refreshed by polling failures. */
    val observedAt: Instant,
    /**
     * The last accepted observation epoch for this individual vehicle identity. A route page can
     * be newer while an individual vehicle row is unchanged or regresses, so page ordering alone
     * must not permit a coordinate jump.
     */
    val observationEpochMillis: Long = observedAt.toEpochMilliseconds(),
    val expiresAt: Instant,
    val freshness: TransitFreshness,
    val isStale: Boolean,
    val interpolationStartedAtMonotonicMillis: Long?,
)

@Immutable
internal data class VehicleRealtimeState(
    val tracks: PersistentList<VehicleTrack> = persistentListOf(),
    val latestObservedAtByRoute: PersistentMap<RouteId, Instant> = persistentMapOf(),
    val routePhases: PersistentMap<RouteId, VehicleRoutePhase> = persistentMapOf(),
    val pausedRoutes: PersistentSet<RouteId> = persistentSetOf(),
)

internal sealed interface VehiclePageReduction {
    data class Applied(val state: VehicleRealtimeState) : VehiclePageReduction
    data class IgnoredOlder(val state: VehicleRealtimeState) : VehiclePageReduction
    /** A malformed page is terminal for this route until selection/capability input changes. */
    data class Rejected(val state: VehicleRealtimeState) : VehiclePageReduction
}

/**
 * Pure common reducer for ephemeral vehicle frames. It stores no cacheable state and treats a
 * malformed page as all-or-nothing, so partially decoded provider responses cannot overwrite a
 * deterministic frame.
 */
internal object VehicleRealtimeReducer {
    fun acceptPage(
        state: VehicleRealtimeState,
        requestedRouteId: RouteId,
        page: VehiclePage,
        freshness: TransitFreshness,
        wallReceivedAt: Instant,
        monotonicNowMillis: Long,
    ): VehiclePageReduction {
        // Reject untrusted oversized input before any timestamp validation, sorting, or matching.
        if (requestedRouteId.value.isBlank() || page.items.size > MAX_VISIBLE_VEHICLES) {
            return VehiclePageReduction.Rejected(state.clearAndPause(requestedRouteId))
        }
        val deadline = page.deadlineAt(wallReceivedAt)
        if (
            !page.observedAt.isSafeRealtimeInstant() || !page.observedAt.isNotTooFarInFuture(wallReceivedAt) ||
            deadline == null || !page.isValidFor(requestedRouteId, wallReceivedAt)
        ) {
            return VehiclePageReduction.Rejected(state.clearAndPause(requestedRouteId))
        }
        val previousObservedAt = state.latestObservedAtByRoute[requestedRouteId]
        if (previousObservedAt != null && page.observedAt < previousObservedAt) {
            return VehiclePageReduction.IgnoredOlder(state)
        }
        if (page.stale && page.items.isEmpty()) {
            return VehiclePageReduction.Applied(
                state.markRouteStale(requestedRouteId).copy(
                    latestObservedAtByRoute = state.latestObservedAtByRoute.put(requestedRouteId, page.observedAt),
                ),
            )
        }
        if (!page.stale && page.items.isEmpty()) {
            return VehiclePageReduction.Applied(
                state.copy(
                    tracks = state.tracks.filterNot { it.routeId == requestedRouteId }.toPersistentList(),
                    latestObservedAtByRoute = state.latestObservedAtByRoute.put(requestedRouteId, page.observedAt),
                    routePhases = state.routePhases.put(requestedRouteId, VehicleRoutePhase.Live),
                ),
            )
        }

        val incoming = page.items.sortedBy { it.id.value }
        val incomingById = incoming.associateBy(TransitVehicle::id)
        val allPrevious = state.tracks
        val previousById = allPrevious.associateBy(VehicleTrack::id)
        val incomingIds = incoming.mapTo(mutableSetOf()) { it.id }
        val sameRoute = allPrevious.filter { it.routeId == requestedRouteId }
        val claimedPreviousIds = mutableSetOf<VehicleId>()
        val accepted = mutableListOf<VehicleTrack>()
        var rejectedExactUpdate = false

        // Exact opaque VehicleId equality is always preferred; no provider namespace is inspected.
        incoming.forEach { vehicle ->
            previousById[vehicle.id]?.let { previous ->
                claimedPreviousIds += previous.id
                val updated = previous.acceptExactUpdate(
                    vehicle = vehicle,
                    page = page,
                    deadline = deadline,
                    freshness = freshness,
                    monotonicNowMillis = monotonicNowMillis,
                )
                if (updated == null) {
                    // A newer route page is not proof that this particular row advanced. Keep the
                    // last known geometry rather than allowing a duplicate/regressing ID to jump.
                    accepted += previous.holdStale()
                    rejectedExactUpdate = true
                } else {
                    accepted += updated
                }
            }
        }

        val unmatchedIncoming = incoming.filter { it.id !in previousById }
        val stitchCandidates = sameRoute.filter { it.id !in claimedPreviousIds && !it.isStale }
        val stitches = when (val stitching = boundedStitches(stitchCandidates, unmatchedIncoming, page.observedAt)) {
            is VehicleStitchReduction.Matched -> stitching.matches
            VehicleStitchReduction.ScanBudgetExceeded -> {
                // Exact IDs have already been handled above, but a dense heuristic match is not
                // safe to guess. Keep the prior route as visibly stale and wait for a later page.
                return VehiclePageReduction.Applied(
                    state.markRouteStale(requestedRouteId).copy(
                        latestObservedAtByRoute = state.latestObservedAtByRoute.put(requestedRouteId, page.observedAt),
                    ),
                )
            }
        }
        val stitchedIncomingIds = stitches.values.toSet()
        stitches.forEach { (previousId, incomingId) ->
            val previous = checkNotNull(previousById[previousId])
            val vehicle = checkNotNull(incomingById[incomingId])
            claimedPreviousIds += previousId
            accepted += previous.stitchedFrom(
                vehicle = vehicle,
                page = page,
                deadline = deadline,
                freshness = freshness,
            )
        }
        unmatchedIncoming.filter { it.id !in stitchedIncomingIds }.forEach { vehicle ->
            accepted += vehicle.newTrack(page, deadline, freshness)
        }

        // A fresh route page is authoritative for that route. Exact IDs can safely move routes.
        val retained = allPrevious.filter { it.routeId != requestedRouteId && it.id !in incomingIds }
        val next = (retained + accepted)
            .sortedWith(compareBy<VehicleTrack> { it.routeId.value }.thenBy { it.id.value })
            .take(MAX_VISIBLE_VEHICLES)
            .toPersistentList()
        return VehiclePageReduction.Applied(
            state.copy(
                tracks = next,
                latestObservedAtByRoute = state.latestObservedAtByRoute.put(requestedRouteId, page.observedAt),
                routePhases = state.routePhases.put(
                    requestedRouteId,
                    when {
                        rejectedExactUpdate || page.stale || freshness == TransitFreshness.StaleOffline -> VehicleRoutePhase.Stale
                        else -> VehicleRoutePhase.Live
                    },
                ),
                pausedRoutes = state.pausedRoutes.remove(requestedRouteId),
            ),
        )
    }

    fun markLoading(state: VehicleRealtimeState, routeId: RouteId): VehicleRealtimeState =
        if (state.tracks.any { it.routeId == routeId }) state else state.copy(
            routePhases = state.routePhases.put(routeId, VehicleRoutePhase.Loading),
        )

    fun acceptEmpty(
        state: VehicleRealtimeState,
        routeId: RouteId,
        freshness: TransitFreshness,
    ): VehicleRealtimeState = if (freshness == TransitFreshness.StaleOffline) {
        state.markRouteStale(routeId)
    } else {
        state.copy(
            tracks = state.tracks.filterNot { it.routeId == routeId }.toPersistentList(),
            routePhases = state.routePhases.put(routeId, VehicleRoutePhase.Live),
            pausedRoutes = state.pausedRoutes.remove(routeId),
        )
    }

    fun handleFailure(
        state: VehicleRealtimeState,
        routeId: RouteId,
        failure: TransitFailure,
    ): VehicleRealtimeState = if (failure.canUseLastKnownGood) {
        state.markRouteStale(routeId, VehicleRoutePhase.Retryable)
    } else {
        state.clearAndPause(routeId)
    }

    fun expire(state: VehicleRealtimeState, wallNow: Instant): VehicleRealtimeState {
        val remaining = state.tracks.filter { it.expiresAt > wallNow }.toPersistentList()
        if (remaining == state.tracks) return state

        val routesWhoseLastTrackExpired = state.tracks
            .asSequence()
            .map(VehicleTrack::routeId)
            .distinct()
            .filter { routeId ->
                state.tracks.any { it.routeId == routeId && it.expiresAt <= wallNow } &&
                    remaining.none { it.routeId == routeId }
            }
            .toList()
        val phases = routesWhoseLastTrackExpired.fold(state.routePhases) { phases, routeId ->
            // Live with no markers is reserved for a fresh authoritative empty page. Expiry is
            // instead visible as stale/retryable state, without affecting other selected routes.
            val nextPhase = when (phases[routeId]) {
                VehicleRoutePhase.Retryable -> VehicleRoutePhase.Retryable
                VehicleRoutePhase.Unavailable -> VehicleRoutePhase.Unavailable
                else -> VehicleRoutePhase.Stale
            }
            phases.put(routeId, nextPhase)
        }
        return state.copy(tracks = remaining, routePhases = phases)
    }

    fun frame(track: VehicleTrack, monotonicNowMillis: Long): GeoPoint {
        val startedAt = track.interpolationStartedAtMonotonicMillis ?: return track.to
        val elapsed = (monotonicNowMillis - startedAt).coerceAtLeast(0L)
        val fraction = (elapsed.toDouble() / VEHICLE_INTERPOLATION_DURATION_MILLIS).coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = track.from.latitude + (track.to.latitude - track.from.latitude) * fraction,
            longitude = track.from.longitude + (track.to.longitude - track.from.longitude) * fraction,
        )
    }

    private fun VehicleRealtimeState.markRouteStale(
        routeId: RouteId,
        phase: VehicleRoutePhase = VehicleRoutePhase.Stale,
    ): VehicleRealtimeState = copy(
        tracks = tracks.map { track -> if (track.routeId == routeId) track.holdStale() else track }
            .toPersistentList(),
        routePhases = routePhases.put(routeId, phase),
    )

    private fun VehicleRealtimeState.clearAndPause(routeId: RouteId): VehicleRealtimeState = copy(
        tracks = tracks.filterNot { it.routeId == routeId }.toPersistentList(),
        routePhases = routePhases.put(routeId, VehicleRoutePhase.Unavailable),
        pausedRoutes = pausedRoutes.add(routeId),
    )

    private fun VehiclePage.isValidFor(requestedRouteId: RouteId, wallReceivedAt: Instant): Boolean {
        val seen = mutableSetOf<VehicleId>()
        return items.all { vehicle ->
            vehicle.id.value.isNotBlank() && seen.add(vehicle.id) && vehicle.routeId == requestedRouteId &&
                vehicle.routeId.value.isNotBlank() && vehicle.directionId?.value?.isNotBlank() != false &&
                vehicle.position.isMapCoordinate() && vehicle.bearing?.isFinite() != false &&
                vehicle.observedAt?.isSafeRealtimeInstant() != false &&
                vehicle.observedAt?.isNotTooFarInFuture(wallReceivedAt) != false &&
                vehicle.ageSeconds?.let { it >= 0 } != false
        }
    }

    private fun VehicleTrack.acceptExactUpdate(
        vehicle: TransitVehicle,
        page: VehiclePage,
        deadline: Instant,
        freshness: TransitFreshness,
        monotonicNowMillis: Long,
    ): VehicleTrack? {
        val observation = vehicle.observedAt ?: page.observedAt
        val observationEpochMillis = observation.toEpochMilliseconds()
        val isStale = page.stale || freshness == TransitFreshness.StaleOffline
        val routeChanged = routeId != vehicle.routeId || directionId != vehicle.directionId
        val gapMillis = observationEpochMillis - this.observationEpochMillis
        val distanceMeters = distanceMeters(from = to, to = vehicle.position)
        val validMovement = !isStale && !routeChanged &&
            gapMillis in 1..MAX_OBSERVATION_GAP_MILLIS &&
            distanceMeters <= MAX_STITCH_DISTANCE_METERS &&
            distanceMeters / (gapMillis / 1_000.0) <= MAX_SPEED_METERS_PER_SECOND
        if (!validMovement) return null

        // A stale predecessor can recover only through this same bounded interpolation, never by
        // snapping an exact opaque ID to an arbitrary coordinate.
        return VehicleTrack(
            id = id,
            routeId = vehicle.routeId,
            directionId = vehicle.directionId,
            from = frame(this, monotonicNowMillis),
            to = vehicle.position,
            bearingDegrees = vehicle.bearing,
            positionKind = vehicle.positionKind,
            observedAt = observation,
            observationEpochMillis = observationEpochMillis,
            expiresAt = deadline,
            freshness = freshness,
            isStale = false,
            interpolationStartedAtMonotonicMillis = monotonicNowMillis,
        )
    }

    private fun VehicleTrack.stitchedFrom(
        vehicle: TransitVehicle,
        page: VehiclePage,
        deadline: Instant,
        freshness: TransitFreshness,
    ): VehicleTrack {
        val observation = vehicle.observedAt ?: page.observedAt
        return VehicleTrack(
            id = id,
            routeId = vehicle.routeId,
            directionId = vehicle.directionId,
            // A stitched identity is intentionally not interpolated: it has no exact vehicle ID
            // continuity and therefore appears as a new observation at a guarded nearby point.
            from = vehicle.position,
            to = vehicle.position,
            bearingDegrees = vehicle.bearing,
            positionKind = vehicle.positionKind,
            observedAt = observation,
            observationEpochMillis = observation.toEpochMilliseconds(),
            expiresAt = deadline,
            freshness = freshness,
            isStale = page.stale || freshness == TransitFreshness.StaleOffline,
            interpolationStartedAtMonotonicMillis = null,
        )
    }

    private fun VehicleTrack.holdStale(): VehicleTrack = copy(
        isStale = true,
        interpolationStartedAtMonotonicMillis = null,
    )

    private fun TransitVehicle.newTrack(page: VehiclePage, deadline: Instant, freshness: TransitFreshness): VehicleTrack {
        val observation = observedAt ?: page.observedAt
        return VehicleTrack(
            id = id,
            routeId = routeId,
            directionId = directionId,
            from = position,
            to = position,
            bearingDegrees = bearing,
            positionKind = positionKind,
            observedAt = observation,
            observationEpochMillis = observation.toEpochMilliseconds(),
            expiresAt = deadline,
            freshness = freshness,
            isStale = page.stale || freshness == TransitFreshness.StaleOffline,
            interpolationStartedAtMonotonicMillis = null,
        )
    }

    /**
     * Greedy global matching on sorted pair costs is deterministic and one-to-one. Coordinates
     * are bucketed in equatorial earth metres: latitude has a fixed physical span and longitude
     * widens with latitude, so every pair within the Haversine 250 m bound is inspected.
     */
    private fun boundedStitches(
        candidates: List<VehicleTrack>,
        incoming: List<TransitVehicle>,
        pageObservedAt: Instant,
    ): VehicleStitchReduction {
        val orderedIncoming = incoming.sortedBy { it.id.value }
        val grid = mutableMapOf<StitchBucket, MutableList<TransitVehicle>>()
        val groupedIncoming = mutableMapOf<StitchGroup, MutableList<TransitVehicle>>()
        orderedIncoming.forEach { vehicle ->
            // The page hard-limit is 1,000, so preserve every dense-bucket candidate rather than
            // silently retaining only the lowest opaque IDs.
            grid.getOrPut(vehicle.stitchBucket()) { mutableListOf() } += vehicle
            groupedIncoming.getOrPut(vehicle.stitchGroup()) { mutableListOf() } += vehicle
        }
        val scanPlans = candidates
            .sortedBy { it.id.value }
            .map { previous ->
                previous.stitchScanPlan(
                    grid = grid,
                    sameRouteAndDirection = groupedIncoming[previous.stitchGroup()].orEmpty(),
                )
            }
        var scanBudgetUsed = 0L
        scanPlans.forEach { plan ->
            scanBudgetUsed += plan.candidateCount
            if (scanBudgetUsed > MAX_STITCH_CANDIDATE_PAIR_SCANS) {
                return VehicleStitchReduction.ScanBudgetExceeded
            }
        }
        val pairs = scanPlans
            .flatMap { plan -> plan.exactTopPairs(pageObservedAt) }
            .sortedWith(
                compareBy<StitchPair> { it.distanceMeters }
                    .thenBy { it.previous.id.value }
                    .thenBy { it.vehicle.id.value },
            )
        val usedPrevious = mutableSetOf<VehicleId>()
        val usedIncoming = mutableSetOf<VehicleId>()
        return VehicleStitchReduction.Matched(buildMap {
            pairs.forEach { pair ->
                if (usedPrevious.add(pair.previous.id) && usedIncoming.add(pair.vehicle.id)) {
                    put(pair.previous.id, pair.vehicle.id)
                }
            }
        })
    }

    /**
     * Collect list references and count them before calculating a single vehicle distance. This
     * makes dense cells and polar fallback deterministic fail-closed work rather than a CPU spike.
     */
    private fun VehicleTrack.stitchScanPlan(
        grid: Map<StitchBucket, List<TransitVehicle>>,
        sameRouteAndDirection: List<TransitVehicle>,
    ): StitchScanPlan {
        val center = stitchBucket()
        val horizontalSpan = to.stitchGridHorizontalSpan()
        if (horizontalSpan == null || horizontalSpan > MAX_SPARSE_GRID_HORIZONTAL_SPAN) {
            // Close to a pole the physical longitude window can cover most of the world. A full
            // same-route/direction scan is still bounded by the already rejected 1,000-item page.
            return StitchScanPlan(this, listOf(sameRouteAndDirection))
        }
        val candidateBuckets = mutableListOf<List<TransitVehicle>>()
        for (latitudeOffset in -STITCH_GRID_VERTICAL_SPAN..STITCH_GRID_VERTICAL_SPAN) {
            for (longitudeOffset in -horizontalSpan..horizontalSpan) {
                val bucket = center.copy(
                    longitudeBucket = (center.longitudeBucket + longitudeOffset).wrapStitchLongitudeBucket(),
                    latitudeBucket = center.latitudeBucket + latitudeOffset,
                )
                grid[bucket]?.let(candidateBuckets::add)
            }
        }
        return StitchScanPlan(this, candidateBuckets)
    }

    /** Keep a bounded sorted top-K without allocating or sorting a full per-track candidate list. */
    private fun MutableList<ApproximateStitchPair>.insertBoundedByCost(candidate: ApproximateStitchPair) {
        val insertionIndex = indexOfFirst { current ->
            candidate.isWithinApproximateContract && !current.isWithinApproximateContract ||
                (candidate.isWithinApproximateContract == current.isWithinApproximateContract &&
                    (candidate.distanceSquaredMeters < current.distanceSquaredMeters ||
                        (candidate.distanceSquaredMeters == current.distanceSquaredMeters &&
                            candidate.vehicle.id.value < current.vehicle.id.value)))
        }.let { if (it < 0) size else it }
        if (insertionIndex >= MAX_STITCH_CANDIDATES_PER_TRACK && size >= MAX_STITCH_CANDIDATES_PER_TRACK) return
        add(insertionIndex, candidate)
        if (size > MAX_STITCH_CANDIDATES_PER_TRACK) removeAt(lastIndex)
    }

    private fun VehicleTrack.stitchBucket(): StitchBucket =
        StitchBucket(routeId, directionId, to.stitchLongitudeBucket(), to.stitchLatitudeBucket())

    private fun TransitVehicle.stitchBucket(): StitchBucket =
        StitchBucket(routeId, directionId, position.stitchLongitudeBucket(), position.stitchLatitudeBucket())

    private fun VehicleTrack.stitchGroup(): StitchGroup = StitchGroup(routeId, directionId)

    private fun TransitVehicle.stitchGroup(): StitchGroup = StitchGroup(routeId, directionId)

    private data class StitchScanPlan(
        val previous: VehicleTrack,
        val candidateBuckets: List<List<TransitVehicle>>,
    ) {
        val candidateCount: Long get() = candidateBuckets.sumOf { it.size.toLong() }

        /**
         * The budget was consumed before this loop. Use equirectangular squared metres to stream
         * the deterministic top-K; exact Haversine/speed checks occur only for those K entries.
         */
        fun exactTopPairs(pageObservedAt: Instant): List<StitchPair> {
            val nearest = mutableListOf<ApproximateStitchPair>()
            candidateBuckets.forEach { candidates ->
                candidates.forEach { vehicle ->
                    val observationAt = vehicle.observedAt ?: pageObservedAt
                    val gapMillis = observationAt.toEpochMilliseconds() - previous.observationEpochMillis
                    if (gapMillis in 1..MAX_OBSERVATION_GAP_MILLIS) {
                        val approximatePair = ApproximateStitchPair(
                            previous = previous,
                            vehicle = vehicle,
                            gapMillis = gapMillis,
                            maximumContractDistanceMeters = stitchMaximumDistanceMeters(gapMillis),
                            distanceSquaredMeters = approximateDistanceSquaredMeters(previous.to, vehicle.position),
                        )
                        // The chord is a physical lower bound. With a fixed epsilon this rejects
                        // only candidates that are clearly too far for their own observation gap.
                        if (approximatePair.isConservativelySpeedAdmissible) nearest.insertBoundedByCost(approximatePair)
                    }
                }
            }
            return nearest.mapNotNull(ApproximateStitchPair::toExactEligiblePair)
        }
    }

    private data class ApproximateStitchPair(
        val previous: VehicleTrack,
        val vehicle: TransitVehicle,
        val gapMillis: Long,
        val maximumContractDistanceMeters: Double,
        val distanceSquaredMeters: Double,
    ) {
        /** Every exactly admissible pair is in this first ranking band because chord <= arc. */
        val isWithinApproximateContract: Boolean
            get() = distanceSquaredMeters <= maximumContractDistanceMeters * maximumContractDistanceMeters

        /** Epsilon prevents floating-point chord rounding from excluding an exactly valid pair. */
        val isConservativelySpeedAdmissible: Boolean
            get() = distanceSquaredMeters <= (maximumContractDistanceMeters + SPEED_PREFILTER_SAFETY_METERS).let { it * it }

        fun toExactEligiblePair(): StitchPair? {
            val distanceMeters = distanceMeters(previous.to, vehicle.position)
            return StitchPair(previous, vehicle, distanceMeters, gapMillis).takeIf(StitchPair::isEligible)
        }
    }

    private data class StitchPair(
        val previous: VehicleTrack,
        val vehicle: TransitVehicle,
        val distanceMeters: Double,
        val gapMillis: Long,
    ) {
        fun isEligible(): Boolean {
            return gapMillis in 1..MAX_OBSERVATION_GAP_MILLIS && distanceMeters <= MAX_STITCH_DISTANCE_METERS &&
                distanceMeters / (gapMillis / 1_000.0) <= MAX_SPEED_METERS_PER_SECOND
        }
    }

    private sealed interface VehicleStitchReduction {
        data class Matched(val matches: Map<VehicleId, VehicleId>) : VehicleStitchReduction
        data object ScanBudgetExceeded : VehicleStitchReduction
    }

    private data class StitchBucket(
        val routeId: RouteId,
        val directionId: DirectionId?,
        val longitudeBucket: Long,
        val latitudeBucket: Long,
    )

    private data class StitchGroup(
        val routeId: RouteId,
        val directionId: DirectionId?,
    )
}

/**
 * TTL stays anchored to the BFF observation, but a malicious/future timestamp cannot prolong a
 * frame beyond the time at which this response was received plus the same bounded TTL.
 */
private fun VehiclePage.deadlineAt(wallReceivedAt: Instant): Instant? {
    val ttl = clampRealtimeTtl(maxAgeSeconds)
    val observedDeadline = observedAt.safePlus(ttl) ?: return null
    val receivedDeadline = wallReceivedAt.safePlus(ttl) ?: return null
    return minOf(observedDeadline, receivedDeadline)
}

private fun clampRealtimeTtl(seconds: Int): Duration =
    seconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS).toLong().toDuration(DurationUnit.SECONDS)

private fun Instant.isSafeRealtimeInstant(): Boolean = runCatching {
    toEpochMilliseconds() in MIN_REALTIME_EPOCH_MILLIS..MAX_REALTIME_EPOCH_MILLIS
}.getOrDefault(false)

private fun Instant.isNotTooFarInFuture(wallReceivedAt: Instant): Boolean {
    val cutoff = wallReceivedAt.safePlus(MAX_FUTURE_OBSERVATION_SKEW) ?: return false
    return this <= cutoff
}

private fun Instant.safePlus(duration: Duration): Instant? = runCatching { this + duration }.getOrNull()

private fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
    val latitudeDelta = (to.latitude - from.latitude) * PI / 180.0
    val longitudeDelta = (to.longitude - from.longitude) * PI / 180.0
    val firstLatitude = from.latitude * PI / 180.0
    val secondLatitude = to.latitude * PI / 180.0
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0).let { it * it }
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

/**
 * A chord is a globally conservative physical distance: it is monotonic with the spherical arc
 * but never greater than it. That makes it safe for a speed prefilter, including at the poles.
 */
private fun approximateDistanceSquaredMeters(from: GeoPoint, to: GeoPoint): Double {
    val latitudeDelta = (to.latitude - from.latitude) * PI / 180.0
    val longitudeDelta = (to.longitude - from.longitude) * PI / 180.0
    val fromLatitude = from.latitude * PI / 180.0
    val toLatitude = to.latitude * PI / 180.0
    // chord² = 4R² sin²(centralAngle / 2). This is the stable Haversine-a form rather
    // than 1 - dotProduct, which loses identical/near-identical positions to cancellation.
    val haversineA = (
        sin(latitudeDelta / 2.0).let { it * it } +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2.0).let { it * it }
        ).coerceIn(0.0, 1.0)
    return 4.0 * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * haversineA
}

private fun stitchMaximumDistanceMeters(gapMillis: Long): Double = minOf(
    MAX_STITCH_DISTANCE_METERS,
    MAX_SPEED_METERS_PER_SECOND * gapMillis / 1_000.0,
)

/** Latitude is an exact arc-length coordinate on the same sphere used by [distanceMeters]. */
private fun GeoPoint.stitchLatitudeBucket(): Long =
    floor(EARTH_RADIUS_METERS * latitude * PI / 180.0 / STITCH_GRID_CELL_METERS).toLong()

/** Longitude stays indexed at the equator and wraps around the antimeridian deterministically. */
private fun GeoPoint.stitchLongitudeBucket(): Long =
    floor(EARTH_RADIUS_METERS * (longitude + 180.0) * PI / 180.0 / STITCH_GRID_CELL_METERS)
        .toLong()
        .coerceIn(0L, STITCH_LONGITUDE_BUCKET_COUNT - 1L)

/**
 * Any 250 m Haversine neighbour is within this latitude-aware number of equatorial-longitude
 * cells. Returning null means the pole-safe full same-group scan must be used instead.
 */
private fun GeoPoint.stitchGridHorizontalSpan(): Int? {
    val latitudeDeltaRadians = MAX_STITCH_DISTANCE_METERS / EARTH_RADIUS_METERS
    val lowestCosine = cos(
        maxOf(
            abs(latitude * PI / 180.0 - latitudeDeltaRadians),
            abs(latitude * PI / 180.0 + latitudeDeltaRadians),
        ).coerceAtMost(PI / 2.0),
    )
    val longitudeHalfAngle = sin(MAX_STITCH_DISTANCE_METERS / (2.0 * EARTH_RADIUS_METERS)) / lowestCosine
    if (!longitudeHalfAngle.isFinite() || longitudeHalfAngle >= 1.0) return null
    val longitudeWindowMeters = 2.0 * asin(longitudeHalfAngle) * EARTH_RADIUS_METERS
    return (ceil(longitudeWindowMeters / STITCH_GRID_CELL_METERS).toInt() + STITCH_GRID_BUCKET_EDGE_ALLOWANCE)
        .takeIf { it >= 0 }
}

private fun Long.wrapStitchLongitudeBucket(): Long =
    ((this % STITCH_LONGITUDE_BUCKET_COUNT) + STITCH_LONGITUDE_BUCKET_COUNT) % STITCH_LONGITUDE_BUCKET_COUNT

private const val MIN_TTL_SECONDS = 1
private const val MAX_TTL_SECONDS = 90
/**
 * A common cap protects the reducer before native adapters see an untrusted page. One thousand
 * remains readable at city scale and lets the map animate only a small deterministic subset.
 */
private const val MAX_VISIBLE_VEHICLES = 1_000
private const val MAX_OBSERVATION_GAP_MILLIS = 20_000L
private const val MAX_STITCH_DISTANCE_METERS = 250.0
private const val MAX_SPEED_METERS_PER_SECOND = 25.0
private const val MAX_STITCH_CANDIDATES_PER_TRACK = 32
/** Total unmatched heuristic pairs inspected per page, before any Haversine calculation. */
private const val MAX_STITCH_CANDIDATE_PAIR_SCANS = 100_000L
/** Retain near-boundary pairs for the exact final gate without admitting visibly impossible speed. */
private const val SPEED_PREFILTER_SAFETY_METERS = 0.05
private const val STITCH_GRID_CELL_METERS = 125.0
private const val STITCH_GRID_VERTICAL_SPAN = 3
private const val STITCH_GRID_BUCKET_EDGE_ALLOWANCE = 1
private const val MAX_SPARSE_GRID_HORIZONTAL_SPAN = 32
/** Nearly spans the default eight-second poll interval, avoiding one-second motion plus a long stop. */
internal const val VEHICLE_INTERPOLATION_DURATION_MILLIS = 7_500L
private const val EARTH_RADIUS_METERS = 6_371_008.8
private val STITCH_LONGITUDE_BUCKET_COUNT = ceil(2.0 * PI * EARTH_RADIUS_METERS / STITCH_GRID_CELL_METERS).toLong()
private val MAX_FUTURE_OBSERVATION_SKEW = 30L.toDuration(DurationUnit.SECONDS)
private const val MIN_REALTIME_EPOCH_MILLIS = 1_577_836_800_000L // 2020-01-01
private const val MAX_REALTIME_EPOCH_MILLIS = 4_102_444_800_000L // 2100-01-01
