package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.planning.GeoBounds
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.LiveTrip
import com.denis.georgiatransit.bff.planning.NetworkTimetable
import com.denis.georgiatransit.bff.planning.PatternService
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.planning.TransitJourneyPlanner
import com.denis.georgiatransit.bff.planning.TransitNetworkGraph
import com.denis.georgiatransit.bff.planning.TransitPattern
import com.denis.georgiatransit.bff.planning.TransitStop
import java.time.Instant

internal val DemoServiceBounds = GeoBounds(
    minLatitude = 41.66,
    maxLatitude = 41.77,
    minLongitude = 44.76,
    maxLongitude = 44.90,
)

/**
 * Development-only plan on the demo blue line through the real planner. Departures are relative to
 * the requested departure so a given request always produces the same itinerary.
 */
internal class DemoFixtureJourneyPlanning(
    stops: List<Stop>,
    private val route: Route,
    private val vehicleIds: List<String>,
) {
    private val graph = TransitNetworkGraph(
        stops = stops.map { TransitStop(it.id, it.position) },
        patterns = listOf(
            pattern(intArrayOf(0, 1, 2), stops, route.directions[0].id),
            pattern(intArrayOf(2, 1, 0), stops, route.directions[1].id),
        ),
    )

    fun plan(query: JourneyPlanQuery, budget: PlanningBudget, now: Instant): RealtimeJourneys {
        val departure = query.departureAt.epochSecond
        val outbound = vehicleIds.mapIndexed { index, vehicleId ->
            val start = departure + FirstDepartureSeconds + index * HeadwaySeconds
            LiveTrip(vehicleId, longArrayOf(start, start + 120, start + 300))
        }
        val timetable = NetworkTimetable(
            observedAt = now,
            liveHorizonEndEpochSecond = departure + HorizonSeconds,
            stale = false,
            services = listOf(
                PatternService(outbound, HeadwaySeconds, doubleArrayOf(0.0, 120.0, 300.0)),
                PatternService(emptyList(), HeadwaySeconds, doubleArrayOf(0.0, 180.0, 300.0)),
            ),
        )
        return TransitJourneyPlanner(graph, timetable, KnownCityIds.Demo, DemoProvider).plan(query, budget)
    }

    private fun pattern(order: IntArray, stops: List<Stop>, directionId: String): TransitPattern {
        val pairs = order.toList().zipWithNext()
        return TransitPattern(
            routeId = route.id,
            directionId = directionId,
            stops = order,
            segmentGeometry = pairs.map { (from, to) -> listOf(stops[from].position, stops[to].position) },
            segmentApproximate = BooleanArray(pairs.size) { true },
            segmentMeters = DoubleArray(pairs.size) { greatCircleMeters(stops[pairs[it].first].position, stops[pairs[it].second].position) },
        )
    }

    private companion object {
        const val DemoProvider = "fixture"
        const val FirstDepartureSeconds = 300L
        const val HeadwaySeconds = 600L
        const val HorizonSeconds = 2 * 60 * 60L
    }
}
