package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionNavigationEventApplierTest {
    private val citySelectionStack: List<Destination> = listOf(Destination.CitySelection)
    private val mapStack: List<Destination> = listOf(Destination.Map)
    private val routesStack: List<Destination> = listOf(Destination.Map, Destination.Routes)

    @Test
    fun cityConfirmedUsesCitySelectedImmediatelyBeforeNavigation() {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val applier = SessionNavigationEventApplier(session)
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }

        assertNull(session.selectedCity.value)
        session.selectCity(tbilisi)

        assertEquals(
            mapStack,
            applier.apply(citySelectionStack, NavigationEvent.CityConfirmed),
        )
    }

    @Test
    fun noCitySessionSnapshotCannotConfirmOrOpenCityDependentDestinations() {
        val applier = SessionNavigationEventApplier(RuntimeTransitSession())

        assertEquals(
            citySelectionStack,
            applier.apply(mapStack, NavigationEvent.CityConfirmed),
        )
        assertEquals(
            citySelectionStack,
            applier.apply(citySelectionStack, NavigationEvent.OpenRoutes),
        )
    }

    @Test
    fun confirmedAndDismissedRouteEntriesBothPopOnlyTheRoutesEntry() {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession().also { it.selectCity(repository.cities().first()) }
        val applier = SessionNavigationEventApplier(session)

        listOf(NavigationEvent.RoutesConfirmed, NavigationEvent.RoutesDismissed, NavigationEvent.Back).forEach { event ->
            assertEquals(mapStack, applier.apply(routesStack, event), "$event must return to Map")
            assertEquals(mapStack, applier.apply(mapStack, event), "$event must not pop Map")
        }
    }
}
