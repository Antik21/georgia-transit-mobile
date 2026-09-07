package com.denis.georgiatransit.shared.presentation.splash

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.TestSettings
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @Test
    fun retryMovesFromErrorThroughLoadingToReady() = runTest {
        val repository = FailingThenReadyRepository()
        val viewModel = SplashViewModel(bootstrap(repository))

        viewModel.test(this, settings = TestSettings(autoCheckInitialState = false)) {
            expectState(ViewState.Loading)
            runOnCreate()
            expectState(ViewState.Error(BootstrapTransitSession.Failure.Unavailable))
            viewModel.container.joinIntents()

            assertEquals(1, repository.snapshotCalls)

            viewModel.dispatchAction(Action.RetryClicked)
            expectState(ViewState.Loading)
            expectState(ViewState.Ready(Destination.CitySelection))

            assertEquals(2, repository.snapshotCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun duplicateRetriesWhileBootstrapIsRunningStartOnlyOneRequest() = runTest {
        lateinit var viewModel: SplashViewModel
        val repository = RetryDispatchingRepository {
            viewModel.dispatchAction(Action.RetryClicked)
            viewModel.dispatchAction(Action.RetryClicked)
        }
        viewModel = SplashViewModel(bootstrap(repository))

        viewModel.test(this, settings = TestSettings(autoCheckInitialState = false)) {
            expectState(ViewState.Loading)
            runOnCreate()
            expectState(ViewState.Ready(Destination.CitySelection))

            assertEquals(1, repository.snapshotCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun bootstrap(repository: TransitRepository): BootstrapTransitSession {
        val store = object : SelectedCityStore {
            override fun read() = null
            override fun save(city: TransitCity) = Unit
            override fun clear() = Unit
        }
        return BootstrapTransitSession(
            repository = repository,
            session = RuntimeTransitSession(store),
            selectedCityStore = store,
            runtimeConfigurationSource = object : RuntimeBootstrapConfigurationSource {
                override suspend fun load() = RuntimeBootstrapConfiguration.default
            },
        )
    }

    private class FailingThenReadyRepository : TransitRepository {
        var snapshotCalls = 0
            private set

        override fun cities(): List<TransitCity> = listOf(tbilisi)

        override suspend fun loadCityCapabilitySnapshot(): List<TransitCity> {
            snapshotCalls += 1
            return when (snapshotCalls) {
                1 -> throw IllegalStateException("Initial snapshot unavailable")
                2 -> listOf(tbilisi)
                else -> error("Unexpected bootstrap request")
            }
        }

        override fun routes(cityId: CityId) = emptyList<com.denis.georgiatransit.shared.domain.model.TransitRoute>()
    }

    private class RetryDispatchingRepository(
        private val dispatchRetries: () -> Unit,
    ) : TransitRepository {
        var snapshotCalls = 0
            private set

        override fun cities(): List<TransitCity> = listOf(tbilisi)

        override suspend fun loadCityCapabilitySnapshot(): List<TransitCity> {
            snapshotCalls += 1
            dispatchRetries()
            return listOf(tbilisi)
        }

        override fun routes(cityId: CityId) = emptyList<com.denis.georgiatransit.shared.domain.model.TransitRoute>()
    }

    private companion object {
        val tbilisi = TransitCity(
            id = CityId("tbilisi"),
            name = "Tbilisi",
            countryCode = "GE",
            center = GeoPoint(41.7151, 44.8271),
            capabilities = CityCapabilities(
                stops = true,
                vehicles = true,
                arrivals = true,
                routeShapes = true,
                journeyPlanning = true,
            ),
        )
    }
}
