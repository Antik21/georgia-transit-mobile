package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.model.CityId
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTransitionPolicyTest {
    private val cityId = CityId("tbilisi")
    private val citySelectionStack: List<Destination> = listOf(Destination.CitySelection)
    private val mapStack: List<Destination> = listOf(Destination.Map)
    private val changeCityStack: List<Destination> = listOf(Destination.Map, Destination.CitySelection)
    private val routesStack: List<Destination> = listOf(Destination.Map, Destination.Routes)

    @Test
    fun initialStackRequiresCityBeforeOpeningMap() {
        assertEquals(citySelectionStack, NavigationTransitionPolicy.initialStack(selectedCityId = null))
        assertEquals(mapStack, NavigationTransitionPolicy.initialStack(selectedCityId = cityId))
    }

    @Test
    fun restoreWithoutCityRejectsEveryRestoredDestinationPermutation() {
        restoredDestinationPermutations().forEach { restoredStack ->
            assertEquals(
                citySelectionStack,
                NavigationTransitionPolicy.restore(restoredStack, selectedCityId = null),
                "restored stack: $restoredStack",
            )
        }
    }

    @Test
    fun restoreWithCityCanonicalizesEmptyAndMalformedDestinationPermutations() {
        restoredDestinationPermutations().forEach { restoredStack ->
            val expected = when (restoredStack.lastOrNull()) {
                null -> mapStack
                Destination.CitySelection -> if (restoredStack == changeCityStack) changeCityStack else citySelectionStack
                Destination.Map -> mapStack
                Destination.Routes -> routesStack
                is Destination.Schedules -> mapStack
            }

            assertEquals(
                expected,
                NavigationTransitionPolicy.restore(restoredStack, selectedCityId = cityId),
                "restored stack: $restoredStack",
            )
        }
    }

    @Test
    fun cityConfirmedAndOpenRoutesRequireAnAuthoritativeCity() {
        assertEquals(
            citySelectionStack,
            NavigationTransitionPolicy.transition(mapStack, selectedCityId = null, NavigationEvent.CityConfirmed),
        )
        assertEquals(
            citySelectionStack,
            NavigationTransitionPolicy.transition(routesStack, selectedCityId = null, NavigationEvent.OpenRoutes),
        )
        assertEquals(
            mapStack,
            NavigationTransitionPolicy.transition(citySelectionStack, selectedCityId = cityId, NavigationEvent.CityConfirmed),
        )
        assertEquals(
            routesStack,
            NavigationTransitionPolicy.transition(mapStack, selectedCityId = cityId, NavigationEvent.OpenRoutes),
        )
    }

    @Test
    fun everyEventWithoutCityResolvesToCitySelection() {
        val events = listOf(
            NavigationEvent.CityConfirmed,
            NavigationEvent.OpenRoutes,
            NavigationEvent.OpenSchedules,
            NavigationEvent.RoutesConfirmed,
            NavigationEvent.RoutesDismissed,
            NavigationEvent.Back,
            NavigationEvent.ChangeCity,
        )

        restoredDestinationPermutations().forEach { currentStack ->
            events.forEach { event ->
                assertEquals(
                    citySelectionStack,
                    NavigationTransitionPolicy.transition(currentStack, selectedCityId = null, event),
                    "event: $event; current stack: $currentStack",
                )
            }
        }
    }

    @Test
    fun routesConfirmationDismissalAndBackPopRoutesButAreNoOpsAtRoots() {
        listOf(NavigationEvent.RoutesConfirmed, NavigationEvent.RoutesDismissed, NavigationEvent.Back).forEach { event ->
            assertEquals(
                mapStack,
                NavigationTransitionPolicy.transition(routesStack, selectedCityId = cityId, event),
                "$event should pop Routes",
            )
            assertEquals(
                mapStack,
                NavigationTransitionPolicy.transition(mapStack, selectedCityId = cityId, event),
                "$event should be a no-op at Map",
            )
            assertEquals(
                citySelectionStack,
                NavigationTransitionPolicy.transition(citySelectionStack, selectedCityId = cityId, event),
                "$event should be a no-op at CitySelection",
            )
            assertEquals(
                citySelectionStack,
                NavigationTransitionPolicy.transition(routesStack, selectedCityId = null, event),
                "$event must not leave a city-dependent destination without a city",
            )
        }
    }

    @Test
    fun changeCityPushesSelectionOverMapAndReturnsToMapOnBackOrConfirmation() {
        assertEquals(
            changeCityStack,
            NavigationTransitionPolicy.transition(mapStack, selectedCityId = cityId, NavigationEvent.ChangeCity),
        )
        assertEquals(changeCityStack, NavigationTransitionPolicy.restore(changeCityStack, cityId))
        assertEquals(citySelectionStack, NavigationTransitionPolicy.restore(changeCityStack, selectedCityId = null))
        listOf(NavigationEvent.Back, NavigationEvent.CityConfirmed).forEach { event ->
            assertEquals(mapStack, NavigationTransitionPolicy.transition(changeCityStack, cityId, event))
        }
        listOf(citySelectionStack, changeCityStack, routesStack).forEach { currentStack ->
            assertEquals(currentStack, NavigationTransitionPolicy.transition(currentStack, cityId, NavigationEvent.ChangeCity))
        }
    }

    @Test
    fun schedulesRequireCapabilityAndRestoreOnlyForTheSameCity() {
        val kutaisi = CityId("kutaisi")
        val batumi = CityId("batumi")
        val schedulesStack = listOf(Destination.Map, Destination.Schedules(kutaisi))
        assertEquals(
            schedulesStack,
            NavigationTransitionPolicy.transition(mapStack, kutaisi, NavigationEvent.OpenSchedules, schedulesAvailable = true),
        )
        assertEquals(mapStack, NavigationTransitionPolicy.transition(mapStack, batumi, NavigationEvent.OpenSchedules))
        assertEquals(
            mapStack,
            NavigationTransitionPolicy.transition(schedulesStack, kutaisi, NavigationEvent.Back, schedulesAvailable = true),
        )
        assertEquals(schedulesStack, NavigationTransitionPolicy.restore(schedulesStack, kutaisi, schedulesAvailable = true))
        assertEquals(mapStack, NavigationTransitionPolicy.restore(schedulesStack, kutaisi))
        assertEquals(mapStack, NavigationTransitionPolicy.restore(schedulesStack, batumi, schedulesAvailable = true))
        assertEquals(citySelectionStack, NavigationTransitionPolicy.restore(schedulesStack, null, schedulesAvailable = true))
        assertEquals(routesStack, NavigationTransitionPolicy.transition(mapStack, batumi, NavigationEvent.OpenRoutes))
    }

    private fun restoredDestinationPermutations(): List<List<Destination>> = buildList {
        add(emptyList<Destination>())
        val destinations: List<Destination> = listOf(Destination.CitySelection, Destination.Map, Destination.Routes)
        repeat(3) { lengthIndex ->
            buildDestinationPermutations(destinations, lengthIndex + 1, emptyList<Destination>(), this)
        }
    }

    private fun buildDestinationPermutations(
        destinations: List<Destination>,
        remainingLength: Int,
        prefix: List<Destination>,
        output: MutableList<List<Destination>>,
    ) {
        if (remainingLength == 0) {
            output += prefix
            return
        }
        destinations.forEach { destination ->
            buildDestinationPermutations(destinations, remainingLength - 1, prefix + destination, output)
        }
    }
}
