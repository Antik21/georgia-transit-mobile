package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.data.network.TransitBffClient
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult

/** Deliberately bypasses [com.denis.georgiatransit.shared.data.cache.TransitCache]: plans are never stored (ADR 0023). */
class BffJourneyPlanRepository(private val client: TransitBffClient) : JourneyPlanRepository {
    override suspend fun plan(cityId: CityId, query: JourneyPlanQuery): TransitLoadResult<JourneyPage> =
        client.planJourney(cityId, query)
}
