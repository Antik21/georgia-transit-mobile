package com.denis.georgiatransit.bff.planning

/** ADR 0022 selection: role picks, slack and transfer filters, walk-only rule, mode ordering. */
internal object JourneySelection {
    fun select(
        transit: List<PlannedItinerary>,
        walkOnly: PlannedItinerary,
        mode: JourneyPlanMode,
    ): List<PlannedItinerary> {
        val order = comparator(mode)
        val undominated = transit.filterNot { walkOnly.arrival <= it.arrival && walkOnly.walkMeters <= it.walkMeters }
        val offerableWalk = walkOnly.takeIf { it.walkMeters <= PlannerParameters.WalkOnlyMaximumMeters }
        val candidates = withinSlack(worthTheirTransfers(distinctByRouteSequence(undominated, order)), offerableWalk)
        val picks = rolePicks(candidates).toMutableList()
        val earliestTransit = picks.minOfOrNull(PlannedItinerary::arrival)
        // With no transit at all, a walk longer than 2 km is not offered as a "plan".
        val includeWalk = walkOnly.walkMeters <= PlannerParameters.WalkOnlyMaximumMeters ||
            undominated.size < transit.size ||
            (earliestTransit != null && walkOnly.arrival < earliestTransit)
        if (includeWalk && picks.size == PlannerParameters.MaximumOptions) picks.removeAt(picks.lastIndex)
        val selected = if (includeWalk) picks + walkOnly else picks
        return selected.sortedWith(order)
    }

    /** Same route sequence means duplicate; the mode decides which variant represents it. */
    private fun distinctByRouteSequence(
        options: List<PlannedItinerary>,
        order: Comparator<PlannedItinerary>,
    ): List<PlannedItinerary> =
        options.groupBy(PlannedItinerary::routeSequence).values.map { group -> group.minWith(order) }

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

    private fun rolePicks(options: List<PlannedItinerary>): List<PlannedItinerary> {
        val remaining = options.toMutableList()
        val picks = mutableListOf<PlannedItinerary>()
        val earliest = remaining.minWithOrNull(compareBy(PlannedItinerary::arrival, PlannedItinerary::generalizedCost))
            ?: return picks
        picks += earliest
        remaining -= earliest
        remaining.filter { it.transfers < earliest.transfers }
            .minWithOrNull(compareBy(PlannedItinerary::generalizedCost, PlannedItinerary::arrival))
            ?.let { picks += it; remaining -= it }
        remaining.minWithOrNull(compareBy(PlannedItinerary::walkMeters, PlannedItinerary::arrival))
            ?.takeIf { it.walkMeters < picks.minOf(PlannedItinerary::walkMeters) }
            ?.let { picks += it }
        return picks
    }

    private fun comparator(mode: JourneyPlanMode): Comparator<PlannedItinerary> = when (mode) {
        JourneyPlanMode.FASTEST -> compareBy(PlannedItinerary::arrival, PlannedItinerary::generalizedCost)
        JourneyPlanMode.LESS_WALKING -> compareBy(PlannedItinerary::walkMeters, PlannedItinerary::generalizedCost)
        JourneyPlanMode.FEWER_TRANSFERS -> compareBy(PlannedItinerary::transfers, PlannedItinerary::generalizedCost)
    }
}
