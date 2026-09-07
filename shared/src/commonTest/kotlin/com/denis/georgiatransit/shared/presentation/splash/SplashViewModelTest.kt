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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @Test
    fun retryMovesFromErrorThroughLoadingToReady() = runTest {
        val initialAttempt = CompletableDeferred<SnapshotOutcome>()
        val retryAttempt = CompletableDeferred<SnapshotOutcome>()
        val repository = SequencedRepository(
            outcomes = mutableListOf(
                SnapshotOutcome.WaitFor(initialAttempt),
                SnapshotOutcome.WaitFor(retryAttempt),
            ),
        )
        val viewModel = SplashViewModel(bootstrap(repository))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(1, repository.snapshotCalls)
            assertEquals(ViewState.Loading, viewModel.container.stateFlow.value)

            initialAttempt.complete(SnapshotOutcome.Fail)
            awaitTerminalState(
                ViewState.Error(BootstrapTransitSession.Failure.Unavailable),
            )

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(2, repository.snapshotCalls)
            assertEquals(ViewState.Loading, viewModel.container.stateFlow.value)

            retryAttempt.complete(SnapshotOutcome.Cities(listOf(tbilisi)))
            awaitTerminalState(ViewState.Ready(Destination.CitySelection))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun duplicateRetriesWhileBootstrapIsRunningStartOnlyOneRequest() = runTest {
        val gate = CompletableDeferred<SnapshotOutcome>()
        val repository = SequencedRepository(
            outcomes = mutableListOf(SnapshotOutcome.WaitFor(gate)),
        )
        val viewModel = SplashViewModel(bootstrap(repository))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(ViewState.Loading, viewModel.container.stateFlow.value)
            viewModel.dispatchAction(Action.RetryClicked)
            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()

            assertEquals(1, repository.snapshotCalls)
            gate.complete(SnapshotOutcome.Cities(listOf(tbilisi)))
            awaitTerminalState(ViewState.Ready(Destination.CitySelection))

            assertEquals(1, repository.snapshotCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    private suspend fun OrbitTestContext<ViewState, Nothing, SplashViewModel>.awaitTerminalState(
        expected: ViewState,
    ) {
        var state: ViewState
        do {
            state = awaitState()
        } while (state == ViewState.Loading)

        assertEquals(expected, state)
    }

    private fun bootstrap(repository: SequencedRepository): BootstrapTransitSession {
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

    private class SequencedRepository(
        private val outcomes: MutableList<SnapshotOutcome>,
    ) : TransitRepository {
        var snapshotCalls = 0
            private set

        override fun cities(): List<TransitCity> = listOf(tbilisi)

        override suspend fun loadCityCapabilitySnapshot(): List<TransitCity> {
            snapshotCalls += 1
            return snapshotFor(outcomes.removeFirst())
        }

        private suspend fun snapshotFor(outcome: SnapshotOutcome): List<TransitCity> = when (outcome) {
            SnapshotOutcome.Fail -> throw IllegalStateException("unavailable")
            is SnapshotOutcome.Cities -> outcome.cities
            is SnapshotOutcome.WaitFor -> snapshotFor(outcome.gate.await())
        }

        override fun routes(cityId: CityId) = emptyList<com.denis.georgiatransit.shared.domain.model.TransitRoute>()
    }

    private sealed interface SnapshotOutcome {
        data object Fail : SnapshotOutcome
        data class Cities(val cities: List<TransitCity>) : SnapshotOutcome
        data class WaitFor(val gate: CompletableDeferred<SnapshotOutcome>) : SnapshotOutcome
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
