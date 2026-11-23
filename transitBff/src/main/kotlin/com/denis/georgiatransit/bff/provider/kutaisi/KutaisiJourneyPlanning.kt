package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.planning.GeoBounds
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.planning.TransitNetworkGraph
import com.denis.georgiatransit.bff.planning.TransitPattern
import com.denis.georgiatransit.bff.planning.TransitRouteShape
import com.denis.georgiatransit.bff.planning.TransitStop
import com.denis.georgiatransit.bff.planning.UntimedJourneyPlanner
import com.denis.georgiatransit.bff.provider.ContinuousSnapshotWorker
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal val KutaisiServiceBounds = GeoBounds(42.17, 42.35, 42.57, 42.77)

/** Builds the graph independently of requests; each plan uses one complete immutable publication. */
internal class KutaisiJourneyPlanning(
    private val catalog: AzryCatalog,
    private val feed: AzryFeed,
    private val clock: Clock,
) : AutoCloseable {
    @Volatile private var worker: ContinuousSnapshotWorker<Network>? = null

    fun start(enabled: () -> Boolean, onFailure: () -> Unit) {
        check(worker == null)
        var nextRefresh = Instant.MIN
        worker = ContinuousSnapshotWorker(
            pollInterval = Duration.ofMinutes(1),
            isEnabled = { enabled() && clock.instant() >= nextRefresh },
            onSuccess = { _, _ -> nextRefresh = clock.instant().plus(RefreshInterval) },
            onFailure = { _, _ ->
                nextRefresh = clock.instant().plus(Duration.ofMinutes(5))
                onFailure()
            },
            loader = {
                val graph = buildKutaisiNetwork(catalog, feed)
                Network(UntimedJourneyPlanner(graph, KnownCityIds.Kutaisi, AzryProviderId), clock.instant())
            },
        )
    }

    val ready: Boolean get() = current(clock.instant()) != null

    fun plan(query: JourneyPlanQuery, budget: PlanningBudget): RealtimeJourneys {
        val now = clock.instant()
        val network = current(now) ?: throw ProviderUnavailable("Kutaisi journey planning data is unavailable")
        val stale = Duration.between(network.observedAt, now) > RefreshInterval
        return network.planner.plan(query, network.observedAt, stale, budget)
    }

    private fun current(now: Instant): Network? = worker?.currentOrNull()?.takeIf {
        val age = Duration.between(it.observedAt, now)
        !age.isNegative && age < MaximumGraphAge
    }

    override fun close() {
        worker?.close()
    }

    private data class Network(val planner: UntimedJourneyPlanner, val observedAt: Instant)

    private companion object {
        val RefreshInterval: Duration = Duration.ofHours(2)
        val MaximumGraphAge: Duration = Duration.ofHours(24)
    }
}

internal suspend fun buildKutaisiNetwork(catalog: AzryCatalog, feed: AzryFeed): TransitNetworkGraph {
    val locale = "en"
    val stops = mutableListOf<TransitStop>()
    val stopIndexes = mutableMapOf<String, Int>()
    val patterns = mutableListOf<TransitPattern>()
    // Provider calls share the existing AZRY request budget. Batch shapes once per route.
    for (route in catalog.routes(locale).sortedBy { it.id }) {
        val raw = catalog.rawRoute(route.id, locale)
        val shapes = feed.polylines(raw.id, raw.patterns.map(AzryPattern::patternSuffix))
        for (pattern in raw.patterns.sortedBy(AzryPattern::patternSuffix)) {
            val directionId = azryDirectionId(raw.id, pattern.patternSuffix)
            val orderedStops = feed.directionStops(raw.id, pattern.patternSuffix, locale)
                .map { it.normalized(listOf(route.id)) }
            if (orderedStops.size !in 2..500 || orderedStops.any {
                it.position.latitude !in -90.0..90.0 || it.position.longitude !in -180.0..180.0
            }) invalidAzry()
            val indices = orderedStops.map { stop ->
                stopIndexes.getOrPut(stop.id) {
                    stops += TransitStop(stop.id, stop.position, stop.name)
                    stops.lastIndex
                }
            }.toIntArray()
            val positions = indices.map { stops[it].position }
            val matching = shapes[pattern.patternSuffix]?.encodedValue?.let(::azryShapePoints)
                ?.let(::TransitRouteShape)?.match(positions)
            val segments = positions.zipWithNext().mapIndexed { index, (from, to) ->
                matching?.segment(index, index + 1)
                    ?: TransitRouteShape.Segment(listOf(from, to), approximate = true, meters = greatCircleMeters(from, to))
            }
            patterns += TransitPattern(
                route.id, directionId, indices,
                segments.map(TransitRouteShape.Segment::points),
                BooleanArray(segments.size) { segments[it].approximate },
                DoubleArray(segments.size) { segments[it].meters },
            )
        }
    }
    if (patterns.isEmpty()) invalidAzry()
    return TransitNetworkGraph(stops, patterns)
}
