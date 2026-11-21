package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import java.time.Instant

/** Searches directional routes and walkable transfers without assuming that a bus is due. */
internal class UntimedJourneyPlanner(
    private val graph: TransitNetworkGraph,
    cityId: String,
    providerId: String,
) {
    private val mapper = JourneyResponseMapper(graph, cityId, providerId)

    fun plan(query: JourneyPlanQuery, observedAt: Instant, stale: Boolean, budget: PlanningBudget): RealtimeJourneys {
        require(query.maxTransfers in 0..PlannerParameters.MaximumTransfers)
        val options = if (greatCircleMeters(query.from, query.to) < PlannerParameters.SamePlaceMeters) {
            listOf(walkOnly(query))
        } else {
            val transit = search(query, budget).map { itinerary(query, it) }
            if (transit.size < PlannerParameters.MaximumOptions &&
                walkingMeters(query.from, query.to) <= PlannerParameters.WalkOnlyMaximumMeters
            ) transit + walkOnly(query) else transit
        }
        return RealtimeJourneys(options, ArrivalSource.CLIENT_ESTIMATE, realtime = false, observedAt, stale)
    }

    private fun search(query: JourneyPlanQuery, budget: PlanningBudget): List<Path> {
        val egress = graph.stopsWithin(query.to, PlannerParameters.AccessRadiusMeters)
            .associate { it.stop to it.straightMeters * PlannerParameters.WalkDetourFactor }
        if (egress.isEmpty()) return emptyList()
        var boardable = graph.stopsWithin(query.from, PlannerParameters.AccessRadiusMeters).associate { access ->
            access.stop to listOf(Boarding(Path(), access.straightMeters * PlannerParameters.WalkDetourFactor))
        }
        val results = mutableListOf<Path>()
        for (round in 0..query.maxTransfers) {
            if (boardable.isEmpty()) break
            val arrivals = scan(boardable, budget)
            for ((stop, paths) in arrivals) {
                val walk = egress[stop] ?: continue
                for (path in paths) results += path.copy(walkMeters = path.walkMeters + walk)
            }
            if (round < query.maxTransfers) boardable = transfers(arrivals, budget)
        }
        return results.sortedWith(compareBy<Path> { it.rides.size }.then(pathOrder))
            .distinctBy(Path::routeIds)
            .take(PlannerParameters.MaximumOptions)
    }

    private fun scan(boardable: Map<Int, List<Boarding>>, budget: PlanningBudget): Map<Int, List<Path>> {
        val arrivals = mutableMapOf<Int, MutableList<Path>>()
        for ((stop, boardings) in boardable) {
            budget.checkpoint()
            for ((index, board) in graph.stopPatterns[stop]) {
                val pattern = graph.patterns[index]
                for ((path, walk) in boardings) {
                    if (pattern.routeId in path.routeIds) continue
                    val routes = path.routeIds + pattern.routeId
                    for (alight in board + 1 until pattern.size) {
                        val target = pattern.stops[alight]
                        if (target == stop) continue
                        val ride = Ride(index, board, alight, walk)
                        val candidate = Path(
                            path.rides + ride,
                            routes,
                            path.walkMeters + walk,
                            path.rideMeters + pattern.rideMeters(board, alight),
                        )
                        keepBest(arrivals.getOrPut(target, ::mutableListOf), candidate)
                    }
                }
            }
        }
        return arrivals
    }

    private fun transfers(arrivals: Map<Int, List<Path>>, budget: PlanningBudget): Map<Int, List<Boarding>> {
        val result = mutableMapOf<Int, MutableList<Boarding>>()
        for ((stop, paths) in arrivals) {
            budget.checkpoint()
            for (path in paths) {
                result.getOrPut(stop, ::mutableListOf) += Boarding(path, 0.0)
                val distances = graph.walkNeighbourMeters[stop]
                graph.walkNeighbours[stop].forEachIndexed { index, neighbour ->
                    result.getOrPut(neighbour, ::mutableListOf) += Boarding(path, distances[index])
                }
            }
        }
        return result.mapValues { (_, candidates) ->
            candidates.sortedWith(compareBy<Boarding> { it.path.walkMeters + it.walkMeters }.thenBy { it.path.rideMeters })
                .distinctBy { it.path.routeIds }
                .take(PathsPerStop)
        }
    }

    private fun keepBest(paths: MutableList<Path>, candidate: Path) {
        val existing = paths.indexOfFirst { it.routeIds == candidate.routeIds }
        if (existing >= 0) {
            if (pathOrder.compare(paths[existing], candidate) <= 0) return
            paths.removeAt(existing)
        }
        if (paths.size == PathsPerStop && pathOrder.compare(paths.last(), candidate) <= 0) return
        paths += candidate
        paths.sortWith(pathOrder)
        if (paths.size > PathsPerStop) paths.removeAt(paths.lastIndex)
    }

    private fun itinerary(query: JourneyPlanQuery, path: Path): Journey {
        val segments = mutableListOf<JourneySegment>()
        var previousStop: Int? = null
        for ((index, board, alight, walk) in path.rides) {
            val pattern = graph.patterns[index]
            val boarding = pattern.stops[board]
            if (previousStop != boarding && (previousStop != null || walk >= MinimumPassengerWalkMeters)) {
                val from = previousStop?.let { graph.stops[it].position } ?: query.from
                segments += mapper.walkSegment(previousStop, boarding, from, graph.stops[boarding].position, walk)
            }
            segments += mapper.rideSegment(index, board, alight)
            previousStop = pattern.stops[alight]
        }
        val lastPosition = graph.stops[checkNotNull(previousStop)].position
        val egress = walkingMeters(lastPosition, query.to)
        if (egress >= MinimumPassengerWalkMeters) {
            segments += mapper.walkSegment(previousStop, null, lastPosition, query.to, egress)
        }
        return mapper.untimedJourney(segments)
    }

    private fun walkOnly(query: JourneyPlanQuery): Journey =
        mapper.journey(walkOnlyItinerary(query.from, query.to, query.departureAt.epochSecond), 0, emptyList())

    private data class Ride(val pattern: Int, val board: Int, val alight: Int, val precedingWalkMeters: Double)
    private data class Path(
        val rides: List<Ride> = emptyList(),
        val routeIds: List<String> = emptyList(),
        val walkMeters: Double = 0.0,
        val rideMeters: Double = 0.0,
    )
    private data class Boarding(val path: Path, val walkMeters: Double)

    private companion object {
        const val PathsPerStop = 6
        val pathOrder = compareBy(Path::walkMeters, Path::rideMeters)
    }
}
