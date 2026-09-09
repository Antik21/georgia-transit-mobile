package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Clock

/** Injectable wall-clock seam for the deliberately entry-local route-shape memory cache. */
fun interface RouteGeometryClock {
    fun nowEpochMillis(): Long
}

data object SystemRouteGeometryClock : RouteGeometryClock {
    override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

@Immutable
sealed interface RouteGeometryLegendState {
    @Immutable data object Loading : RouteGeometryLegendState
    @Immutable data object Ready : RouteGeometryLegendState
    /** At least one direction is visible, while another declared direction is not. */
    @Immutable data object Partial : RouteGeometryLegendState
    /** No direction is visible and retrying can ask only for the missing directions. */
    @Immutable data object Retryable : RouteGeometryLegendState
    /** Geometry is disabled, absent, malformed, or outside the shared renderer safety cap. */
    @Immutable data object Unavailable : RouteGeometryLegendState
    /** Selection exceeded the shared distinct-color capacity; no ambiguous line is emitted. */
    @Immutable data object PaletteOverflow : RouteGeometryLegendState
}

/** Distinguishes a renderable route color from an explicit selection-limit overflow. */
enum class RouteGeometryColorAvailability {
    Assigned,
    PaletteOverflow,
}

/** Route-level UI contract; typed IDs remain common and provider errors never enter this model. */
@Immutable
data class RouteGeometryLegendUi(
    val routeId: RouteId,
    val routeLabel: String,
    val colorArgb: Long,
    /** A neutral chip is used for overflow; no polyline is emitted for that route. */
    val colorAvailability: RouteGeometryColorAvailability,
    val state: RouteGeometryLegendState,
    val successfulDirections: Int,
    val totalDirections: Int,
    val isFocused: Boolean,
    val canRetry: Boolean,
)

@Immutable
data class RouteGeometrySnapshot(
    val polylines: PersistentList<MapPolyline> = persistentListOf(),
    val legends: PersistentList<RouteGeometryLegendUi> = persistentListOf(),
    /** Changes only when the ordered grouped-polyline source needs replacing. */
    val polylineSourceRevision: Long = 0L,
)

/** A unique, normalized BFF shape endpoint. It is never constructed from an opaque string. */
private data class RouteGeometryKey(
    val cityId: CityId,
    val routeId: RouteId,
    val directionId: DirectionId,
)

private data class DirectionSpec(
    val key: RouteGeometryKey,
    val route: TransitRoute,
)

private data class GeometryInput(
    val cityId: CityId?,
    val routeGeometryEnabled: Boolean,
    val routes: List<TransitRoute>,
    val isVisibleAndStarted: Boolean,
)

private sealed interface DirectionPhase {
    data object Loading : DirectionPhase
    data class Ready(
        val points: PersistentList<GeoPoint>,
        val freshness: com.denis.georgiatransit.shared.domain.repository.TransitFreshness,
        val storedAtEpochMillis: Long,
    ) : DirectionPhase
    data object Retryable : DirectionPhase
    data object Unavailable : DirectionPhase
}

private data class CachedDirection(
    val points: PersistentList<GeoPoint>,
    val freshness: com.denis.georgiatransit.shared.domain.repository.TransitFreshness,
    val storedAtEpochMillis: Long,
) {
    val vertexCount: Int get() = points.size
}

private data class InFlightDirection(
    val generation: Long,
    val job: Job,
)

/**
 * Coordinates bounded route-shape fetching for one Map entry. Shapes deliberately stay only in
 * this bounded in-memory cache; neither raw encoded values nor decoded points are persisted.
 *
 * All calls originate on the Map ViewModel scope. Request completion still validates membership
 * and generation, because a repository implementation is allowed to be non-cooperative when a
 * coroutine is cancelled.
 */
class RouteGeometryCoordinator(
    private val repository: TransitRepository,
    private val clock: RouteGeometryClock = SystemRouteGeometryClock,
) {
    private var input = GeometryInput(null, routeGeometryEnabled = false, routes = emptyList(), isVisibleAndStarted = false)
    private var generation = 0L
    private var focusedRouteId: RouteId? = null
    private var listener: ((RouteGeometrySnapshot) -> Unit)? = null
    private val phases = mutableMapOf<RouteGeometryKey, DirectionPhase>()
    private val inFlight = mutableMapOf<RouteGeometryKey, InFlightDirection>()
    private val completedCache = linkedMapOf<RouteGeometryKey, CachedDirection>()
    // Native sources begin empty, so the initial empty snapshot must retain revision 0. A later
    // nonempty-to-empty transition still differs from this value and clears the grouped source.
    private var lastPublishedPolylines: PersistentList<MapPolyline> = persistentListOf()
    private var polylineSourceRevision = 0L
    private var expiryJob: Job? = null

    /** Updates lifecycle, capability, city, and committed-session selection as one generation. */
    fun update(
        scope: CoroutineScope,
        cityId: CityId?,
        routeGeometryEnabled: Boolean,
        orderedSelectedRoutes: List<TransitRoute>,
        isVisibleAndStarted: Boolean,
        onSnapshot: (RouteGeometrySnapshot) -> Unit,
    ): RouteGeometrySnapshot {
        listener = onSnapshot
        val next = GeometryInput(
            cityId = cityId,
            routeGeometryEnabled = routeGeometryEnabled,
            // The repository list has already supplied canonical route-catalog order. Never sort
            // these opaque IDs or directions in this coordinator or either native adapter.
            routes = orderedSelectedRoutes.toList(),
            isVisibleAndStarted = isVisibleAndStarted,
        )
        // Location updates share the Map collector but do not change geometry ownership. Keep an
        // in-flight request current in that fast path, while still checking an expired cache when
        // this is an explicit active re-sync.
        if (next == input) {
            if (canLoad()) {
                reconcileDesired(scope, activeDirectionSpecs())
                scheduleNextExpiry(scope)
            }
            return snapshot()
        }
        cancelExpirySchedule()
        input = next
        generation = nextGeneration(generation)
        ensureFocusedRoute()

        val desired = activeDirectionSpecs()
        val desiredKeys = desired.mapTo(mutableSetOf(), DirectionSpec::key)
        phases.keys.retainAll(desiredKeys)
        // Cancel deselected/city-invalid work immediately, but retain the bookkeeping entry until
        // its coroutine terminates so a non-cooperative request can never overlap a replacement.
        inFlight.filterKeys { it !in desiredKeys || !canLoad() }.values.forEach { it.job.cancel() }

        if (!canLoad()) {
            // Cooperative cancellation removes this phase only after the suspend returns. Clear
            // it now so foreground entry cannot be stranded behind a completed cancellation.
            phases.entries.removeAll { (_, phase) -> phase is DirectionPhase.Loading }
            return snapshot()
        }

        reconcileDesired(scope, desired)
        scheduleNextExpiry(scope)
        return snapshot()
    }

    private fun reconcileDesired(scope: CoroutineScope, desired: List<DirectionSpec>) {
        desired.forEach { spec ->
            val cached = cached(spec.key)
            when {
                cached != null -> phases[spec.key] = DirectionPhase.Ready(
                    points = cached.points,
                    freshness = cached.freshness,
                    storedAtEpochMillis = cached.storedAtEpochMillis,
                )
                // A non-cooperative cancelled request still owns this key. Do not overlap it;
                // completion notices the newer generation and schedules its replacement.
                inFlight.containsKey(spec.key) -> {
                    if (phases[spec.key] !is DirectionPhase.Ready) {
                        phases[spec.key] = DirectionPhase.Loading
                    }
                }
                // Keep expired Ready geometry visible while its next active sync refreshes it, but
                // never let it bypass the TTL or falsely count as a cache hit.
                phases[spec.key] is DirectionPhase.Ready -> startRequest(scope, spec, generation)
                // A cooperative cancellation can remove its Job before the next foreground sync.
                // Restart only after that happens, so this remains one request per key.
                phases[spec.key] is DirectionPhase.Loading -> startRequest(scope, spec, generation)
                phases[spec.key] == null -> {
                    phases[spec.key] = DirectionPhase.Loading
                    startRequest(scope, spec, generation)
                }
            }
        }
    }

    /** Focus is presentation-only; changing it replaces only the common polyline source. */
    fun focus(routeId: RouteId) {
        if (input.routes.none { it.id == routeId } || focusedRouteId == routeId) return
        focusedRouteId = routeId
        emitSnapshot()
    }

    /** Starts only currently missing/failed directions for this still-selected route. */
    fun retry(scope: CoroutineScope, routeId: RouteId) {
        if (!canLoad() || input.routes.none { it.id == routeId }) return
        val requestGeneration = generation
        activeDirectionSpecs()
            .filter { it.route.id == routeId }
            .filter { phases[it.key] is DirectionPhase.Retryable || phases[it.key] == null }
            .forEach { spec ->
                phases[spec.key] = DirectionPhase.Loading
                startRequest(scope, spec, requestGeneration)
            }
        emitSnapshot()
    }

    fun clear() {
        cancelExpirySchedule()
        inFlight.values.forEach { it.job.cancel() }
        inFlight.clear()
        phases.clear()
        completedCache.clear()
        listener = null
    }

    private fun startRequest(scope: CoroutineScope, spec: DirectionSpec, requestGeneration: Long) {
        if (inFlight.containsKey(spec.key)) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                repository.directionShape(spec.key.cityId, spec.key.routeId, spec.key.directionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TransitLoadResult.Failure(TransitFailure.Transport("Route geometry request failed"))
            }

            // This check follows the suspend point. A stale selection, lifecycle, or capability
            // can therefore never republish a completed request, even if its client ignored cancel.
            if (isCurrent(spec.key, requestGeneration)) {
                phases[spec.key] = result.toDirectionPhase(spec.key)
                scheduleNextExpiry(scope)
                emitSnapshot()
            }
        }
        job.invokeOnCompletion {
            val registered = inFlight[spec.key]
            if (registered?.job !== job) return@invokeOnCompletion
            inFlight.remove(spec.key)
            // A retained direction whose request finished under an older generation needs exactly
            // one fresh request. Scheduling here prevents an overlap with a non-cooperative call.
            if (
                canLoad() && spec.key in activeDirectionKeys() &&
                requestGeneration != generation && cached(spec.key) == null
            ) {
                if (phases[spec.key] !is DirectionPhase.Ready) {
                    phases[spec.key] = DirectionPhase.Loading
                }
                startRequest(scope, spec, generation)
                emitSnapshot()
            }
            if (canLoad() && spec.key in activeDirectionKeys()) {
                // A successful request is still registered while its result publishes. Schedule
                // only after removing that registration (or starting its replacement), otherwise
                // a lone Ready direction would be excluded from the active TTL timer.
                scheduleNextExpiry(scope)
            }
        }
        inFlight[spec.key] = InFlightDirection(requestGeneration, job)
        job.start()
    }

