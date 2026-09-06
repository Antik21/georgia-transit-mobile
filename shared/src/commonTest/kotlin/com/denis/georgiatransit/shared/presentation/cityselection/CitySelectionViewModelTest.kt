package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CitySelectionViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueUpdatesSessionBeforeOpenMapEffectIsObserved() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val cities = repository.cities().map { city ->
            CityItemUiModel(
                id = city.id,
                name = city.name,
                isEnabled = city.capabilities.stops,
                isExperimental = city.capabilities.experimental,
            )
        }
        val viewModel = CitySelectionViewModel(repository, session, RuntimeLocationSession(scope = this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = cities))

            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()
            expectState(ViewState(cities = cities, selectedCityId = tbilisi.id))

            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.OpenMap)
            assertEquals(tbilisi, session.selectedCity.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun grantingPermissionStartsOneShotRequestAndAcceptedFixEntersSharedSession() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession()
        val locationSession = testLocationSession()
        val viewModel = CitySelectionViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = repository.cityItems()))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()
            val command = awaitLocationCommand()
            assertTrue(command is LocationPlatformCommand.RequestLocation)
            assertEquals(command.id, locationSession.state.value.activeRequestId)
            assertTrue(locationSession.state.value.isLocating)

            val candidate = validCandidate()
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.FixReceived(command.id, candidate)),
            )
            this@runTest.runCurrent()

            val fix = requireNotNull(locationSession.state.value.fix)
            assertEquals(candidate.latitude, fix.point.latitude)
            assertEquals(candidate.longitude, fix.point.longitude)
            assertEquals(LocationPrecision.Precise, fix.precision)
            assertFalse(locationSession.state.value.isLocating)
            assertEquals(fix, viewModel.container.stateFlow.value.location.fix)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun denialDoesNotBlockManualCityContinue() = runTest {
        assertPermissionDoesNotBlockContinue(LocationPermissionState.Denied)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settingsRequiredDoesNotBlockManualCityContinue() = runTest {
        assertPermissionDoesNotBlockContinue(LocationPermissionState.SettingsRequired)
    }

    private suspend fun TestScope.assertPermissionDoesNotBlockContinue(permission: LocationPermissionState) {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession()
        val locationSession = testLocationSession().also { it.updatePermission(permission) }
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val viewModel = CitySelectionViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@assertPermissionDoesNotBlockContinue.runCurrent()
            expectState(ViewState(cities = repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@assertPermissionDoesNotBlockContinue.runCurrent()
            expectState { copy(selectedCityId = tbilisi.id) }
            viewModel.dispatchAction(Action.ContinueClicked)
            this@assertPermissionDoesNotBlockContinue.runCurrent()

            assertEquals(NavigationEffect.OpenMap, awaitSideEffectIgnoringStates())
            assertEquals(tbilisi, transitSession.selectedCity.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settingsRequiredLocationActionOpensAppSettings() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSession().also {
            it.updatePermission(LocationPermissionState.SettingsRequired)
        }
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()

            assertTrue(awaitLocationCommand() is LocationPlatformCommand.OpenAppSettings)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resumePermissionEventDoesNotDuplicateRequestWhenFreshFixExists() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSessionWithFix()
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertTrue(locationSession.state.value.fix != null)
            assertNull(locationSession.state.value.activeRequestId)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun explicitRefreshClearsFreshFixAndStartsNewAcquisition() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSessionWithFix()
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()
            val command = awaitLocationCommand()

            assertTrue(command is LocationPlatformCommand.RequestLocation)
            assertNull(locationSession.state.value.fix)
            assertTrue(locationSession.state.value.isLocating)
            assertEquals(command.id, locationSession.state.value.activeRequestId)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun correlatedFailureIsVisibleAndEndsLocatingState() = runTest {
        val repository = PreviewTransitRepository()
        val locationSession = testLocationSession().also { it.updatePermission(PRECISE) }
        val viewModel = CitySelectionViewModel(repository, RuntimeTransitSession(), locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = repository.cityItems(), location = locationSession.state.value))

            viewModel.dispatchAction(Action.LocationClicked)
            this@runTest.runCurrent()
            val command = awaitLocationCommand()
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.Failed(command.id, LocationFailure.TimedOut)),
            )
            this@runTest.runCurrent()

            assertFalse(viewModel.container.stateFlow.value.location.isLocating)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(viewModel.container.stateFlow.value.location.activeRequestId)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun TestScope.testLocationSession() = RuntimeLocationSession(
        scope = this,
        nowMillis = { NOW_MILLIS + testScheduler.currentTime },
    )

    private fun TestScope.testLocationSessionWithFix() = testLocationSession().also { session ->
        session.updatePermission(PRECISE)
        val requestId = session.nextCommandId()
        assertTrue(session.beginLocationRequest(requestId))
        session.accept(requestId, validCandidate())
    }

    private fun PreviewTransitRepository.cityItems() = cities().map { city ->
        CityItemUiModel(
            id = city.id,
            name = city.name,
            isEnabled = city.capabilities.stops,
            isExperimental = city.capabilities.experimental,
        )
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, CitySelectionViewModel>.awaitLocationCommand():
        LocationPlatformCommand {
        val sideEffect = awaitSideEffectIgnoringStates()
        return (sideEffect as SideEffect.HandleLocation).command
    }

    private suspend fun OrbitTestContext<ViewState, SideEffect, CitySelectionViewModel>.awaitSideEffectIgnoringStates():
        SideEffect {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> Unit
                is Item.SideEffectItem -> return item.value
            }
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
        val PRECISE = LocationPermissionState.Granted(LocationPrecision.Precise)

        fun validCandidate() = LocationFixCandidate(
            latitude = 41.7151,
            longitude = 44.8271,
            accuracyMeters = 20.0,
            capturedAtEpochMillis = NOW_MILLIS,
        )
    }
}
