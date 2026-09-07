package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import kotlinx.coroutines.flow.StateFlow

interface TransitRepository {
    fun cities(): List<TransitCity>

    /**
     * Loads the current city and capability snapshot used to bootstrap the application.
     *
     * The preview implementation remains synchronous today. A future BFF-backed repository can
     * override this boundary without forcing product screens to know about its transport.
     */
    suspend fun loadCityCapabilitySnapshot(): List<TransitCity> = cities()

    fun routes(cityId: CityId): List<TransitRoute>
}

interface TransitSession {
    val selectedCity: StateFlow<TransitCity?>
    val selectedRouteIds: StateFlow<Set<RouteId>>

    fun selectCity(city: TransitCity)
    fun selectRoutes(routeIds: Set<RouteId>)
    /** Clears all city-dependent session state when bootstrap validation fails. */
    fun clearSelectedCity()
}
