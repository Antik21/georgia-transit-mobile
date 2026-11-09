package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId

interface RecentRouteHistory {
    suspend fun read(cityId: CityId): List<RouteId>
    suspend fun record(cityId: CityId, selectedRoutes: Set<RouteId>)

    object None : RecentRouteHistory {
        override suspend fun read(cityId: CityId): List<RouteId> = emptyList()
        override suspend fun record(cityId: CityId, selectedRoutes: Set<RouteId>) = Unit
    }
}
