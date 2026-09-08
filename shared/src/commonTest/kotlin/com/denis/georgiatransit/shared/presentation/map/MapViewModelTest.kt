package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    @Test
    fun selectedCityAttributionIsExposedToCommonMapStateWithStableAutomationSelectors() = runTest {
        val attribution = TransitAttribution(
            id = "transitous",
            label = LocalizedText(ru = "Источники", en = "Sources", ka = "წყაროები"),
            url = "https://transitous.org/sources/",
        )
        val selectedCity = city("tbilisi", defaultZoom = 12.5).copy(attribution = listOf(attribution))
        val session = RuntimeTransitSession().also { it.selectCity(selectedCity) }

        MapViewModel(MapRepository(), session, testLocationSession()).test(this) {
            runOnCreate()
            this@runTest.runCurrent()

            expectState(
                ViewState(
                    cityName = selectedCity.name,
                    renderState = renderState(
                        center = selectedCity.center,
                        zoom = selectedCity.defaultZoom,
                        revision = 1,
                    ),
                    contentState = MapContentState.Loading,
                    attribution = listOf(attribution),
                ),
            )
            assertEquals("map.attribution", AutomationId.MapAttribution)
            assertEquals("map.attribution.link.transitous", AutomationId.mapAttributionLink(attribution.id))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun noFixCameraUsesEachSelectedCityNonDefaultBffCenterAndZoom() = runTest {
        val tbilisi = city("tbilisi", defaultZoom = 13.5)
        val batumi = city("batumi", defaultZoom = 12.5)
        val session = RuntimeTransitSession().also { it.selectCity(tbilisi) }
        val locationSession = testLocationSession()
        val viewModel = MapViewModel(MapRepository(), session, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(center = tbilisi.center, zoom = 13.5, revision = 1),
                    contentState = MapContentState.Loading,
                ),
            )
            this@runTest.advanceTimeBy(351)
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(
                        center = tbilisi.center,
                        zoom = 13.5,
                        revision = 1,
                        stopSourceRevision = 2,
                    ),
                    contentState = MapContentState.Loading,
                ),
            )
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(
                        center = tbilisi.center,
                        zoom = tbilisi.defaultZoom,
                        revision = 1,
                        stopSourceRevision = 2,
                    ),
                    contentState = MapContentState.Unavailable,
                ),
            )
            // The local renderer foundation must not manufacture stops, vehicles, or shapes.
            // All layers stay empty until a reviewed BFF-backed map contract supplies them.
            val initialRenderState = requireNotNull(viewModel.container.stateFlow.value.renderState)
            assertTrue(initialRenderState.stops.isEmpty())
            assertTrue(initialRenderState.vehicles.isEmpty())
            assertTrue(initialRenderState.polylines.isEmpty())
            assertNull(initialRenderState.userLocation)

            session.selectCity(batumi)
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = batumi.name,
                    renderState = renderState(
                        center = batumi.center,
                        zoom = 12.5,
                        revision = 2,
                        stopSourceRevision = 3,
                    ),
                    contentState = MapContentState.Loading,
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun userFixUsesNewCameraRevisionAndKeepsZoomFifteenForANonDefaultBffZoom() = runTest {
        val batumi = city("batumi", defaultZoom = 12.5)
        val session = RuntimeTransitSession().also { it.selectCity(batumi) }
        val locationSession = testLocationSessionWithFix()
        val fix = requireNotNull(locationSession.state.value.fix)

        MapViewModel(MapRepository(), session, locationSession).test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = batumi.name,
                    renderState = renderState(center = fix.point, zoom = 15.0, revision = 2, userLocation = fix),
                    contentState = MapContentState.Loading,
                    location = locationSession.state.value,
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun selectedCityCenterDrivesCameraAndUpdatesWhenCityChanges() = runTest {
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
                    renderState = renderState(center = tbilisi.center, zoom = tbilisi.defaultZoom, revision = 1),
                    contentState = MapContentState.Loading,
                ),
            )
            this@runTest.advanceTimeBy(351)
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(
                        center = tbilisi.center,
                        zoom = tbilisi.defaultZoom,
                        revision = 1,
                        stopSourceRevision = 2,
                    ),
                    contentState = MapContentState.Loading,
                ),
            )
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(
                        center = tbilisi.center,
                        zoom = tbilisi.defaultZoom,
                        revision = 1,
                        stopSourceRevision = 2,
                    ),
                    contentState = MapContentState.Unavailable,
                ),
            )

            session.selectCity(batumi)
            this@runTest.runCurrent()

            expectState(
                ViewState(
                    cityName = batumi.name,
                    renderState = renderState(
                        center = batumi.center,
                        zoom = batumi.defaultZoom,
                        revision = 2,
                        stopSourceRevision = 3,
                    ),
                    contentState = MapContentState.Loading,
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun freshSharedFixDrivesCameraAndUserLocationState() = runTest {
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
                    renderState = renderState(center = fix.point, zoom = 15.0, revision = 2, userLocation = fix),
                    contentState = MapContentState.Loading,
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
            this@runTest.advanceTimeBy(351)
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2))
            expectState(
                expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2)
                    .copy(contentState = MapContentState.Unavailable),
            )

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
            this@runTest.advanceTimeBy(351)
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2))
            expectState(
                expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2)
                    .copy(contentState = MapContentState.Unavailable),
            )

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
            assertEquals(city.center, state.renderState?.camera?.center)
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
            this@runTest.advanceTimeBy(351)
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2))
            expectState(
                expectedCityState(repository, transitSession, locationSession, stopSourceRevision = 2)
                    .copy(contentState = MapContentState.Unavailable),
            )

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
        stopSourceRevision: Long = 1L,
    ): ViewState {
        val city = requireNotNull(transitSession.selectedCity.value)
        val fix = locationSession.state.value.fix
        return ViewState(
            cityName = city.name,
            renderState = renderState(
                center = fix?.point ?: city.center,
                zoom = if (fix == null) city.defaultZoom else 15.0,
                revision = if (fix == null) 1 else 2,
                userLocation = fix,
                stopSourceRevision = stopSourceRevision,
            ),
            contentState = MapContentState.Loading,
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

    @Test
    fun cameraRevisionChangesOnlyForCityAndAcceptedLocationUpdates() = runTest {
        val repository = PreviewTransitRepository()
        val city = repository.cities().first { it.id.value == "tbilisi" }
        val transitSession = RuntimeTransitSession().also { it.selectCity(city) }
        val locationSession = testLocationSession()
        val viewModel = MapViewModel(repository, transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(expectedCityState(repository, transitSession, locationSession))

            val cityCamera = requireNotNull(viewModel.container.stateFlow.value.renderState).camera
            assertEquals(city.center, cityCamera.center)
            assertEquals(city.defaultZoom, cityCamera.zoom)
            assertEquals(1, cityCamera.revision)

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.PermissionChanged(PRECISE)))
            this@runTest.runCurrent()
            val firstRequest = awaitLocationCommand()
            val locatingState = requireNotNull(viewModel.container.stateFlow.value.renderState)
            assertEquals(cityCamera, locatingState.camera)
            assertNull(locatingState.userLocation)

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.FixReceived(firstRequest.id, validCandidate())))
            this@runTest.runCurrent()
            val acceptedFix = requireNotNull(locationSession.state.value.fix)
            val locationCamera = requireNotNull(viewModel.container.stateFlow.value.renderState).camera
            assertEquals(2, locationCamera.revision)
            assertEquals(acceptedFix.point, locationCamera.center)
            assertEquals(15.0, locationCamera.zoom)

            transitSession.selectRoutes(setOf(repository.routes(city.id).first().id))
            this@runTest.runCurrent()
            assertEquals(locationCamera, requireNotNull(viewModel.container.stateFlow.value.renderState).camera)

            viewModel.dispatchAction(Action.MyLocationClicked)
            this@runTest.runCurrent()
            val refreshRequest = awaitLocationCommand()
            val refreshingState = requireNotNull(viewModel.container.stateFlow.value.renderState)
            assertEquals(locationCamera, refreshingState.camera)
            assertNull(refreshingState.userLocation)

            viewModel.dispatchAction(Action.LocationEventReceived(LocationPlatformEvent.FixReceived(refreshRequest.id, validCandidate())))
            this@runTest.runCurrent()
            val refreshedCamera = requireNotNull(viewModel.container.stateFlow.value.renderState).camera
            assertEquals(3, refreshedCamera.revision)
            assertEquals(acceptedFix.point, refreshedCamera.center)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityChangeKeepsAcceptedLocationMarkerButUsesTheNewCityCamera() = runTest {
        val tbilisi = city("tbilisi", defaultZoom = 13.25).copy(center = GeoPoint(41.7151, 44.8271))
        val batumi = city("batumi", defaultZoom = 11.75).copy(center = GeoPoint(41.6168, 41.6367))
        val transitSession = RuntimeTransitSession().also { it.selectCity(tbilisi) }
        val locationSession = testLocationSessionWithFix()
        val acceptedFix = requireNotNull(locationSession.state.value.fix)
        val viewModel = MapViewModel(MapRepository(), transitSession, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    renderState = renderState(
                        center = acceptedFix.point,
                        zoom = 15.0,
                        revision = 2,
                        userLocation = acceptedFix,
                    ),
                    contentState = MapContentState.Loading,
                    location = locationSession.state.value,
                ),
            )

            val cameraBeforeCityChange = requireNotNull(viewModel.container.stateFlow.value.renderState).camera
            transitSession.selectCity(batumi)
            this@runTest.runCurrent()

            val switchedState = requireNotNull(viewModel.container.stateFlow.value.renderState)
            assertEquals(acceptedFix, switchedState.userLocation)
            assertEquals(batumi.center, switchedState.camera.center)
            assertEquals(batumi.defaultZoom, switchedState.camera.zoom)
            assertEquals(cameraBeforeCityChange.revision + 1, switchedState.camera.revision)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun mapRenderStateSnapshotsCallerOwnedLayersAndDefaultsToNoTransitData() {
        val camera = MapCameraCommand(center = GeoPoint(41.7151, 44.8271), zoom = 13.0, revision = 1)
        val callerStops = mutableListOf(MapStopMarker(StopId("stop-1"), GeoPoint(41.7151, 44.8271)))
        val state = MapRenderState.from(camera = camera, stops = callerStops)

        callerStops.clear()

        assertEquals(1, state.stops.size)
        assertTrue(state.vehicles.isEmpty())
        assertTrue(state.polylines.isEmpty())
        assertNull(state.userLocation)

        val callerPoints = mutableListOf(GeoPoint(41.0, 44.0), GeoPoint(41.1, 44.1))
        val polyline = MapPolyline(
            routeId = RouteId("route-1"),
            directionId = null,
            points = callerPoints,
            routeColorArgb = 0xFF0000,
            freshness = TransitFreshness.Network,
        )
        callerPoints.clear()

        assertEquals(listOf(GeoPoint(41.0, 44.0), GeoPoint(41.1, 44.1)), polyline.points)
    }

    @Test
    fun noSelectedCityCreatesNoMapRenderStateOrFabricatedTransitLayers() = runTest {
        val viewModel = MapViewModel(MapRepository(), RuntimeTransitSession(), testLocationSession())

        assertEquals(ViewState(), viewModel.container.stateFlow.value)
        assertNull(viewModel.container.stateFlow.value.renderState)
    }

    private fun renderState(
        center: GeoPoint,
        zoom: Double,
        revision: Long,
        userLocation: com.denis.georgiatransit.shared.presentation.location.UserLocationFix? = null,
        stopSourceRevision: Long = 1L,
    ) = MapRenderState(
        camera = MapCameraCommand(center = center, zoom = zoom, revision = revision),
        userLocation = userLocation,
        stopSourceRevision = stopSourceRevision,
    )

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

        fun city(id: String, defaultZoom: Double) = TransitCity(
            id = CityId(id),
            name = id.replaceFirstChar(Char::uppercase),
            center = GeoPoint(latitude = 41.0, longitude = 44.0),
            capabilities = CityCapabilities(
                stops = true,
                vehicles = true,
                arrivals = true,
                routeShapes = true,
                journeyPlanning = true,
            ),
            defaultZoom = defaultZoom,
        )
    }

    private class MapRepository : TransitRepository {
        override fun cities(): List<TransitCity> = emptyList()

        override fun routes(cityId: CityId): List<TransitRoute> = emptyList()
    }
}