    private fun TransitLoadResult<com.denis.georgiatransit.shared.domain.model.TransitShape>.toDirectionPhase(
        key: RouteGeometryKey,
    ): DirectionPhase =
        when (this) {
            is TransitLoadResult.Data -> when (val decoded = decodeEncodedPolyline(value)) {
                is EncodedPolylineDecodeResult.Success -> {
                    val cached = putCached(key, decoded.points, freshness)
                    DirectionPhase.Ready(
                        points = cached.points,
                        freshness = cached.freshness,
                        storedAtEpochMillis = cached.storedAtEpochMillis,
                    )
                }
                is EncodedPolylineDecodeResult.Invalid -> DirectionPhase.Retryable
            }
            is TransitLoadResult.Empty -> DirectionPhase.Retryable
            // City capability is the only fail-closed terminal gate. A shape endpoint can still
            // fail transiently even after that capability was enabled, so every endpoint failure
            // remains an honest, route-level retry opportunity without exposing provider text.
            is TransitLoadResult.Failure -> DirectionPhase.Retryable
        }

    private fun putCached(
        key: RouteGeometryKey,
        points: PersistentList<GeoPoint>,
        freshness: com.denis.georgiatransit.shared.domain.repository.TransitFreshness,
    ): CachedDirection {
        val entry = CachedDirection(points, freshness, clock.nowEpochMillis())
        completedCache[key] = entry
        while (completedCache.size > MAX_CACHED_DIRECTIONS || completedCache.values.sumOf(CachedDirection::vertexCount) > MAX_CACHED_VERTICES) {
            completedCache.entries.firstOrNull()?.key?.let(completedCache::remove) ?: break
        }
        return entry
    }

