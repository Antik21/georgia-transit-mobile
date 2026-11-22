package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import java.time.Instant
import kotlin.math.max

/** Searches directional routes and walkable transfers without assuming that a bus is due. */
internal class UntimedJourneyPlanner(
    private val graph: TransitNetworkGraph,
    cityId: String,
    providerId: String,
) {
    private val mapper = JourneyResponseMapper(graph, cityId, providerId)

    fun plan(query: JourneyPlanQuery, observedAt: Instant, stale: Boolean, budget: PlanningBudget): RealtimeJourneys {
        require(query.maxTransfers in 0..PlannerParameters.MaximumTransfers)
        val directMeters = greatCircleMeters(query.from, query.to)
        val options = if (directMeters < PlannerParameters.SamePlaceMeters) {
            listOf(walkOnly(query))
        } else {
            val walk = (directMeters * PlannerParameters.WalkDetourFactor).takeIf {
                it <= PlannerParameters.WalkOnlyMaximumMeters
            }
            val transit = select(search(query, budget), directMeters, walk).map { itinerary(query, it) }
            if (walk != null) transit + walkOnly(query) else transit
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
        return results
    }

    private fun select(paths: List<Path>, directMeters: Double, walkMeters: Double?): List<Path> {
        val walkScore = walkMeters?.times(WalkingWeight) ?: Double.POSITIVE_INFINITY
        val candidates = paths.filter { path ->
            path.rideMeters + path.walkMeters <= detourLimit(directMeters) && path.score < walkScore
        }
        val bestScore = candidates.minOfOrNull(Path::score) ?: return emptyList()
        val limit = bestScore + max(MinimumAlternativeSlack, bestScore * AlternativeSlackFraction)
        val bestByRideCount = candidates.groupBy { it.rides.size }
            .mapValues { (_, sameRideCount) -> sameRideCount.minOf(Path::score) }
        return candidates.asSequence()
            .filter { path ->
                path.score <= limit && bestByRideCount.none { (rides, score) -> rides < path.rides.size && score <= path.score }
            }
            .sortedWith(pathOrder)
            .distinctBy(Path::routeIds)
            .take(MaximumTransitOptions)
            .toList()
    }

    private fun scan(boardable: Map<Int, List<Boarding>>, budget: PlanningBudget): Map<Int, List<Path>> {
        val arrivals = mutableMapOf<Int, MutableList<Path>>()
        for ((stop, boardings) in boardable) {
            budget.checkpoint()
            for ((index, board) in graph.stopPatterns[stop]) {
                val pattern = graph.patterns[index]
                val eligible = boardings.mapNotNull { boarding ->
                    if (pattern.routeId in boarding.path.routeIds) null
                    else boarding to (boarding.path.routeIds + pattern.routeId)
                }
                if (eligible.isEmpty()) continue
                val boardingPoint = graph.stops[stop].position
                for (alight in board + 1 until pattern.size) {
                    val target = pattern.stops[alight]
                    if (target == stop) continue
                    val rideMeters = pattern.rideMeters(board, alight)
                    val progress = greatCircleMeters(boardingPoint, graph.stops[target].position)
                    if (rideMeters > detourLimit(progress)) continue
                    for ((boarding, routes) in eligible) {
                        val (path, walk) = boarding
                        val ride = Ride(index, board, alight, walk)
                        val candidate = Path(
                            path.rides + ride,
                            routes,
                            path.walkMeters + walk,
                            path.rideMeters + rideMeters,
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
            candidates.sortedBy { it.path.score + it.walkMeters * WalkingWeight }
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
    ) {
        // Distance-based ranking for a network without reliable ride or wait times.
        val score: Double get() = rideMeters + walkMeters * WalkingWeight +
            (rides.size - 1).coerceAtLeast(0) * TransferPenaltyMeters
    }
    private data class Boarding(val path: Path, val walkMeters: Double)

    private companion object {
        const val PathsPerStop = 6
        const val MaximumTransitOptions = 3
        const val WalkingWeight = 3.0
        const val TransferPenaltyMeters = 500.0
        const val MaximumDetourRatio = 2.5
        const val DetourAllowanceMeters = 300.0
        const val MinimumAlternativeSlack = 250.0
        const val AlternativeSlackFraction = 0.20
        val pathOrder = compareBy(Path::score, Path::walkMeters)

        fun detourLimit(directMeters: Double): Double = directMeters * MaximumDetourRatio + DetourAllowanceMeters
    }
}
