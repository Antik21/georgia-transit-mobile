package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.model.CityId

/**
 * Pure, common navigation policy for the application shell.
 *
 * The policy deliberately accepts only a city identifier instead of a city entity so that
 * destinations never become a second source of selection state. It also makes restoration
 * decisions deterministic on every platform.
 */
object NavigationTransitionPolicy {
    /** Returns the safe shell root for a newly-created navigation stack. */
    fun initialStack(selectedCityId: CityId?): List<Destination> =
        if (selectedCityId == null) listOf(Destination.CitySelection) else listOf(Destination.Map)

    /**
     * Canonicalizes a restored stack against the current session prerequisite.
     *
     * A missing city always wins over restored navigation state: Map and Routes are city-dependent
     * destinations and must never be displayed without a selected city. With a city, malformed
     * stacks are reduced to one of the legal shell stacks.
     */
    fun restore(
        restoredStack: List<Destination>,
        selectedCityId: CityId?,
    ): List<Destination> {
        if (selectedCityId == null) return listOf(Destination.CitySelection)

        return when (restoredStack.lastOrNull()) {
            null -> listOf(Destination.Map)
            Destination.CitySelection -> listOf(Destination.CitySelection)
            Destination.Map -> listOf(Destination.Map)
            Destination.Routes -> listOf(Destination.Map, Destination.Routes)
        }
    }

    /** Applies a user navigation event after enforcing the current session prerequisite. */
    fun transition(
        currentStack: List<Destination>,
        selectedCityId: CityId?,
        event: NavigationEvent,
    ): List<Destination> {
        val safeStack = restore(currentStack, selectedCityId)

        return when (event) {
            NavigationEvent.CityConfirmed ->
                if (selectedCityId == null) listOf(Destination.CitySelection) else listOf(Destination.Map)

            NavigationEvent.OpenRoutes ->
                if (selectedCityId == null) listOf(Destination.CitySelection)
                else listOf(Destination.Map, Destination.Routes)

            NavigationEvent.RoutesConfirmed,
            NavigationEvent.RoutesDismissed,
            NavigationEvent.Back,
            -> when (safeStack) {
                listOf(Destination.Map, Destination.Routes) -> listOf(Destination.Map)
                else -> safeStack
            }

            NavigationEvent.ChangeCity -> listOf(Destination.CitySelection)
        }
    }
}

/** Typed navigation events emitted by the common presentation shell. */
sealed interface NavigationEvent {
    data object CityConfirmed : NavigationEvent
    data object OpenRoutes : NavigationEvent
    data object RoutesConfirmed : NavigationEvent
    data object RoutesDismissed : NavigationEvent
    data object ChangeCity : NavigationEvent
    data object Back : NavigationEvent
}
