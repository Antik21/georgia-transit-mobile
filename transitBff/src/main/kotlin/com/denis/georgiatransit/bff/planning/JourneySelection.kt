package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.JourneyLabel

internal class LabelledItinerary(val itinerary: PlannedItinerary, val labels: List<JourneyLabel>)

/** ADR 0022 selection: admissible options, flight-search style labels, cap, and page order. */
internal object JourneySelection {
    private val byCost = compareBy(PlannedItinerary::generalizedCost, PlannedItinerary::arrival)

    fun select(transit: List<PlannedItinerary>, walkOnly: PlannedItinerary): List<LabelledItinerary> {
        val options = admissible(transit, walkOnly)
        val labels = if (options.size >= 2) labels(options) else emptyMap()
        val kept = if (options.size <= PlannerParameters.MaximumOptions) {
            options
        } else {
            val labelled = options.filter { it in labels }
            labelled + options.filterNot { it in labels }.sortedWith(byCost).take(PlannerParameters.MaximumOptions - labelled.size)
        }
        val optimal = labels.entries.firstOrNull { JourneyLabel.OPTIMAL in it.value }?.key
        return kept.sortedWith(
            compareBy<PlannedItinerary> { it != optimal }
                .thenBy(PlannedItinerary::arrival)
                .thenBy(PlannedItinerary::generalizedCost),
        ).map { LabelledItinerary(it, labels[it].orEmpty().sorted()) }
    }

    private fun admissible(transit: List<PlannedItinerary>, walkOnly: PlannedItinerary): List<PlannedItinerary> {
        val undominated = transit.filterNot { walkOnly.arrival <= it.arrival && walkOnly.walkMeters <= it.walkMeters }
        val offerableWalk = walkOnly.takeIf { it.walkMeters <= PlannerParameters.WalkOnlyMaximumMeters }
        val candidates = withinSlack(worthTheirTransfers(distinctByRouteSequence(undominated)), offerableWalk)
        val earliestTransit = candidates.minOfOrNull(PlannedItinerary::arrival)
        // With no transit at all, a walk longer than 2 km is not offered as a "plan".
        val includeWalk = walkOnly.walkMeters <= PlannerParameters.WalkOnlyMaximumMeters ||
            undominated.size < transit.size ||
            (earliestTransit != null && walkOnly.arrival < earliestTransit)
        return if (includeWalk) candidates + walkOnly else candidates
    }

    private fun distinctByRouteSequence(options: List<PlannedItinerary>): List<PlannedItinerary> =
        options.groupBy(PlannedItinerary::routeSequence).values.map { group -> group.minWith(byCost) }

    /** A transfer must buy at least five minutes over every option with fewer transfers. */
    private fun worthTheirTransfers(options: List<PlannedItinerary>): List<PlannedItinerary> =
        options.filter { option ->
            val fewer = options.filter { it.transfers < option.transfers }
            fewer.isEmpty() ||
                option.arrival + PlannerParameters.TransferMinimumSavingSeconds <= fewer.minOf(PlannedItinerary::arrival)
        }

    private fun withinSlack(options: List<PlannedItinerary>, walk: PlannedItinerary?): List<PlannedItinerary> {
        val earliest = (options + listOfNotNull(walk)).minByOrNull(PlannedItinerary::arrival) ?: return emptyList()
        val slack = maxOf(
            PlannerParameters.SlackMinimumSeconds,
            (earliest.durationSeconds * PlannerParameters.SlackFraction).toLong(),
        )
        return options.filter { it.arrival <= earliest.arrival + slack }
    }

    private fun labels(options: List<PlannedItinerary>): Map<PlannedItinerary, Set<JourneyLabel>> {
        val transit = options.filterNot(PlannedItinerary::isWalkOnly)
        val picks = buildMap {
            put(JourneyLabel.OPTIMAL, options.minWith(byCost))
            put(JourneyLabel.FASTEST, options.minWith(compareBy(PlannedItinerary::arrival, PlannedItinerary::generalizedCost)))
            transit.minWithOrNull(compareBy(PlannedItinerary::walkMeters, PlannedItinerary::generalizedCost))
                ?.let { put(JourneyLabel.LEAST_WALKING, it) }
            if (transit.any { it.transfers > 0 }) {
                transit.filter { it.transfers == 0 }.minWithOrNull(byCost)?.let { put(JourneyLabel.NO_TRANSFERS, it) }
            }
        }
        return picks.entries.groupBy({ it.value }, { it.key }).mapValues { (_, labels) -> labels.toSet() }
    }
}
