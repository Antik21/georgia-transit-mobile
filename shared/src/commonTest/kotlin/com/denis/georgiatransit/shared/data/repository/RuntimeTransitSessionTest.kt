package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeTransitSessionTest {
    @Test
    fun selectionRequiresTheActiveCityAndRejectsCrossCityDisabledAndOversizedSets() {
        val session = RuntimeTransitSession()
        val tbilisi = city("tbilisi")
        val disabled = city("disabled", routes = false)
        val selected = setOf(RouteId("opaque:route:1"))

        assertFalse(session.selectRoutes(tbilisi.id, selected))
        session.selectCity(tbilisi)
        assertTrue(session.selectRoutes(tbilisi.id, selected))
        assertFalse(session.selectRoutes(CityId("batumi"), emptySet()))
        assertEquals(selected, session.selectedRouteIds.value)

        val oversized = (0..RouteSelectionPolicy.MaximumSelectedRoutes)
            .mapTo(linkedSetOf()) { RouteId("opaque:route:$it") }
        assertFalse(session.selectRoutes(tbilisi.id, oversized))
        assertEquals(selected, session.selectedRouteIds.value)

        session.selectCity(disabled)
        assertTrue(session.selectedRouteIds.value.isEmpty())
        assertFalse(session.selectRoutes(disabled.id, selected))
    }

    @Test
    fun differentCityClearsAndPersistsEmptyWhileSameCityRefreshPreservesSelection() {
        val store = RecordingStore()
        val session = RuntimeTransitSession(store)
        val originalTbilisi = city("tbilisi")
        val refreshedTbilisi = originalTbilisi.copy(name = "Tbilisi refreshed")
        val batumi = city("batumi")
        val selected = linkedSetOf(RouteId("opaque:route:1"), RouteId("opaque:route:2"))

        session.selectCity(originalTbilisi)
        assertTrue(session.selectRoutes(originalTbilisi.id, selected))
        session.selectCity(refreshedTbilisi)

        assertEquals(refreshedTbilisi, session.selectedCity.value)
        assertEquals(selected, session.selectedRouteIds.value)
        assertEquals(CachedCitySnapshot(city = refreshedTbilisi, selectedRouteIds = selected), store.saved.last())

        session.selectCity(batumi)

        assertEquals(batumi, session.selectedCity.value)
        assertTrue(session.selectedRouteIds.value.isEmpty())
        assertEquals(CachedCitySnapshot(city = batumi), store.saved.last())
    }

    @Test
    fun sameCityRoutesCapabilityOffClearsSelectionAndReenableCannotReviveIt() {
        val session = RuntimeTransitSession()
        val enabled = city("tbilisi", routes = true)
        val disabled = enabled.copy(capabilities = enabled.capabilities.copy(routes = false))
        val selected = setOf(RouteId("opaque:route:1"))

        session.selectCity(enabled)
        assertTrue(session.selectRoutes(enabled.id, selected))
        session.selectCity(disabled)
        assertTrue(session.selectedRouteIds.value.isEmpty())

        session.selectCity(enabled)
        assertTrue(session.selectedRouteIds.value.isEmpty())
    }

    @Test
    fun clearRemovesDurableSnapshotAndOrdinaryPersistenceFailuresDoNotRollbackMemory() {
        val store = RecordingStore().apply { saveFailure = IllegalStateException("disk unavailable") }
        val session = RuntimeTransitSession(store)
        val city = city("tbilisi")
        val selected = setOf(RouteId("opaque:route:1"))

        session.selectCity(city)
        assertTrue(session.selectRoutes(city.id, selected))
        assertEquals(city, session.selectedCity.value)
        assertEquals(selected, session.selectedRouteIds.value)

        store.saveFailure = null
        session.clearSelectedCity()

        assertNull(session.selectedCity.value)
        assertTrue(session.selectedRouteIds.value.isEmpty())
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun persistenceCancellationIsRethrownAfterTheInMemoryMutation() {
        val store = RecordingStore().apply { saveFailure = CancellationException("cancelled") }
        val session = RuntimeTransitSession(store)
        val city = city("tbilisi")

        assertFailsWith<CancellationException> { session.selectCity(city) }
        assertEquals(city, session.selectedCity.value)
    }

    private fun city(id: String, routes: Boolean = true) = TransitCity(
        id = CityId(id),
        name = id,
        center = GeoPoint(0.0, 0.0),
        capabilities = CityCapabilities(
            stops = true,
            vehicles = true,
            arrivals = true,
            routeShapes = true,
            journeyPlanning = true,
            routes = routes,
        ),
    )

    private class RecordingStore : SelectedCityStore {
        val saved = mutableListOf<CachedCitySnapshot>()
        var clearCalls = 0
        var saveFailure: Throwable? = null

        override fun read(): CachedCitySnapshot? = null

        override fun save(snapshot: CachedCitySnapshot) {
            saveFailure?.let { throw it }
            saved += snapshot
        }

        override fun save(city: TransitCity) = save(CachedCitySnapshot(city = city))

        override fun clear() {
            clearCalls += 1
        }
    }
}
