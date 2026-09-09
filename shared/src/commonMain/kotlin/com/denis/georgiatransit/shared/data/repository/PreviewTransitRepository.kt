package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.data.persistence.NoOpSelectedCityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreviewTransitRepository : TransitRepository {
    private val cityList = listOf(
        TransitCity(
            id = CityId("tbilisi"),
            name = "Tbilisi",
            center = GeoPoint(41.7151, 44.8271),
            capabilities = CityCapabilities(true, true, true, true, true),
        ),
        TransitCity(
            id = CityId("batumi"),
            name = "Batumi",
            center = GeoPoint(41.6461, 41.6405),
            capabilities = CityCapabilities(true, true, true, true, false, experimental = true),
        ),
        TransitCity(
            id = CityId("kutaisi"),
            name = "Kutaisi",
            center = GeoPoint(42.2679, 42.6946),
            capabilities = CityCapabilities(false, false, false, false, false, experimental = true, routes = false),
        ),
    )

    private val routeList = listOf(
        TransitRoute(RouteId("tbilisi:preview:route:301"), CityId("tbilisi"), "301", "Station Square — Varketili", 0xFF2A9D8F),
        TransitRoute(RouteId("tbilisi:preview:route:337"), CityId("tbilisi"), "337", "Airport — Station Square", 0xFFE76F51),
        TransitRoute(RouteId("tbilisi:preview:route:395"), CityId("tbilisi"), "395", "University — Freedom Square", 0xFF457B9D),
        TransitRoute(RouteId("batumi:preview:route:1"), CityId("batumi"), "1", "Airport — Botanical Garden", 0xFF6D597A),
        TransitRoute(RouteId("batumi:preview:route:10"), CityId("batumi"), "10", "Old Boulevard — Airport", 0xFFF4A261),
    )

    override fun cities(): List<TransitCity> = cityList

    override fun routes(cityId: CityId): List<TransitRoute> = routeList.filter { it.cityId == cityId }
}

class RuntimeTransitSession(
    private val selectedCityStore: SelectedCityStore = NoOpSelectedCityStore,
) : TransitSession {
    private val mutableCity = MutableStateFlow<TransitCity?>(null)
    private val mutableRoutes = MutableStateFlow<Set<RouteId>>(emptySet())

    override val selectedCity: StateFlow<TransitCity?> = mutableCity.asStateFlow()
    override val selectedRouteIds: StateFlow<Set<RouteId>> = mutableRoutes.asStateFlow()

    override fun selectCity(city: TransitCity) {
        if (mutableCity.value?.id != city.id || !city.capabilities.routes) clearRouteSelection()
        mutableCity.value = city
        persistSelection(city, mutableRoutes.value)
    }

    override fun selectRoutes(cityId: CityId, routeIds: Set<RouteId>): Boolean {
        val city = mutableCity.value ?: return false
        if (city.id != cityId || !city.capabilities.routes || !RouteSelectionPolicy.isValid(routeIds)) return false

        val replacement = routeIds.toSet()
        mutableRoutes.value = replacement
        persistSelection(city, replacement)
        return true
    }

    override fun restoreCitySelection(city: TransitCity, routeIds: Set<RouteId>) {
        val restoredRoutes = routeIds.takeIf { city.capabilities.routes }
            ?.let(RouteSelectionPolicy::sanitized)
            .orEmpty()
        // Publish the route portion before city so a collector never sees a selected city paired
        // with a synthetic empty selection during cold-start restoration.
        mutableRoutes.value = restoredRoutes
        mutableCity.value = city
        persistSelection(city, restoredRoutes)
    }

    override fun clearSelectedCity() {
        clearRouteSelection()
        mutableCity.value = null
        clearPersistedSelection()
    }

    private fun clearRouteSelection() {
        mutableRoutes.value = emptySet()
    }

    private fun persistSelection(city: TransitCity, routeIds: Set<RouteId>) {
        try {
            selectedCityStore.save(
                CachedCitySnapshot(
                    city = city,
                    selectedRouteIds = routeIds,
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // A failed cache write must not prevent the in-memory selection from opening Map.
        }
    }

    private fun clearPersistedSelection() {
        try {
            selectedCityStore.clear()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // A stale durable value will be revalidated at the next bootstrap attempt.
        }
    }
}
