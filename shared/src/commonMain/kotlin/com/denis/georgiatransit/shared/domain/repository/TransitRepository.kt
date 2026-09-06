package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import kotlinx.coroutines.flow.StateFlow

interface TransitRepository {
    fun cities(): List<TransitCity>
    fun routes(cityId: CityId): List<TransitRoute>
}

interface TransitSession {
    val selectedCity: StateFlow<TransitCity?>
    val selectedRouteIds: StateFlow<Set<RouteId>>

    fun selectCity(city: TransitCity)
    fun selectRoutes(routeIds: Set<RouteId>)
}

