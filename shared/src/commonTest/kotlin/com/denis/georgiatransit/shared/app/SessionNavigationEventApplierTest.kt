package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionNavigationEventApplierTest {
    private val citySelectionStack: List<Destination> = listOf(Destination.CitySelection)
    private val mapStack: List<Destination> = listOf(Destination.Map)

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
}
