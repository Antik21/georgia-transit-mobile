package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyWaitBasis

internal sealed class PlannedLeg {
    abstract val departure: Long
    abstract val arrival: Long
}

internal class WalkLeg(
    val fromStop: Int?,
    val toStop: Int?,
    val fromPoint: GeoPoint,
    val toPoint: GeoPoint,
    val meters: Double,
    override val departure: Long,
    override val arrival: Long,
) : PlannedLeg()

internal class RideLeg(
    val pattern: Int,
    val boardPosition: Int,
    val alightPosition: Int,
    /** When the passenger reaches the boarding stop; the wait ends at [departure]. */
    val waitStart: Long,
    override val departure: Long,
    override val arrival: Long,
    val waitBasis: JourneyWaitBasis,
    val vehicleId: String?,
) : PlannedLeg() {
    val waitSeconds: Long get() = departure - waitStart
}

internal class PlannedItinerary(
    val departure: Long,
    val legs: List<PlannedLeg>,
    val routeSequence: List<String>,
) {
    val arrival: Long = legs.lastOrNull()?.arrival ?: departure
    val rides: List<RideLeg> = legs.filterIsInstance<RideLeg>()
    val transfers: Int = (rides.size - 1).coerceAtLeast(0)
    val walkMeters: Double = legs.filterIsInstance<WalkLeg>().sumOf(WalkLeg::meters)
    val durationSeconds: Long = arrival - departure
    private val walkSeconds: Long = legs.filterIsInstance<WalkLeg>().sumOf { it.arrival - it.departure }
    private val waitSeconds: Long = rides.sumOf(RideLeg::waitSeconds)

    /** Extra weight on top of total time: walking ×1.5, waiting ×1.2, four minutes per transfer. */
    val generalizedCost: Double = durationSeconds +
        (PlannerParameters.WalkWeight - 1.0) * walkSeconds +
        (PlannerParameters.WaitWeight - 1.0) * waitSeconds +
        PlannerParameters.TransferPenaltySeconds * transfers

    val isWalkOnly: Boolean get() = rides.isEmpty()
}

internal fun walkOnlyItinerary(from: GeoPoint, to: GeoPoint, departure: Long): PlannedItinerary {
    val meters = walkingMeters(from, to)
    return PlannedItinerary(
        departure = departure,
        legs = listOf(WalkLeg(null, null, from, to, meters, departure, departure + walkingSeconds(meters))),
        routeSequence = emptyList(),
    )
}

internal fun SearchResult.toItinerary(
    graph: TransitNetworkGraph,
    origin: GeoPoint,
    destination: GeoPoint,
    departure: Long,
): PlannedItinerary {
    val legs = ArrayDeque<PlannedLeg>()
    val lastStop = graph.stops[lastRide.stop].position
    legs.addFirstWalk(
        WalkLeg(lastRide.stop, null, lastStop, destination, egressMeters, lastRide.arrival, arrival),
    )
    var label: SearchLabel = lastRide
    while (true) {
        when (val current = label) {
            is RideLabel -> {
                legs.addFirst(
                    RideLeg(
                        pattern = current.pattern,
                        boardPosition = current.boardPosition,
                        alightPosition = current.alightPosition,
                        waitStart = current.boarding.arrival,
                        departure = current.departure,
                        arrival = current.arrival,
                        waitBasis = current.waitBasis,
                        vehicleId = current.vehicleId,
                    ),
                )
                label = current.boarding
            }
            is TransferLabel -> {
                // Distinct stops must stay joined by a walk, however short, for ride-to-ride continuity.
                legs.addFirst(
                    WalkLeg(
                        fromStop = current.from.stop,
                        toStop = current.stop,
                        fromPoint = graph.stops[current.from.stop].position,
                        toPoint = graph.stops[current.stop].position,
                        meters = current.legMeters,
                        departure = current.from.arrival,
                        arrival = current.arrival,
                    ),
                )
                label = current.from
            }
            is AccessLabel -> {
                val stopPosition = graph.stops[current.stop].position
                legs.addFirstWalk(WalkLeg(null, current.stop, origin, stopPosition, current.walkMeters, departure, current.arrival))
                break
            }
        }
    }
    return PlannedItinerary(
        departure = departure,
        legs = legs.toList(),
        routeSequence = legs.filterIsInstance<RideLeg>().map { graph.patterns[it.pattern].routeId },
    )
}

/** A sub-metre walk (origin or destination at the stop) is noise for the passenger. */
private fun ArrayDeque<PlannedLeg>.addFirstWalk(leg: WalkLeg) {
    if (leg.meters >= MinimumPassengerWalkMeters) addFirst(leg)
}
