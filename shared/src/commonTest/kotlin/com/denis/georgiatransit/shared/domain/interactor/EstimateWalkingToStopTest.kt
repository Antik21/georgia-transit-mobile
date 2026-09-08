package com.denis.georgiatransit.shared.domain.interactor

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class EstimateWalkingToStopTest {
    @Test
    fun routedEstimateIsUsedOnlyWhenTripPlanningIsAvailableAndValid() = runTest {
        val expected = WalkingEstimate(420.0, 360, NOW)
        val repository = WalkingRepository(TransitLoadResult.Data(expected, TransitFreshness.Network))
        val result = EstimateWalkingToStop(repository, WalkingEstimateClock { NOW })(
            city(journeyPlanning = true), FROM, TO, TransitLocale.Georgian,
        )

        assertEquals(WalkingEstimateSource.Routed, result.source)
        assertEquals(expected, result.estimate)
        assertEquals(1, repository.calls)
        assertEquals(TransitLocale.Georgian, repository.locale)
    }

    @Test
    fun unavailableCapabilityAndRemoteFailuresUseHonestDeterministicApproximation() = runTest {
        val disabledRepository = WalkingRepository(TransitLoadResult.Empty(TransitFreshness.Network))
        val disabled = EstimateWalkingToStop(disabledRepository, WalkingEstimateClock { NOW })(
            city(journeyPlanning = false), FROM, TO, TransitLocale.English,
        )
        assertEquals(0, disabledRepository.calls)
        assertEquals(WalkingEstimateSource.Approximate, disabled.source)
        assertEquals(NOW, disabled.estimate.observedAt)
        assertTrue(disabled.estimate.distanceMeters > 1_000.0)
        assertTrue(disabled.estimate.durationSeconds > 0)

        val failures = listOf<TransitLoadResult<WalkingEstimate>>(
            TransitLoadResult.Failure(TransitFailure.UpstreamUnavailable("offline", null, null)),
            TransitLoadResult.Empty(TransitFreshness.Network),
            TransitLoadResult.Data(WalkingEstimate(Double.NaN, 0, NOW), TransitFreshness.Network),
        )
        failures.forEach { failure ->
            val result = EstimateWalkingToStop(WalkingRepository(failure), WalkingEstimateClock { NOW })(
                city(journeyPlanning = true), FROM, TO, TransitLocale.Russian,
            )
            assertEquals(disabled, result)
        }
    }

    @Test
    fun cancellationAlwaysPropagates() = runTest {
        val repository = object : WalkingRepository(TransitLoadResult.Empty(TransitFreshness.Network)) {
            override suspend fun walkingEstimate(
                cityId: CityId,
                from: GeoPoint,
                to: GeoPoint,
                locale: TransitLocale,
            ): TransitLoadResult<WalkingEstimate> = throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            EstimateWalkingToStop(repository)(city(), FROM, TO, TransitLocale.English)
        }
    }

    private fun city(journeyPlanning: Boolean = true) = TransitCity(
        id = CityId("tbilisi"),
        name = "Tbilisi",
        center = FROM,
        capabilities = CityCapabilities(true, false, true, false, journeyPlanning),
    )

    private open class WalkingRepository(
        private val result: TransitLoadResult<WalkingEstimate>,
    ) : TransitRepository {
        var calls = 0
        var locale: TransitLocale? = null
        override fun cities(): List<TransitCity> = emptyList()
        override fun routes(cityId: CityId): List<TransitRoute> = emptyList()
        override suspend fun walkingEstimate(
            cityId: CityId,
            from: GeoPoint,
            to: GeoPoint,
            locale: TransitLocale,
        ): TransitLoadResult<WalkingEstimate> {
            calls++
            this.locale = locale
            return result
        }
    }

    private companion object {
        val NOW = Instant.parse("2030-01-01T00:00:00Z")
        val FROM = GeoPoint(41.7151, 44.8271)
        val TO = GeoPoint(41.7251, 44.8371)
    }
}
