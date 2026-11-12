package com.denis.georgiatransit.shared.domain.repository

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery

/**
 * Coordinate-bearing planning (ADR 0023). Implementations never cache, persist, or log the query
 * or its result; a failure is returned as is, with no last-known-good fallback.
 */
fun interface JourneyPlanRepository {
    suspend fun plan(cityId: CityId, query: JourneyPlanQuery): TransitLoadResult<JourneyPage>

    companion object {
        val Unconfigured = JourneyPlanRepository { _, _ ->
            TransitLoadResult.Failure(TransitFailure.Configuration("Journey planning is not configured"))
        }
    }
}
