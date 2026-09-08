package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.interactor.EstimateWalkingToStop
import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationFreshnessClock
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapWalkingEstimateViewModelTest {
    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    @Test
    fun freshAccurateFixIsDebouncedAndPublishesRoutedEstimateWithoutBlockingArrivals() = runTest {
        val repository = WalkingMapRepository()
        val location = locationSession(LocationPermissionState.Granted(LocationPrecision.Precise), accuracyMeters = 20.0)

        withOpenedSheet(repository, location) { viewModel ->
            val initial = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.NoArrivals>(initial.state)
            assertIs<WalkingEstimateUi.Loading>(initial.walkingEstimate)
            advanceTimeBy(699)
            runCurrent()
            assertEquals(0, repository.walkingRequests.size)
            advanceTimeBy(1)
            runCurrent()

            val ready = assertIs<WalkingEstimateUi.Ready>(
                requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).walkingEstimate,
            )
            assertEquals(WalkingEstimateSource.Routed, ready.source)
            assertEquals(420.0, ready.distanceMeters)
            assertEquals(360L, ready.durationSeconds)
            assertEquals(listOf(FIRST_FIX), repository.walkingRequests)
        }
    }

    @Test
    fun deniedLocationNeverSharesCoordinatesAndArrivalsRemainAvailable() = runTest {
        val deniedRepository = WalkingMapRepository()
        withOpenedSheet(deniedRepository, locationSession(LocationPermissionState.Denied)) { viewModel ->
            val sheet = requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet)
            assertIs<StopArrivalsSheetState.NoArrivals>(sheet.state)
            assertEquals(
                WalkingEstimateUi.Unavailable(WalkingEstimateUnavailableReason.PermissionDenied),
                sheet.walkingEstimate,
            )
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, deniedRepository.walkingRequests.size)
        }
    }

    @Test
    fun inaccurateLocationNeverSharesCoordinates() = runTest {
        val inaccurateRepository = WalkingMapRepository()
        val inaccurate = locationSession(
            LocationPermissionState.Granted(LocationPrecision.Approximate),
            accuracyMeters = 500.0,
        )
        withOpenedSheet(inaccurateRepository, inaccurate) { viewModel ->
            assertEquals(
                WalkingEstimateUi.Unavailable(WalkingEstimateUnavailableReason.NoAccurateFix),
                requireNotNull(viewModel.container.stateFlow.value.stopArrivalsSheet).walkingEstimate,
            )
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, inaccurateRepository.walkingRequests.size)
        }
    }

    @Test
    fun rapidlyReplacedFixCancelsPendingRequestAndOnlyLatestCoordinatesLeaveTheApp() = runTest {
        val repository = WalkingMapRepository()
        val location = locationSession(LocationPermissionState.Granted(LocationPrecision.Precise), accuracyMeters = 20.0)

        withOpenedSheet(repository, location) {
            advanceTimeBy(300)
            val request = location.nextCommandId()
            location.beginLocationRequest(request)
            location.accept(request, candidate(SECOND_FIX, accuracyMeters = 15.0))
            runCurrent()
            advanceTimeBy(700)
            runCurrent()

            assertEquals(listOf(SECOND_FIX), repository.walkingRequests)
        }
    }

    private suspend fun TestScope.withOpenedSheet(
        repository: WalkingMapRepository,
        location: RuntimeLocationSession,
        assertion: suspend TestScope.(MapViewModel) -> Unit,
    ) {
        val transitSession = RuntimeTransitSession().also { it.selectCity(repository.city) }
        val viewModel = MapViewModel(
            repository = repository,
            session = transitSession,
            locationSession = location,
            estimateWalkingToStop = EstimateWalkingToStop(repository),
            locationFreshnessClock = LocationFreshnessClock { NOW_MILLIS + testScheduler.currentTime },
        )
        viewModel.test(this) {
            runOnCreate()
            this@withOpenedSheet.runCurrent()
            repeat(20) {
                if (viewModel.container.stateFlow.value.renderState != null) return@repeat
                this@withOpenedSheet.advanceTimeBy(50)
                this@withOpenedSheet.runCurrent()
            }
            this@withOpenedSheet.advanceTimeBy(351)
            this@withOpenedSheet.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            val revision = requireNotNull(viewModel.container.stateFlow.value.renderState).stopSourceRevision
            viewModel.dispatchAction(Action.StopSelected(repository.stop.id, revision))
            this@withOpenedSheet.runCurrent()
            assertion(viewModel)
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@withOpenedSheet.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun TestScope.locationSession(
        permission: LocationPermissionState,
        accuracyMeters: Double? = null,
    ) = RuntimeLocationSession(
        scope = this,
        nowMillis = { NOW_MILLIS + testScheduler.currentTime },
    ).also { session ->
        session.updatePermission(permission)
        if (accuracyMeters != null) {
            val request = session.nextCommandId()
            session.beginLocationRequest(request)
            session.accept(request, candidate(FIRST_FIX, accuracyMeters))
        }
    }

    private fun candidate(point: GeoPoint, accuracyMeters: Double) = LocationFixCandidate(
        latitude = point.latitude,
        longitude = point.longitude,
        accuracyMeters = accuracyMeters,
        capturedAtEpochMillis = NOW_MILLIS,
    )

    private class WalkingMapRepository : TransitRepository {
        val city = TransitCity(
            id = CityId("tbilisi"),
            name = "Tbilisi",
            center = FIRST_FIX,
            capabilities = CityCapabilities(true, false, true, false, true),
        )
        val stop = TransitStop(
            id = StopId("tbilisi:fixture:stop:central"),
            providerId = ProviderId("central"),
            code = "1",
            name = LocalizedText("Центр", "Central", "ცენტრი"),
            position = GeoPoint(41.7160, 44.8280),
            routeIds = emptyList(),
            mode = TransitMode.Bus,
        )
        val walkingRequests = mutableListOf<GeoPoint>()

        override fun cities(): List<TransitCity> = listOf(city)
        override fun routes(cityId: CityId): List<TransitRoute> = emptyList()
        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: TransitLocale,
        ) = TransitLoadResult.Data(listOf(stop), TransitFreshness.Network)

        override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale) =
            TransitLoadResult.Data(
                ArrivalPage(emptyList(), ArrivalSource.Schedule, Instant.parse("2030-01-01T00:00:00Z"), false),
                TransitFreshness.Network,
            )

        override suspend fun walkingEstimate(cityId: CityId, from: GeoPoint, to: GeoPoint, locale: TransitLocale): TransitLoadResult<WalkingEstimate> {
            walkingRequests += from
            return TransitLoadResult.Data(
                WalkingEstimate(420.0, 360L, Instant.parse("2030-01-01T00:00:00Z")),
                TransitFreshness.Network,
            )
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
        val FIRST_FIX = GeoPoint(41.7151, 44.8271)
        val SECOND_FIX = GeoPoint(41.7155, 44.8275)
    }
}