    private fun cached(key: RouteGeometryKey): CachedDirection? {
        val entry = completedCache[key] ?: return null
        if (clock.nowEpochMillis() - entry.storedAtEpochMillis in 0L until CACHE_TTL_MILLIS) return entry
        completedCache.remove(key)
        return null
    }

    private fun isCurrent(key: RouteGeometryKey, requestGeneration: Long): Boolean =
        requestGeneration == generation && canLoad() && key in activeDirectionKeys()

    private fun canLoad(): Boolean = input.cityId != null && input.routeGeometryEnabled && input.isVisibleAndStarted

    private fun activeDirectionSpecs(): List<DirectionSpec> {
        val cityId = input.cityId ?: return emptyList()
        val colors = RoutePolylineColorResolver.resolve(input.routes)
        return input.routes.asSequence()
            .take(RouteSelectionPolicy.MaximumSelectedRoutes)
            // A palette overflow is an explicit no-style state: do not start requests for a
            // geometry that can never become a safely distinguishable native line.
            .filter { route -> colors.getValue(route.id).availability == RouteGeometryColorAvailability.Assigned }
            .flatMap { route ->
                route.directions.asSequence().map { direction ->
                    DirectionSpec(RouteGeometryKey(cityId, route.id, direction.id), route)
                }
            }
            .filter { it.key.routeId.value.isNotBlank() && it.key.directionId.value.isNotBlank() }
            .take(MAX_RENDERABLE_DIRECTIONS)
            .toList()
    }

