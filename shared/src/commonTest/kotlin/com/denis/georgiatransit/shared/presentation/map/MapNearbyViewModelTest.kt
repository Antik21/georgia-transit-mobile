package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapNearbyViewModelTest {
    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val MapViewModel.state: ViewState
        get() = container.stateFlow.value

    @Test
    fun configuredBffStyleDoesNotClaimThatTheLocalPreviewIsActive() = runTest {
        val viewModel = MapViewModel(
            repository = RecordingRepository(),
            session = RuntimeTransitSession(),
            locationSession = RuntimeLocationSession(this),
            bffStyleUrl = "http://10.0.2.2:8080/v1/map/style.json",
        )

        assertEquals(MapBaseLayerState.BffStyle, viewModel.state.baseLayerState)
    }

    @Test
    fun initialStateIsLoadingAndViewportCallbackIsNormalizedDebouncedAndDeduplicated() = runTest {
        val city = city("tbilisi")
        val repository = RecordingRepository().apply { results += { data(stop("a")) } }
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(MapContentState.Loading, viewModel.state.contentState)
            assertEquals(MapBaseLayerState.LocalPreview, viewModel.state.baseLayerState)

            val raw = MapViewport(GeoPoint(41.7000049, 44.8000051), radiusMeters = 1_001, zoom = 13.126)
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.ViewportSettled(raw)))
            this@runTest.runCurrent()
            this@runTest.advanceTimeBy(349)
            this@runTest.runCurrent()
            assertTrue(repository.calls.isEmpty())

            this@runTest.advanceTimeBy(2)
            this@runTest.runCurrent()
            assertEquals(
                NearbyCall(city.id, GeoPoint(41.7, 44.80001), 1_025, 100, TransitLocale.English),
                repository.calls.single(),
            )
            assertEquals(MapContentState.Ready, viewModel.state.contentState)
            assertEquals(MapBaseLayerState.LocalPreview, viewModel.state.baseLayerState)

            viewModel.dispatchAction(
                Action.MapEventReceived(
                    MapPlatformEvent.ViewportSettled(
                        MapViewport(GeoPoint(41.7000001, 44.8000099), radiusMeters = 1_024, zoom = 13.129),
                    ),
                ),
            )
            this@runTest.advanceTimeBy(1_000)
            this@runTest.runCurrent()
            assertEquals(1, repository.calls.size, "Equivalent normalized viewport must not reload")

            settle(viewModel, GeoPoint(Double.NaN, 44.8))
            viewModel.dispatchAction(
                Action.MapEventReceived(
                    MapPlatformEvent.ViewportSettled(MapViewport(GeoPoint(41.7, 44.8), radiusMeters = 0, zoom = 13.0)),
                ),
            )
            this@runTest.advanceTimeBy(1_000)
            this@runTest.runCurrent()
            assertEquals(1, repository.calls.size, "Invalid native viewport data must fail closed")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun panningAwayKeepsTheIndependentOneKilometreUserStopPageVisible() = runTest {
        val now = 2_000_000L
        val userPoint = GeoPoint(41.7, 44.8)
        val locationSession = RuntimeLocationSession(backgroundScope) { now }
        locationSession.updatePermission(LocationPermissionState.Granted(LocationPrecision.Precise))
        val locationRequest = locationSession.nextCommandId()
        assertTrue(locationSession.beginLocationRequest(locationRequest))
        locationSession.accept(
            locationRequest,
            LocationFixCandidate(
                latitude = userPoint.latitude,
                longitude = userPoint.longitude,
                accuracyMeters = 5.0,
                capturedAtEpochMillis = now,
            ),
        )
        val city = city("tbilisi")
        val repository = RecordingRepository().apply {
            results += { data(stop("near-user", position = GeoPoint(41.705, 44.8))) }
            results += { data(stop("initial-viewport", position = GeoPoint(41.72, 44.8))) }
            results += { data(stop("far-viewport", position = GeoPoint(42.7, 45.8))) }
        }
        val viewModel = MapViewModel(
            repository = repository,
            session = RuntimeTransitSession().also { it.selectCity(city) },
            locationSession = locationSession,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(
                NearbyCall(city.id, userPoint, 1_000, 100, TransitLocale.English),
                repository.calls.first(),
            )
            this@runTest.advanceNearby()

            settle(viewModel, GeoPoint(42.7, 45.8))
            this@runTest.advanceNearby()

            assertEquals(
                listOf(StopId("near-user")),
                requireNotNull(viewModel.state.renderState).stops.map { it.id },
            )
            assertTrue(requireNotNull(viewModel.state.renderState).stopClusters.isEmpty())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun typedStopCallbackSelectsOnlyALoadedVisibleMarker() = runTest {
        val repository = RecordingRepository().apply { results += { data(stop("visible")) } }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city("tbilisi", defaultZoom = ALL_STOPS_MIN_ZOOM)) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            val sourceRevision = requireNotNull(viewModel.state.renderState).stopSourceRevision
            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId("unknown"), sourceRevision)),
            )
            this@runTest.runCurrent()
            assertNull(viewModel.state.selectedStop)

            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId("visible"), sourceRevision)),
            )
            this@runTest.runCurrent()
            assertEquals(StopId("visible"), viewModel.state.selectedStop?.id)
            assertTrue(requireNotNull(viewModel.state.renderState).stops.single().isSelected)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun committedRouteReplacementReprojectsMapMarkersWithoutClusteringOrReloadingStops() = runTest {
        val city = city("tbilisi")
        val routeA = route(city.id, "route-a", "A", 0xFF0057B8)
        val routeB = route(city.id, "route-b", "B", 0xFF457B9D)
        val routeC = route(city.id, "route-c", "C", 0xFF2A9D8F)
        val repository = RecordingRepository().apply {
            routesSnapshot = listOf(routeA, routeB, routeC)
            results += {
                data(
                    stop("ordinary-1"),
                    stop("ordinary-2"),
                    stop("only-a", routeIds = listOf(routeA.id)),
                    stop("only-b", routeIds = listOf(routeB.id)),
                    stop("a-and-b", routeIds = listOf(routeA.id, routeB.id)),
                )
            }
        }
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            assertTrue(it.selectRoutes(city.id, linkedSetOf(routeA.id, routeB.id)))
        }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            val initial = viewModel.state
            assertEquals(
                listOf("ordinary-1", "ordinary-2", "only-a", "only-b", "a-and-b"),
                initial.nearbyStops.map { it.id.value },
                "Route highlighting must not filter the nearby accessibility list.",
            )
            val highlights = initial.nearbyStops.associateBy { it.id.value }
            assertEquals(StopRouteHighlightStyle.None, highlights.getValue("ordinary-1").routeHighlight.style)
            assertEquals(StopRouteHighlightStyle.SingleRoute, highlights.getValue("only-a").routeHighlight.style)
            assertEquals(listOf(routeA.id), highlights.getValue("only-a").routeHighlight.matchingRouteIds)
            assertEquals(StopRouteHighlightStyle.SingleRoute, highlights.getValue("only-b").routeHighlight.style)
            assertEquals(StopRouteHighlightStyle.MultipleRoutes, highlights.getValue("a-and-b").routeHighlight.style)
            assertEquals(listOf(routeA.id, routeB.id), highlights.getValue("a-and-b").routeHighlight.matchingRouteIds)
            val initialRender = requireNotNull(initial.renderState)
            assertTrue(
                setOf(StopId("only-a"), StopId("only-b"), StopId("a-and-b")).all { id ->
                    initialRender.stops.any { it.id == id }
                },
                "Selected-route stops must remain individually tappable at low zoom.",
            )
            assertEquals(
                setOf("only-a", "only-b", "a-and-b"),
                initialRender.stops.mapTo(mutableSetOf()) { it.id.value },
            )
            assertTrue(initialRender.stopClusters.isEmpty())
            val initialRevision = initialRender.stopSourceRevision
            val nearbyCallsBeforeReplacement = repository.calls.size

            viewModel.selectCurrentStop(StopId("only-b"))
            this@runTest.runCurrent()
            assertTrue(session.selectRoutes(city.id, setOf(routeC.id)))
            this@runTest.runCurrent()

            val replacement = viewModel.state
            assertEquals(nearbyCallsBeforeReplacement, repository.calls.size, "Selection reprojection must not reload nearby stops.")
            assertEquals(StopId("only-b"), replacement.selectedStop?.id)
            assertTrue(replacement.nearbyStops.all { it.routeHighlight.style == StopRouteHighlightStyle.None })
            val replacementRender = requireNotNull(replacement.renderState)
            assertTrue(replacementRender.stopSourceRevision > initialRevision)
            assertTrue(
                replacementRender.stops.any { it.id == StopId("only-b") && it.isSelected },
                "The open selected stop must remain visible after its route highlight is removed.",
            )
            assertEquals(listOf(StopId("only-b")), replacementRender.stops.map { it.id })
            assertTrue(replacementRender.stopClusters.isEmpty())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun selectedRouteLoadsEveryDirectionStopAndRendersItAtLowZoom() = runTest {
        val city = city("batumi")
        val direction = TransitDirection(
            id = DirectionId("direction-10"),
            name = LocalizedText.fromLegacy("Outbound"),
            headsign = LocalizedText.fromLegacy("Terminus"),
        )
        val route = route(city.id, "route-10", "10", 0xFF0057B8).copy(directions = listOf(direction))
        val selectedRouteStop = stop(
            id = "route-stop",
            routeIds = listOf(route.id),
            position = GeoPoint(41.75, 44.8),
        )
        val repository = RecordingRepository().apply {
            routesSnapshot = listOf(route)
            results += { data(stop("ordinary-nearby")) }
            directionStopResults[route.id to direction.id] = { data(selectedRouteStop) }
        }
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            assertTrue(it.selectRoutes(city.id, setOf(route.id)))
        }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            this@runTest.advanceNearby()

            val renderState = requireNotNull(viewModel.state.renderState)
            assertEquals(listOf(StopId("route-stop")), renderState.stops.map(MapStopMarker::id))
            assertTrue(renderState.stopClusters.isEmpty())
            assertEquals(listOf(route.id to direction.id), repository.directionStopCalls)
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun staleStopRevisionCannotSelectAReusedIdAfterCityOrCapabilityChange() = runTest {
        val firstCity = city("first", defaultZoom = ALL_STOPS_MIN_ZOOM)
        val secondCity = city("second", defaultZoom = ALL_STOPS_MIN_ZOOM)
        val repository = RecordingRepository().apply {
            results += { data(stop("shared")) }
            results += { data(stop("shared")) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(firstCity) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            val firstRevision = requireNotNull(viewModel.state.renderState).stopSourceRevision

            session.selectCity(secondCity)
            this@runTest.runCurrent()
            this@runTest.advanceNearby()
            val secondRevision = requireNotNull(viewModel.state.renderState).stopSourceRevision
            assertNotEquals(firstRevision, secondRevision)

            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId("shared"), firstRevision)),
            )
            this@runTest.runCurrent()
            assertNull(viewModel.state.selectedStop)

            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId("shared"), secondRevision)),
            )
            this@runTest.runCurrent()
            assertEquals(StopId("shared"), viewModel.state.selectedStop?.id)
            val selectedRevision = requireNotNull(viewModel.state.renderState).stopSourceRevision

            session.selectCity(secondCity.copy(capabilities = secondCity.capabilities.copy(stops = false)))
            this@runTest.runCurrent()
            assertNull(viewModel.state.selectedStop)
            assertNull(viewModel.state.stopArrivalsSheet)
            assertTrue(viewModel.state.nearbyStops.isEmpty())
            assertTrue(requireNotNull(viewModel.state.renderState).stops.isEmpty())

            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId("shared"), selectedRevision)),
            )
            this@runTest.runCurrent()
            assertNull(viewModel.state.selectedStop)
            assertNull(viewModel.state.stopArrivalsSheet)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun currentVehicleTapIsNoOpAndStaleOrUnknownTapsCannotChangeStateOrCamera() = runTest {
        val route = TransitRoute(
            id = RouteId("route"),
            cityId = CityId("vehicle-city"),
            shortName = "10",
            name = "Route 10",
            colorArgb = 0xFF0057B8,
        )
        val repository = VehicleTapRepository(route)
        val session = RuntimeTransitSession().also {
            it.selectCity(repository.city)
            it.selectRoutes(repository.city.id, setOf(route.id))
        }
        val clock = object : VehicleRealtimeClock {
            override fun wallNow(): Instant = VehicleTapRepository.Now
            override fun monotonicNowMillis(): Long = 0L
        }
        val ticker = object : VehicleRealtimeTickerPolicy {
            override val pollIntervalMillis: Long = 60_000L
            override val frameIntervalMillis: Long = 60_000L
        }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this), clock, ticker)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(
                Action.MapEventReceived(MapPlatformEvent.ViewportSettled(MapViewport(repository.city.center, 2_000, 14.0))),
            )
            this@runTest.runCurrent()
            val cameraBeforeRealtime = requireNotNull(viewModel.state.renderState).camera
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            val before = viewModel.state
            val renderState = requireNotNull(before.renderState)
            val vehicle = renderState.vehicles.single()
            assertEquals(cameraBeforeRealtime, renderState.camera)

            listOf(
                MapPlatformEvent.VehicleTapped(vehicle.id, renderState.vehicleSourceRevision),
                MapPlatformEvent.VehicleTapped(VehicleId("unknown"), renderState.vehicleSourceRevision),
                MapPlatformEvent.VehicleTapped(vehicle.id, renderState.vehicleSourceRevision - 1),
            ).forEach { event ->
                viewModel.dispatchAction(Action.MapEventReceived(event))
                this@runTest.runCurrent()
                assertEquals(before, viewModel.state)
            }
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun freshAndEmptyResponsesReplaceMarkersAndMapState() = runTest {
        val city = city("tbilisi")
        val repository = RecordingRepository().apply {
            results += { data(stop("a"), stop("b")) }
            results += { TransitLoadResult.Empty(TransitFreshness.Network) }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            assertEquals(MapContentState.Ready, viewModel.state.contentState)
            assertEquals(listOf("a", "b"), viewModel.state.nearbyStops.map { it.id.value })

            settle(viewModel, GeoPoint(41.71, 44.81))
            val cameraBeforeRefresh = requireNotNull(viewModel.state.renderState).camera
            this@runTest.advanceNearby()
            assertEquals(MapContentState.Empty, viewModel.state.contentState)
            assertTrue(viewModel.state.nearbyStops.isEmpty())
            assertTrue(requireNotNull(viewModel.state.renderState).stops.isEmpty())
            assertEquals(cameraBeforeRefresh, requireNotNull(viewModel.state.renderState).camera)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun retryableFailureRetainsMarkersAndRetryActionReplacesThem() = runTest {
        val city = city("tbilisi")
        val repository = RecordingRepository().apply {
            results += { data(stop("cached")) }
            results += { TransitLoadResult.Failure(TransitFailure.Transport("offline")) }
            results += { data(stop("recovered")) }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertIs<MapContentState.RetryableError>(viewModel.state.contentState)
            assertEquals(listOf("cached"), viewModel.state.nearbyStops.map { it.id.value })
            viewModel.dispatchAction(Action.RetryNearby)
            this@runTest.advanceNearby()
            assertEquals(MapContentState.Ready, viewModel.state.contentState)
            assertEquals(listOf("recovered"), viewModel.state.nearbyStops.map { it.id.value })
            assertEquals(3, repository.calls.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun staleOfflineResponseRetainsMarkersAndExposesOfflineState() = runTest {
        val staleFailure = TransitFailure.UpstreamUnavailable("offline", retryAfterSeconds = 3, requestId = "req")
        val repository = RecordingRepository().apply {
            results += { data(stop("cached")) }
            results += {
                TransitLoadResult.Data(
                    listOf(stop("cached")),
                    TransitFreshness.StaleOffline,
                    revalidationFailure = staleFailure,
                )
            }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city("tbilisi")) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertEquals(MapContentState.Offline(isStale = true), viewModel.state.contentState)
            assertEquals(listOf("cached"), viewModel.state.nearbyStops.map { it.id.value })
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cancellationAndGenerationGuardRejectLateNonCooperativeResultAfterCityChange() = runTest {
        val oldCity = city("old")
        val newCity = city("new").copy(center = GeoPoint(42.0, 43.0))
        val repository = RecordingRepository().apply {
            results += {
                try {
                    delay(1_000)
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { delay(1_000) }
                }
                data(stop("late-old"))
            }
            results += { data(stop("new-city")) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(oldCity) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            assertEquals(1, repository.calls.size)
            session.selectCity(newCity)
            this@runTest.runCurrent()
            assertEquals(MapContentState.Loading, viewModel.state.contentState)
            assertTrue(viewModel.state.nearbyStops.isEmpty())

            this@runTest.advanceNearby()
            assertEquals(listOf("new-city"), viewModel.state.nearbyStops.map { it.id.value })
            this@runTest.advanceTimeBy(1_000)
            this@runTest.runCurrent()
            assertEquals("New", viewModel.state.cityName)
            assertEquals(listOf("new-city"), viewModel.state.nearbyStops.map { it.id.value })
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun terminalCapabilityFailureClearsIdentityAndBlocksPansUntilCapabilityFlips() = runTest {
        val enabled = city("tbilisi")
        val disabled = enabled.copy(capabilities = enabled.capabilities.copy(stops = false))
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += {
                TransitLoadResult.Failure(
                    TransitFailure.CapabilityUnavailable("disabled", requestId = "req"),
                )
            }
            results += { data(stop("after-flip")) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(enabled) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            viewModel.selectCurrentStop(StopId("old"))
            this@runTest.runCurrent()
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertEquals(MapContentState.Unavailable, viewModel.state.contentState)
            assertNull(viewModel.state.selectedStop)
            assertTrue(viewModel.state.nearbyStops.isEmpty())
            settle(viewModel, GeoPoint(41.72, 44.82))
            this@runTest.advanceTimeBy(1_000)
            assertEquals(2, repository.calls.size, "A kill switch must remain fail-closed while capability is unchanged")

            session.selectCity(disabled)
            this@runTest.runCurrent()
            session.selectCity(enabled)
            this@runTest.runCurrent()
            this@runTest.advanceNearby()
            assertEquals(listOf("after-flip"), viewModel.state.nearbyStops.map { it.id.value })
            assertEquals(3, repository.calls.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun localeChangeRefreshesExactQueryAndUsesLocalizedStopIdentity() = runTest {
        val repository = RecordingRepository().apply {
            results += { data(stop("central")) }
            results += { data(stop("central")) }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city("tbilisi")) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            assertEquals("Central", viewModel.state.nearbyStops.single().name)
            settle(viewModel, GeoPoint(41.705, 44.805))
            this@runTest.runCurrent()
            val cameraBeforeLocaleRefresh = requireNotNull(viewModel.state.renderState).camera
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.Russian))
            this@runTest.runCurrent()
            assertEquals("Центральная", viewModel.state.nearbyStops.single().name)
            this@runTest.advanceNearby()
            assertEquals(listOf(TransitLocale.English, TransitLocale.Russian), repository.calls.map { it.locale })
            assertEquals(cameraBeforeLocaleRefresh, requireNotNull(viewModel.state.renderState).camera)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityNotFoundClearsOldStateThenRefreshesCatalogAndRetriesEntityExactlyOnce() = runTest {
        val city = city("tbilisi")
        val retryGate = CompletableDeferred<Unit>()
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += { TransitLoadResult.Failure(TransitFailure.CityNotFound("missing", "first")) }
            results += {
                retryGate.await()
                data(stop("new"))
            }
            revalidation = { TransitLoadResult.Data(listOf(city), TransitFreshness.Network) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            this@runTest.selectOldStop(viewModel)
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertEquals(3, repository.calls.size, "Initial load, failed query, and one bounded retry")
            assertEquals(1, repository.revalidationCalls)
            assertRecoveryClearedOldStop(viewModel, MapContentState.Loading)

            retryGate.complete(Unit)
            this@runTest.runCurrent()
            assertEquals(MapContentState.Ready, viewModel.state.contentState)
            assertEquals(listOf("new"), viewModel.state.nearbyStops.map { it.id.value })
            assertEquals(3, repository.calls.size, "Successful recovery must not start another retry")
            assertEquals(1, repository.revalidationCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityNotFoundWithStaleCatalogClearsOldStateAndDoesNotRetryEntity() = runTest {
        val city = city("tbilisi")
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += { TransitLoadResult.Failure(TransitFailure.CityNotFound("missing", "req")) }
            revalidation = {
                TransitLoadResult.Data(
                    listOf(city),
                    TransitFreshness.StaleOffline,
                    revalidationFailure = TransitFailure.Transport("offline"),
                )
            }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            this@runTest.selectOldStop(viewModel)
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertRecoveryClearedOldStop(viewModel, MapContentState.RetryableError)
            assertIs<MapContentState.RetryableError>(viewModel.state.contentState)
            assertEquals(2, repository.calls.size)
            assertEquals(1, repository.revalidationCalls)
            this@runTest.advanceTimeBy(5_000)
            this@runTest.runCurrent()
            assertEquals(2, repository.calls.size, "Stale catalog recovery must not loop")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityNotFoundWithDisabledRefreshedCapabilityClearsOldStateAndFailsClosed() = runTest {
        val city = city("tbilisi")
        val disabled = city.copy(capabilities = city.capabilities.copy(stops = false))
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += { TransitLoadResult.Failure(TransitFailure.CityNotFound("missing", "req")) }
            revalidation = { TransitLoadResult.Data(listOf(disabled), TransitFreshness.Network) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            this@runTest.selectOldStop(viewModel)
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertRecoveryClearedOldStop(viewModel, MapContentState.Unavailable)
            assertEquals(MapContentState.Unavailable, viewModel.state.contentState)
            assertEquals(false, session.selectedCity.value?.capabilities?.stops)
            assertEquals(2, repository.calls.size)
            assertEquals(1, repository.revalidationCalls)
            settle(viewModel, GeoPoint(41.72, 44.82))
            this@runTest.advanceTimeBy(5_000)
            assertEquals(2, repository.calls.size, "Disabled capability must remain fail-closed")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityNotFoundClearsSelectedCityWhenForcedCatalogNoLongerContainsIt() = runTest {
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += { TransitLoadResult.Failure(TransitFailure.CityNotFound("missing", "req")) }
            revalidation = { TransitLoadResult.Empty(TransitFreshness.Network) }
        }
        val session = RuntimeTransitSession().also { it.selectCity(city("tbilisi")) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(this))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            this@runTest.selectOldStop(viewModel)
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()
            assertNull(session.selectedCity.value)
            assertNull(viewModel.state.renderState)
            assertEquals(MapContentState.Unavailable, viewModel.state.contentState)
            assertNull(viewModel.state.selectedStop)
            assertTrue(viewModel.state.nearbyStops.isEmpty())
            assertEquals(2, repository.calls.size)
            assertEquals(1, repository.revalidationCalls)
            this@runTest.advanceTimeBy(5_000)
            assertEquals(2, repository.calls.size, "Missing city recovery must not loop")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun providerIdConflictClearsOldStateAndRetriesEntityExactlyOnce() = runTest {
        val retryGate = CompletableDeferred<Unit>()
        val repository = RecordingRepository().apply {
            results += { data(stop("old")) }
            results += { TransitLoadResult.Failure(TransitFailure.ProviderIdChanged("changed", "first")) }
            results += {
                retryGate.await()
                data(stop("new"))
            }
        }
        val viewModel = MapViewModel(
            repository,
            RuntimeTransitSession().also { it.selectCity(city("tbilisi")) },
            RuntimeLocationSession(this),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.advanceNearby()
            this@runTest.selectOldStop(viewModel)
            settle(viewModel, GeoPoint(41.71, 44.81))
            this@runTest.advanceNearby()

            assertEquals(3, repository.calls.size, "Initial load, failed query, and one bounded retry")
            assertEquals(0, repository.revalidationCalls)
            assertRecoveryClearedOldStop(viewModel, MapContentState.Loading)

            retryGate.complete(Unit)
            this@runTest.runCurrent()
            assertEquals(MapContentState.Ready, viewModel.state.contentState)
            assertEquals(listOf("new"), viewModel.state.nearbyStops.map { it.id.value })
            assertEquals(3, repository.calls.size, "Successful conflict recovery must not loop")
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.selectOldStop(viewModel: MapViewModel) {
        viewModel.selectCurrentStop(StopId("old"))
        runCurrent()
        assertEquals(StopId("old"), viewModel.state.selectedStop?.id)
        assertEquals(listOf("old"), viewModel.state.nearbyStops.map { it.id.value })
    }

    private fun assertRecoveryClearedOldStop(viewModel: MapViewModel, expectedContentState: MapContentState) {
        assertEquals(expectedContentState, viewModel.state.contentState)
        assertNull(viewModel.state.selectedStop)
        assertTrue(viewModel.state.nearbyStops.isEmpty(), "Accessibility activation list must clear old identity")
        val renderState = requireNotNull(viewModel.state.renderState)
        assertTrue(renderState.stops.isEmpty(), "Old individual markers must clear before recovery")
        assertTrue(renderState.stopClusters.isEmpty(), "Old clustered markers must clear before recovery")
    }

    private fun kotlinx.coroutines.test.TestScope.advanceNearby() {
        runCurrent()
        advanceTimeBy(351)
        runCurrent()
    }

    private fun MapViewModel.selectCurrentStop(stopId: StopId) {
        dispatchAction(Action.StopSelected(stopId, requireNotNull(state.renderState).stopSourceRevision))
    }

    private fun settle(viewModel: MapViewModel, center: GeoPoint) {
        viewModel.dispatchAction(
            Action.MapEventReceived(
                MapPlatformEvent.ViewportSettled(MapViewport(center, radiusMeters = 2_000, zoom = 14.0)),
            ),
        )
    }

    private fun data(vararg stops: TransitStop) =
        TransitLoadResult.Data(stops.toList(), TransitFreshness.Network)

    private fun stop(
        id: String,
        routeIds: List<RouteId> = emptyList(),
        position: GeoPoint = GeoPoint(41.7, 44.8),
    ) = TransitStop(
        id = StopId(id),
        providerId = ProviderId("provider-$id"),
        code = id,
        name = LocalizedText(ru = "Центральная", en = "Central", ka = "ცენტრალური"),
        position = position,
        routeIds = routeIds,
        mode = TransitMode.Bus,
    )

    private fun route(cityId: CityId, id: String, shortName: String, color: Long) = TransitRoute(
        id = RouteId(id),
        cityId = cityId,
        shortName = shortName,
        name = "Route $shortName",
        colorArgb = color,
    )

    private fun city(id: String, defaultZoom: Double = 13.0) = TransitCity(
        id = CityId(id),
        name = id.replaceFirstChar(Char::uppercase),
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(
            stops = true,
            vehicles = true,
            arrivals = true,
            routeShapes = true,
            journeyPlanning = true,
        ),
        defaultZoom = defaultZoom,
    )

    private data class NearbyCall(
        val cityId: CityId,
        val center: GeoPoint,
        val radiusMeters: Int,
        val limit: Int,
        val locale: TransitLocale,
    )

    private class RecordingRepository : TransitRepository {
        val calls = mutableListOf<NearbyCall>()
        val results = ArrayDeque<suspend () -> TransitLoadResult<List<TransitStop>>>()
        val directionStopCalls = mutableListOf<Pair<RouteId, DirectionId>>()
        val directionStopResults = mutableMapOf<Pair<RouteId, DirectionId>, suspend () -> TransitLoadResult<List<TransitStop>>>()
        var routesSnapshot: List<TransitRoute> = emptyList()
        var revalidationCalls = 0
        var revalidation: suspend () -> TransitLoadResult<List<TransitCity>> = {
            TransitLoadResult.Empty(TransitFreshness.Network)
        }

        override fun cities(): List<TransitCity> = emptyList()

        override fun routes(cityId: CityId): List<TransitRoute> = routesSnapshot.filter { it.cityId == cityId }

        override suspend fun revalidateCityCapabilities(): TransitLoadResult<List<TransitCity>> {
            revalidationCalls += 1
            return revalidation()
        }

        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: TransitLocale,
        ): TransitLoadResult<List<TransitStop>> {
            calls += NearbyCall(cityId, center, radiusMeters, limit, locale)
            return results.removeFirstOrNull()?.invoke()
                ?: TransitLoadResult.Empty(TransitFreshness.Network)
        }

        override suspend fun directionStops(
            cityId: CityId,
            routeId: RouteId,
            directionId: DirectionId,
            locale: TransitLocale,
        ): TransitLoadResult<List<TransitStop>> {
            directionStopCalls += routeId to directionId
            return directionStopResults[routeId to directionId]?.invoke()
                ?: TransitLoadResult.Empty(TransitFreshness.Network)
        }
    }

    private class VehicleTapRepository(private val route: TransitRoute) : TransitRepository {
        val city = TransitCity(
            id = CityId("vehicle-city"),
            name = "Vehicle city",
            center = GeoPoint(41.7, 44.8),
            capabilities = CityCapabilities(
                stops = true,
                vehicles = true,
                arrivals = false,
                routeShapes = false,
                journeyPlanning = false,
            ),
            defaultZoom = 13.0,
        )

        override fun cities(): List<TransitCity> = listOf(city)

        override fun routes(cityId: CityId): List<TransitRoute> = listOf(route)

        override suspend fun vehicles(
            cityId: CityId,
            routeId: RouteId,
            directionId: DirectionId?,
        ): TransitLoadResult<VehiclePage> = TransitLoadResult.Data(
            VehiclePage(
                items = listOf(
                    TransitVehicle(
                        id = VehicleId("vehicle"),
                        routeId = route.id,
                        directionId = DirectionId("outbound"),
                        position = city.center,
                        bearing = 90.0,
                        nextStopId = null,
                        observedAt = Now,
                        ageSeconds = 0,
                        positionKind = VehiclePositionKind.Gps,
                    ),
                ),
                observedAt = Now,
                maxAgeSeconds = 30,
                stale = false,
            ),
            TransitFreshness.Network,
        )

        companion object {
            val Now: Instant = Instant.parse("2030-01-01T00:00:00Z")
        }
    }
}
