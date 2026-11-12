package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyWaitBasis

internal class SearchResult(
    val lastRide: RideLabel,
    val egressMeters: Double,
    val arrival: Long,
    val walkMeters: Double,
    val transfers: Int,
)

/**
 * Time-dependent multi-criteria RAPTOR: round k holds journeys with k rides, and each stop keeps a
 * Pareto bag over (arrival, walking). Every stop in the access radius is a boarding candidate.
 */
internal class JourneySearch(
    private val graph: TransitNetworkGraph,
    private val timetable: NetworkTimetable,
    private val budget: PlanningBudget,
) {
    private val best = arrayOfNulls<LabelBag>(graph.stops.size)

    fun search(
        origin: GeoPoint,
        destination: GeoPoint,
        departure: Long,
        maxTransfers: Int,
        bufferSeconds: Long,
    ): List<SearchResult> {
        val egress = graph.stopsWithin(destination, PlannerParameters.AccessRadiusMeters)
            .associate { it.stop to it.straightMeters * PlannerParameters.WalkDetourFactor }
        if (egress.isEmpty()) return emptyList()
        var boardable = HashMap<Int, MutableList<SearchLabel>>()
        graph.stopsWithin(origin, PlannerParameters.AccessRadiusMeters).forEach { access ->
            val meters = access.straightMeters * PlannerParameters.WalkDetourFactor
            val label = AccessLabel(access.stop, departure + walkingSeconds(meters), meters)
            if (bag(access.stop).add(label)) boardable.getOrPut(access.stop, ::mutableListOf) += label
        }
        val results = mutableListOf<SearchResult>()
        for (round in 1..maxTransfers + 1) {
            if (boardable.isEmpty()) break
            val rides = scanRound(boardable, bufferSeconds)
            rides.forEach { (stop, labels) ->
                val egressMeters = egress[stop] ?: return@forEach
                labels.forEach { ride ->
                    results += SearchResult(
                        lastRide = ride,
                        egressMeters = egressMeters,
                        arrival = ride.arrival + walkingSeconds(egressMeters),
                        walkMeters = ride.walkMeters + egressMeters,
                        transfers = round - 1,
                    )
                }
            }
            boardable = if (round <= maxTransfers) relaxTransfers(rides) else HashMap()
        }
        return paretoFront(results)
    }

    private fun scanRound(
        boardable: Map<Int, List<SearchLabel>>,
        bufferSeconds: Long,
    ): Map<Int, MutableList<RideLabel>> {
        val scanStart = HashMap<Int, Int>()
        boardable.keys.forEach { stop ->
            graph.stopPatterns[stop].forEach { (pattern, position) ->
                scanStart.merge(pattern, position, ::minOf)
            }
        }
        val rides = HashMap<Int, MutableList<RideLabel>>()
        scanStart.forEach { (pattern, start) ->
            budget.checkpoint()
            scanPattern(pattern, start, boardable, bufferSeconds, rides)
        }
        return rides
    }

    private fun scanPattern(
        patternIndex: Int,
        start: Int,
        boardable: Map<Int, List<SearchLabel>>,
        bufferSeconds: Long,
        rides: MutableMap<Int, MutableList<RideLabel>>,
    ) {
        val pattern = graph.patterns[patternIndex]
        val service = timetable.services[patternIndex] ?: return
        val entries = ArrayList<RouteEntry>()
        for (position in start until pattern.size) {
            val stop = pattern.stops[position]
            entries.removeAll { it.lastPosition < position }
            entries.forEach { entry ->
                if (position <= entry.boardPosition) return@forEach
                val label = RideLabel(
                    stop = stop,
                    arrival = entry.timeAt(position),
                    boarding = entry.boarding,
                    pattern = patternIndex,
                    boardPosition = entry.boardPosition,
                    alightPosition = position,
                    departure = entry.departure,
                    waitBasis = entry.waitBasis,
                    vehicleId = entry.vehicleId,
                )
                if (bag(stop).add(label)) rides.getOrPut(stop, ::mutableListOf) += label
            }
            if (position == pattern.size - 1) break
            boardable[stop]?.forEach { label ->
                if (label is RideLabel && label.pattern == patternIndex) return@forEach
                val entry = board(service, pattern, position, label, bufferSeconds) ?: return@forEach
                if (entries.none { it.dominates(entry, position) }) {
                    entries.removeAll { entry.dominates(it, position) }
                    entries += entry
                }
            }
        }
    }

    private fun board(
        service: PatternService,
        pattern: TransitPattern,
        position: Int,
        label: SearchLabel,
        bufferSeconds: Long,
    ): RouteEntry? {
        val threshold = label.arrival + bufferSeconds
        var bestTrip: LiveTrip? = null
        for (trip in service.liveTrips) {
            val time = trip.times[position]
            if (time == NotServed || trip.lastPosition <= position) continue
            if (time < threshold || time > timetable.liveHorizonEndEpochSecond) continue
            if (bestTrip == null || time < bestTrip.times[position]) bestTrip = trip
        }
        bestTrip?.let { trip ->
            return RouteEntry(
                boarding = label,
                boardPosition = position,
                departure = trip.times[position],
                lastPosition = trip.lastPosition,
                waitBasis = JourneyWaitBasis.LIVE_VEHICLE,
                vehicleId = trip.vehicleId,
                liveTimes = trip.times,
                rideOffsetSeconds = service.rideOffsetSeconds,
            )
        }
        return RouteEntry(
            boarding = label,
            boardPosition = position,
            departure = label.arrival + maxOf(service.headwaySeconds, bufferSeconds),
            lastPosition = pattern.size - 1,
            waitBasis = JourneyWaitBasis.HEADWAY_ESTIMATE,
            vehicleId = null,
            liveTimes = null,
            rideOffsetSeconds = service.rideOffsetSeconds,
        )
    }

    private fun relaxTransfers(rides: Map<Int, List<RideLabel>>): HashMap<Int, MutableList<SearchLabel>> {
        val next = HashMap<Int, MutableList<SearchLabel>>()
        rides.forEach { (stop, labels) ->
            budget.checkpoint()
            labels.forEach { ride ->
                next.getOrPut(stop, ::mutableListOf) += ride
                val neighbours = graph.walkNeighbours[stop]
                val meters = graph.walkNeighbourMeters[stop]
                neighbours.forEachIndexed { index, neighbour ->
                    val transfer = TransferLabel(neighbour, ride.arrival + walkingSeconds(meters[index]), ride, meters[index])
                    if (bag(neighbour).add(transfer)) next.getOrPut(neighbour, ::mutableListOf) += transfer
                }
            }
        }
        return next
    }

    private fun bag(stop: Int): LabelBag = best[stop] ?: LabelBag().also { best[stop] = it }

    private fun paretoFront(results: List<SearchResult>): List<SearchResult> {
        val front = mutableListOf<SearchResult>()
        results.sortedWith(compareBy(SearchResult::arrival, SearchResult::transfers, SearchResult::walkMeters))
            .forEach { candidate ->
                val dominated = front.any {
                    it.arrival <= candidate.arrival && it.transfers <= candidate.transfers &&
                        it.walkMeters <= candidate.walkMeters + 1.0
                }
                if (!dominated) front += candidate
            }
        return front
    }
}