    private fun activeDirectionKeys(): Set<RouteGeometryKey> = activeDirectionSpecs().mapTo(mutableSetOf(), DirectionSpec::key)

    /** Schedules exactly the earliest active Ready expiry; no timer runs while Map is inactive. */
    private fun scheduleNextExpiry(scope: CoroutineScope) {
        cancelExpirySchedule()
        if (!canLoad()) return
        val now = clock.nowEpochMillis()
        val earliestExpiry = activeDirectionSpecs()
            .mapNotNull { spec ->
                (phases[spec.key] as? DirectionPhase.Ready)
                    ?.takeIf { spec.key !in inFlight }
                    ?.storedAtEpochMillis
            }
            .minOrNull()
            ?.plus(CACHE_TTL_MILLIS)
            ?: return
        val requestGeneration = generation
        val waitMillis = (earliestExpiry - now).coerceAtLeast(MIN_EXPIRY_DELAY_MILLIS)
        lateinit var scheduled: Job
        scheduled = scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMillis)
            if (expiryJob !== scheduled) return@launch
            expiryJob = null
            if (requestGeneration != generation || !canLoad()) return@launch
            // Reconciliation turns only expired Ready entries into requests. Their previous
            // polylines remain visible until a replacement completes or returns an error.
            reconcileDesired(scope, activeDirectionSpecs())
            emitSnapshot()
            scheduleNextExpiry(scope)
        }
        expiryJob = scheduled
        scheduled.start()
    }

    private fun cancelExpirySchedule() {
        expiryJob?.cancel()
        expiryJob = null
    }

    private fun ensureFocusedRoute() {
        focusedRouteId = focusedRouteId?.takeIf { focused -> input.routes.any { it.id == focused } }
            ?: input.routes.firstOrNull()?.id
    }

    private fun emitSnapshot() {
        listener?.invoke(snapshot())
    }

    private fun snapshot(): RouteGeometrySnapshot {
        val colors = RoutePolylineColorResolver.resolve(input.routes)
        val activeKeys = activeDirectionKeys()
        val lines = input.routes.asSequence().takeIf { input.routeGeometryEnabled }?.flatMap { route ->
            route.directions.asSequence().mapNotNull { direction ->
                val key = input.cityId?.let { RouteGeometryKey(it, route.id, direction.id) } ?: return@mapNotNull null
                val phase = phases[key] as? DirectionPhase.Ready ?: return@mapNotNull null
                if (key !in activeKeys) return@mapNotNull null
                val routeColor = colors.getValue(route.id).argb ?: return@mapNotNull null
                val focused = route.id == focusedRouteId
                MapPolyline(
                    routeId = route.id,
                    directionId = direction.id,
                    points = phase.points,
                    routeColorArgb = routeColor,
                    freshness = phase.freshness,
                    isEmphasized = focused,
                    strokeWidth = if (focused) EMPHASIZED_STROKE_WIDTH else DEFAULT_STROKE_WIDTH,
                    opacity = if (focused) EMPHASIZED_OPACITY else BACKGROUND_STROKE_OPACITY,
                )
            }
        }?.toList()?.toPersistentList() ?: persistentListOf()
        val legends = input.routes.map { route ->
            val routeColor = colors.getValue(route.id)
            val routeDirections = route.directions
            val ready = routeDirections.count { direction ->
                val key = input.cityId?.let { RouteGeometryKey(it, route.id, direction.id) }
                phases[key] is DirectionPhase.Ready
            }
            val phasesForRoute = routeDirections.map { direction ->
                input.cityId?.let { phases[RouteGeometryKey(it, route.id, direction.id)] } ?: DirectionPhase.Unavailable
            }
            val hasRetryable = phasesForRoute.any { it is DirectionPhase.Retryable }
            val hasLoading = phasesForRoute.any { it is DirectionPhase.Loading }
            val cityId = input.cityId
            val hasUnavailable = phasesForRoute.any { it is DirectionPhase.Unavailable } ||
                routeDirections.any { direction ->
                    cityId == null || direction.id.value.isBlank() ||
                        RouteGeometryKey(cityId, route.id, direction.id) !in activeKeys
                }
            RouteGeometryLegendUi(
                routeId = route.id,
                routeLabel = route.shortName.trim().ifBlank { route.name.trim().ifBlank { "—" } },
                colorArgb = routeColor.argb ?: UNAVAILABLE_ROUTE_COLOR,
                colorAvailability = routeColor.availability,
                state = when {
                    routeColor.availability == RouteGeometryColorAvailability.PaletteOverflow -> RouteGeometryLegendState.PaletteOverflow
                    !input.routeGeometryEnabled || routeDirections.isEmpty() -> RouteGeometryLegendState.Unavailable
                    ready == routeDirections.size -> RouteGeometryLegendState.Ready
                    ready > 0 -> RouteGeometryLegendState.Partial
                    hasRetryable -> RouteGeometryLegendState.Retryable
                    hasLoading && input.isVisibleAndStarted -> RouteGeometryLegendState.Loading
                    hasUnavailable -> RouteGeometryLegendState.Unavailable
                    else -> if (input.isVisibleAndStarted) {
                        RouteGeometryLegendState.Loading
                    } else {
                        RouteGeometryLegendState.Unavailable
                    }
                },
                successfulDirections = ready,
                totalDirections = routeDirections.size,
                isFocused = route.id == focusedRouteId,
                canRetry = routeColor.availability == RouteGeometryColorAvailability.Assigned && hasRetryable,
            )
        }.toPersistentList()
        if (lastPublishedPolylines != lines) {
            lastPublishedPolylines = lines
            polylineSourceRevision = nextGeneration(polylineSourceRevision)
        }
        return RouteGeometrySnapshot(
            polylines = lines,
            legends = legends,
            polylineSourceRevision = polylineSourceRevision,
        )
    }

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        /** 10 selected routes normally make 20 directions; this leaves headroom below adapter cap. */
        const val MAX_RENDERABLE_DIRECTIONS = 256
        const val MAX_CACHED_DIRECTIONS = 64
        const val MAX_CACHED_VERTICES = 512_000
        const val DEFAULT_STROKE_WIDTH = 4.0
        const val EMPHASIZED_STROKE_WIDTH = 6.0
        const val BACKGROUND_STROKE_OPACITY = 1.0
        const val EMPHASIZED_OPACITY = 1.0
        const val MIN_EXPIRY_DELAY_MILLIS = 1_000L
    }
}

