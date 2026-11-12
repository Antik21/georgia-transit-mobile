package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.provider.RealtimeJourneys

/** Provider-agnostic planner over an immutable graph and one live timetable snapshot. */
internal class TransitJourneyPlanner(
    private val graph: TransitNetworkGraph,
    private val timetable: NetworkTimetable,
    cityId: String,
    providerId: String,
) {
    private val mapper = JourneyResponseMapper(graph, cityId, providerId)

    fun plan(query: JourneyPlanQuery, budget: PlanningBudget): RealtimeJourneys {
        require(query.maxTransfers in 0..PlannerParameters.MaximumTransfers) { "maxTransfers is out of range" }
        val departure = query.departureAt.epochSecond
        val walkOnly = walkOnlyItinerary(query.from, query.to, departure)
        val selected = if (greatCircleMeters(query.from, query.to) < PlannerParameters.SamePlaceMeters) {
            listOf(walkOnly)
        } else {
            val transit = JourneySearch(graph, timetable, budget)
                .search(query.from, query.to, departure, query.maxTransfers, query.transferBufferSeconds.toLong())
                .map { it.toItinerary(graph, query.from, query.to, departure) }
            budget.checkpoint()
            JourneySelection.select(transit, walkOnly, query.mode)
        }
        val rides = selected.flatMap(PlannedItinerary::rides)
        return RealtimeJourneys(
            items = selected.mapIndexed { rank, itinerary -> mapper.journey(itinerary, rank) },
            source = ArrivalSource.CLIENT_ESTIMATE,
            realtime = rides.isNotEmpty() && rides.all { it.waitBasis == JourneyWaitBasis.LIVE_VEHICLE },
            observedAt = timetable.observedAt,
            stale = timetable.stale,
        )
    }
}
