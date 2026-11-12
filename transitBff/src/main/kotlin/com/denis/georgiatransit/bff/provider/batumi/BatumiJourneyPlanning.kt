package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.planning.GeoBounds
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.planning.TransitJourneyPlanner
import com.denis.georgiatransit.bff.provider.BatumiCatalog
import com.denis.georgiatransit.bff.provider.BatumiLiveVehicle
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** Matches the catalog and live parsers' accepted Batumi coordinate range. */
internal val BatumiServiceBounds = GeoBounds(
    minLatitude = 41.45,
    maxLatitude = 41.85,
    minLongitude = 41.40,
    maxLongitude = 41.95,
)

/**
 * Owns the immutable planning graph published with each validated catalog. Planning reads only
 * that graph and the latest city-wide live snapshot; it never triggers an upstream request.
 */
internal class BatumiJourneyPlanning(
    private val cityId: String,
    private val providerId: String,
    private val catalogMaximumAge: Duration,
    private val liveMaximumAge: Duration,
    private val liveFreshAge: Duration,
) {
    @Volatile private var network: BatumiPlanningNetwork? = null

    fun publish(catalog: BatumiCatalog, publishedAt: Instant) {
        try {
            network = BatumiNetworkGraphBuilder.build(catalog, publishedAt)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            // A graph defect must not block catalog publication; planning keeps the previous graph.
        }
    }

    fun isReady(now: Instant, liveObservedAt: Instant?): Boolean {
        val published = network ?: return false
        if (Duration.between(published.publishedAt, now) >= catalogMaximumAge) return false
        val liveAge = liveObservedAt?.let { Duration.between(it, now) } ?: return false
        return !liveAge.isNegative && liveAge <= liveMaximumAge
    }

    fun plan(
        query: JourneyPlanQuery,
        budget: PlanningBudget,
        vehiclesByRoute: Map<String, List<BatumiLiveVehicle>>,
        liveObservedAt: Instant,
        now: Instant,
    ): RealtimeJourneys {
        val published = network
        if (published == null || !isReady(now, liveObservedAt)) {
            throw ProviderUnavailable("Batumi journey planning data is unavailable")
        }
        val timetable = BatumiLiveTimetableBuilder.build(
            network = published,
            vehiclesByRoute = vehiclesByRoute,
            observedAt = liveObservedAt,
            stale = Duration.between(liveObservedAt, now) > liveFreshAge,
            budget = budget,
        )
        return TransitJourneyPlanner(published.graph, timetable, cityId, providerId).plan(query, budget)
    }
}