/** A bounded, pure Google encoded-polyline decoder. It never forwards untrusted geometry. */
internal sealed interface EncodedPolylineDecodeResult {
    data class Success(val points: PersistentList<GeoPoint>) : EncodedPolylineDecodeResult
    data object Invalid : EncodedPolylineDecodeResult
}

internal fun decodeEncodedPolyline(shape: TransitShape): EncodedPolylineDecodeResult =
    decodeEncodedPolyline(shape.encodedPolyline, shape.precision)

internal fun decodeEncodedPolyline(encoded: String, precision: Int): EncodedPolylineDecodeResult {
    if (precision !in 0..8 || encoded.isEmpty() || encoded.length > MAX_ENCODED_POLYLINE_CHARS) {
        return EncodedPolylineDecodeResult.Invalid
    }
    var index = 0
    var latitude = 0L
    var longitude = 0L
    var hasDistinctPoint = false
    var firstPoint: GeoPoint? = null
    val scale = 10.0.pow(precision)
    val points = ArrayList<GeoPoint>()
    while (index < encoded.length) {
        val latitudeComponent = decodePolylineComponent(encoded, index) ?: return EncodedPolylineDecodeResult.Invalid
        index = latitudeComponent.nextIndex
        val longitudeComponent = decodePolylineComponent(encoded, index) ?: return EncodedPolylineDecodeResult.Invalid
        index = longitudeComponent.nextIndex
        latitude = latitude.safeAdd(latitudeComponent.delta) ?: return EncodedPolylineDecodeResult.Invalid
        longitude = longitude.safeAdd(longitudeComponent.delta) ?: return EncodedPolylineDecodeResult.Invalid
        val point = GeoPoint(latitude.toDouble() / scale, longitude.toDouble() / scale)
        if (!point.isMapCoordinate()) return EncodedPolylineDecodeResult.Invalid
        if (points.size == MAX_POINTS_PER_POLYLINE) return EncodedPolylineDecodeResult.Invalid
        if (firstPoint == null) firstPoint = point else if (firstPoint != point) hasDistinctPoint = true
        points += point
    }
    return if (points.size >= 2 && hasDistinctPoint) {
        EncodedPolylineDecodeResult.Success(points.toPersistentList())
    } else {
        EncodedPolylineDecodeResult.Invalid
    }
}

