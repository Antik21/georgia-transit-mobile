package com.denis.georgiatransit.shared.presentation.map

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
class MapViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun selectedCityCenterDrivesViewportAndUpdatesWhenCityChanges() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val batumi = repository.cities().first { it.id.value == "batumi" }
        session.selectCity(tbilisi)

        MapViewModel(repository, session, RuntimeLocationSession(scope = this)).test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    viewport = MapViewport(center = tbilisi.center),
                ),
            )

            session.selectCity(batumi)
            this@runTest.runCurrent()

            expectState(
                ViewState(
                    cityName = batumi.name,
                    viewport = MapViewport(center = batumi.center),
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun freshSharedFixDrivesViewportAndUserLocationState() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        transitSession.selectCity(tbilisi)
        val locationSession = testLocationSessionWithFix()
        val fix = requireNotNull(locationSession.state.value.fix)

        MapViewModel(repository, transitSession, locationSession).test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    viewport = MapViewport(center = fix.point, contentCenter = tbilisi.center, zoom = 15.0),
                    location = locationSession.state.value,
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun permissionGrantAutomaticallyRequestsLocationOnlyWhenFixIsAbsent() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSession()
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()
            val command = awaitLocationCommand()

            assertTrue(command is LocationPlatformCommand.RequestLocation)
            assertEquals(command.id, locationSession.state.value.activeRequestId)
            assertTrue(locationSession.state.value.isLocating)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun resumePermissionGrantDoesNotRequestAgainWhenFreshFixExists() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSessionWithFix()
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertTrue(locationSession.state.value.fix != null)
            assertNull(locationSession.state.value.activeRequestId)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun explicitMyLocationRefreshReplacesFreshFixWithNewAcquisition() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSessionWithFix()
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.MyLocationClicked)
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
    fun deniedActionRequestsPermissionAgain() = runTest {
        assertMyLocationCommand(
            permission = LocationPermissionState.Denied,
            expectedType = LocationPlatformCommand.RequestPermission::class,
        )
    }

    @Test
    fun settingsRequiredActionOpensAppSettings() = runTest {
        assertMyLocationCommand(
            permission = LocationPermissionState.SettingsRequired,
            expectedType = LocationPlatformCommand.OpenAppSettings::class,
        )
    }

    @Test
    fun cancelledRequestAndLateTerminalEventsCannotCorruptMapState() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSession().also { it.updatePermission(PRECISE) }
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.MyLocationClicked)
            this@runTest.runCurrent()
            val request = awaitLocationCommand()
            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.Cancelled(request.id)))
            this@runTest.runCurrent()
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.FixReceived(request.id, validCandidate())),
            )
            viewModel.dispatchAction(
                Action.LocationEventReceived(LocationPlatformEvent.Failed(request.id, LocationFailure.TimedOut)),
            )
            this@runTest.runCurrent()

            val state = viewModel.container.stateFlow.value
            val city = requireNotNull(transitSession.selectedCity.value)
            assertEquals(city.center, state.viewport?.center)
            assertNull(state.location.fix)
            assertNull(state.location.failure)
            assertFalse(state.location.isLocating)
            assertNull(state.location.activeRequestId)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun grantedResumeAfterFailureWaitsForExplicitMyLocationRetry() = runTest {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSession().also { session ->
            session.updatePermission(PRECISE)
            val failedRequest = session.nextCommandId()
            assertTrue(session.beginLocationRequest(failedRequest))
            session.fail(failedRequest, LocationFailure.TimedOut)
        }
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertEquals(LocationFailure.TimedOut, locationSession.state.value.failure)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(locationSession.state.value.activeRequestId)
            assertFalse(locationSession.state.value.isLocating)
            expectNoItems()

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()

            assertEquals(LocationFailure.TimedOut, locationSession.state.value.failure)
            assertEquals(LocationFailure.TimedOut, viewModel.container.stateFlow.value.location.failure)
            assertNull(locationSession.state.value.activeRequestId)
            assertFalse(locationSession.state.value.isLocating)
            expectNoItems()

            viewModel.dispatchAction(Action.MyLocationClicked)
            this@runTest.runCurrent()
            val retry = awaitLocationCommand()

            assertTrue(retry is LocationPlatformCommand.RequestLocation)
            assertEquals(retry.id, locationSession.state.value.activeRequestId)
            assertTrue(locationSession.state.value.isLocating)
            assertNull(locationSession.state.value.failure)
            assertNull(viewModel.container.stateFlow.value.location.failure)
            cancelAndIgnoreRemainingItems()
        }
    }

    private suspend fun TestScope.assertMyLocationCommand(
        permission: LocationPermissionState,
        expectedType: kotlin.reflect.KClass<out LocationPlatformCommand>,
    ) {
        val repository = PreviewTransitRepository()
        val transitSession = RuntimeTransitSession().also {
            it.selectCity(repository.cities().first { city -> city.id.value == "tbilisi" })
        }
        val locationSession = testLocationSession().also { it.updatePermission(permission) }
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@assertMyLocationCommand.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            viewModel.dispatchAction(Action.MyLocationClicked)
            this@assertMyLocationCommand.runCurrent()

            assertEquals(expectedType, awaitLocationCommand()::class)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun expectedCityState(
        repository: PreviewTransitRepository,
        transitSession: RuntimeTransitSession,
        locationSession: RuntimeLocationSession,
    ): ViewState {
        val city = requireNotNull(transitSession.selectedCity.value)
        val fix = locationSession.state.value.fix
        return ViewState(
            cityName = city.name,
            viewport = MapViewport(
                center = fix?.point ?: city.center,
                contentCenter = city.center,
                zoom = if (fix == null) 12.0 else 15.0,
            ),
            selectedRouteNames = repository.routes(city.id)
                .filter { it.id in transitSession.selectedRouteIds.value }
                .map { it.shortName },
            location = locationSession.state.value,
        )
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

    private suspend fun OrbitTestContext<ViewState, SideEffect, MapViewModel>.awaitLocationCommand():
        LocationPlatformCommand {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> Unit
                is Item.SideEffectItem -> return (item.value as SideEffect.HandleLocation).command
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