private data class DecodedPolylineComponent(val delta: Long, val nextIndex: Int)

private fun decodePolylineComponent(value: String, start: Int): DecodedPolylineComponent? {
    var index = start
    var result = 0uL
    var shift = 0
    while (true) {
        if (index >= value.length || shift > 60) return null
        val code = value[index++].code - POLYLINE_ASCII_OFFSET
        if (code !in 0..POLYLINE_VALUE_MASK || (shift == 60 && (code and POLYLINE_CHUNK_MASK) > 0x0F)) return null
        result = result or ((code and POLYLINE_CHUNK_MASK).toULong() shl shift)
        if (code and POLYLINE_CONTINUATION_MASK == 0) break
        shift += POLYLINE_CHUNK_BITS
    }
    // Decode ZigZag in the unsigned domain. In particular, a valid bit 63 maps to Long.MIN_VALUE
    // without signed shift/negation overflow; coordinate accumulation then safely rejects it.
    val delta = (result shr 1).toLong() xor -((result and 1uL).toLong())
    return DecodedPolylineComponent(delta, index)
}

private fun Long.safeAdd(other: Long): Long? = when {
    other > 0L && this > Long.MAX_VALUE - other -> null
    other < 0L && this < Long.MIN_VALUE - other -> null
    else -> this + other
}

private sealed interface ResolvedRouteColor {
    val argb: Long?
    val availability: RouteGeometryColorAvailability

    data class Assigned(override val argb: Long) : ResolvedRouteColor {
        override val availability = RouteGeometryColorAvailability.Assigned
    }

    data object PaletteOverflow : ResolvedRouteColor {
        override val argb: Long? = null
        override val availability = RouteGeometryColorAvailability.PaletteOverflow
    }
}

/** Colors are resolved once in the same canonical order that forms the common polyline snapshot. */
private object RoutePolylineColorResolver {
    fun resolve(routes: List<TransitRoute>): Map<RouteId, ResolvedRouteColor> {
        val assigned = linkedSetOf<Long>()
        return buildMap {
            routes.forEachIndexed { index, route ->
                if (index >= RouteSelectionPolicy.MaximumSelectedRoutes) {
                    put(route.id, ResolvedRouteColor.PaletteOverflow)
                    return@forEachIndexed
                }
                val provider = route.colorArgb.takeIf(::isAccessibleProviderColor)
                    ?.takeIf { candidate -> assigned.all { isDistinct(candidate, it) } }
                val color = provider ?: paletteColor(route.id, assigned)
                if (color == null) {
                    put(route.id, ResolvedRouteColor.PaletteOverflow)
                } else {
                    assigned += color
                    put(route.id, ResolvedRouteColor.Assigned(color))
                }
            }
        }
    }

    private fun paletteColor(routeId: RouteId, assigned: Set<Long>): Long? {
        val start = (routeId.value.stableColorHash().toUInt().toLong() % ACCESSIBLE_ROUTE_PALETTE.size).toInt()
        return (ACCESSIBLE_ROUTE_PALETTE.indices)
            .asSequence()
            .map { ACCESSIBLE_ROUTE_PALETTE[(start + it) % ACCESSIBLE_ROUTE_PALETTE.size] }
            // Palette entries are pairwise validated below. Keep probing in canonical selection
            // order and never substitute an already-used or near-duplicate color on exhaustion.
            .firstOrNull { candidate -> candidate !in assigned && assigned.all { isDistinct(candidate, it) } }
    }

    private fun isAccessibleProviderColor(color: Long): Boolean =
        (color ushr 24) == 0xFFL && contrastRatio(color.luminance(), MAP_BACKGROUND_LUMINANCE) >= MIN_MAP_CONTRAST

    private fun isDistinct(first: Long, second: Long): Boolean {
        val red = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val green = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return red * red + green * green + blue * blue >= MIN_RGB_DISTANCE_SQUARED
    }

    private fun String.stableColorHash(): Int = fold(17) { hash, character -> 31 * hash + character.code }

    private fun Long.luminance(): Double {
        fun channel(shift: Int): Double {
            val value = ((this shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrastRatio(first: Double, second: Double): Double =
        (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)

    private val ACCESSIBLE_ROUTE_PALETTE = listOf(
        0xFF5B21B6L, 0xFF1D4ED8L, 0xFF0F766EL, 0xFFBE123CL, 0xFF334155L,
        0xFF7F1D1DL, 0xFF14532DL, 0xFF1E3A8AL, 0xFF854D0EL, 0xFF3F6212L,
        0xFFC2410CL, 0xFFA21CAFL, 0xFF0369A1L,
    ).also(::validatePalette)

    private fun validatePalette(palette: List<Long>): List<Long> {
        check(palette.size >= RouteSelectionPolicy.MaximumSelectedRoutes)
        check(palette.all(::isAccessibleProviderColor))
        check(palette.indices.all { first ->
            palette.drop(first + 1).all { second -> isDistinct(palette[first], second) }
        })
        return palette
    }
}

/** Enough for a 20k-point line at high precision, while bounding malformed response work. */
private const val MAX_ENCODED_POLYLINE_CHARS = 512_000
private const val MAX_POINTS_PER_POLYLINE = 20_000
private const val POLYLINE_ASCII_OFFSET = 63
private const val POLYLINE_VALUE_MASK = 0x3F
private const val POLYLINE_CHUNK_MASK = 0x1F
private const val POLYLINE_CONTINUATION_MASK = 0x20
private const val POLYLINE_CHUNK_BITS = 5
private const val MAP_BACKGROUND_LUMINANCE = 0.8589768 // #E7F1EB, the fixed local preview land fill.
private const val MIN_MAP_CONTRAST = 3.0
private const val MIN_RGB_DISTANCE_SQUARED = 2_500L
private const val UNAVAILABLE_ROUTE_COLOR = 0xFF3F3F46L
