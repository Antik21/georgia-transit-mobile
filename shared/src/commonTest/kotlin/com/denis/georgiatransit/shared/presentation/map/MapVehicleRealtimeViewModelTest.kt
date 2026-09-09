package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class MapVehicleRealtimeViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectedKnownRouteStartsImmediatelyThenPollsEveryEightSecondsWithoutOverlap() = runTest {
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                TransitLoadResult.Data(page(listOf(vehicle("vehicle-${requests.size}", routeId))), TransitFreshness.Network)
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA))
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope = this), testClock(), ticker(frameMillis = 8_000))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()

            assertEquals(listOf(routeA), repository.requests)
            assertEquals(1, repository.maximumVehicleRequestsInFlight)
            this@runTest.advanceTimeBy(7_999)
            this@runTest.runCurrent()
            assertEquals(1, repository.requests.size)
            this@runTest.advanceTimeBy(1)
            this@runTest.runCurrent()
            assertEquals(listOf(routeA, routeA), repository.requests)
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun hiddenNoSelectionAndUnavailableCapabilityNeverRequestVehiclesAndPanningOnlyLoadsStops() = runTest {
        val hiddenRepository = ScriptedVehicleRepository(routes = listOf(route(routeA)))
        val hiddenSession = selectedSession(hiddenRepository, routes = setOf(routeA))
        val hidden = MapViewModel(hiddenRepository, hiddenSession, RuntimeLocationSession(scope = this), testClock(), ticker())
        hidden.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            hidden.dispatchAction(Action.MapEventReceived(MapPlatformEvent.ViewportSettled(MapViewport(origin, 400, 13.0))))
            this@runTest.advanceTimeBy(300)
            this@runTest.runCurrent()
            assertTrue(hiddenRepository.requests.isEmpty())
            cancelViewModel(hidden)
            cancelAndIgnoreRemainingItems()
        }

        val noSelectionRepository = ScriptedVehicleRepository(routes = listOf(route(routeA)))
        val noSelectionSession = selectedSession(noSelectionRepository, routes = emptySet())
        val noSelection = MapViewModel(noSelectionRepository, noSelectionSession, RuntimeLocationSession(scope = this), testClock(), ticker())
        noSelection.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            noSelection.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertTrue(noSelectionRepository.requests.isEmpty())
            cancelViewModel(noSelection)
            cancelAndIgnoreRemainingItems()
        }

        val unavailableRepository = ScriptedVehicleRepository(routes = listOf(route(routeA)), vehiclesAvailable = false)
        val unavailableSession = selectedSession(unavailableRepository, routes = setOf(routeA))
        val unavailable = MapViewModel(unavailableRepository, unavailableSession, RuntimeLocationSession(scope = this), testClock(), ticker())
        unavailable.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            unavailable.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertTrue(unavailableRepository.requests.isEmpty())
            cancelViewModel(unavailable)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun visibilityCancellationRejectsLateNonCooperativeVehicleResult() = runTest {
        val lateResult = CompletableDeferred<TransitLoadResult<VehiclePage>>()
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                withContext(NonCancellable) { lateResult.await() }
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA))
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope = this), testClock(), ticker())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertEquals(1, repository.requests.size)

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            lateResult.complete(TransitLoadResult.Data(page(listOf(vehicle("late", routeA))), TransitFreshness.Network))
            this@runTest.runCurrent()

            val state = viewModel.container.stateFlow.value
            assertTrue(state.renderState?.vehicles.orEmpty().isEmpty())
            assertIs<VehicleLayerState.Hidden>(state.vehicleLayerState)
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun idleFramesDoNotChurnSourceBadgeOrCameraRevisionsAndVisibilityClearsTheLiveFrame() = runTest {
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                TransitLoadResult.Data(page(listOf(vehicle("live", routeId))), TransitFreshness.Network)
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA))
        val viewModel = MapViewModel(
            repository,
            session,
            RuntimeLocationSession(scope = this),
            testClock(),
            ticker(pollMillis = 60_000, frameMillis = 10),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            val initial = checkNotNull(viewModel.container.stateFlow.value.renderState)
            assertIs<VehicleLayerState.Live>(viewModel.container.stateFlow.value.vehicleLayerState)
            assertEquals(1, initial.vehicles.size)

            this@runTest.advanceTimeBy(50)
            this@runTest.runCurrent()
            val idle = checkNotNull(viewModel.container.stateFlow.value.renderState)
            assertEquals(initial.vehicleSourceRevision, idle.vehicleSourceRevision)
            assertEquals(initial.vehicleBadgeRevision, idle.vehicleBadgeRevision)
            assertEquals(initial.camera.revision, idle.camera.revision)
            assertEquals(initial.vehicles, idle.vehicles)

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            val hidden = viewModel.container.stateFlow.value
            assertIs<VehicleLayerState.Hidden>(hidden.vehicleLayerState)
            assertTrue(hidden.renderState?.vehicles.orEmpty().isEmpty())
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun routeAndCityChangesRejectLateNonCooperativeVehicleResults() = runTest {
        val routeResult = CompletableDeferred<TransitLoadResult<VehiclePage>>()
        val cityResult = CompletableDeferred<TransitLoadResult<VehiclePage>>()
        var callCount = 0
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                callCount++
                withContext(NonCancellable) {
                    if (callCount == 1) routeResult.await() else cityResult.await()
                }
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA))
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope = this), testClock(), ticker())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertEquals(1, repository.requests.size)

            session.selectRoutes(repository.city.id, emptySet())
            this@runTest.runCurrent()
            routeResult.complete(TransitLoadResult.Data(page(listOf(vehicle("late-route", routeA))), TransitFreshness.Network))
            this@runTest.runCurrent()
            assertTrue(viewModel.container.stateFlow.value.renderState?.vehicles.orEmpty().isEmpty())
            assertIs<VehicleLayerState.Hidden>(viewModel.container.stateFlow.value.vehicleLayerState)

            session.selectRoutes(repository.city.id, setOf(routeA))
            this@runTest.runCurrent()
            assertEquals(2, repository.requests.size)
            session.selectCity(repository.city.copy(id = CityId("other-city"), name = "Other city"))
            this@runTest.runCurrent()
            cityResult.complete(TransitLoadResult.Data(page(listOf(vehicle("late-city", routeA))), TransitFreshness.Network))
            this@runTest.runCurrent()

            val state = viewModel.container.stateFlow.value
            assertEquals("Other city", state.cityName)
            assertTrue(state.renderState?.vehicles.orEmpty().isEmpty())
            assertIs<VehicleLayerState.Hidden>(state.vehicleLayerState)
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun committedReplacementCancelsRemovedRouteAndNeverPublishesItsLateVehicleFrame() = runTest {
        val lateA = CompletableDeferred<TransitLoadResult<VehiclePage>>()
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA), route(routeB), route(routeC))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                when (routeId) {
                    routeA -> withContext(NonCancellable) { lateA.await() }
                    routeB, routeC -> TransitLoadResult.Data(
                        page(listOf(vehicle("${routeId.value}-${requests.count { it == routeId }}", routeId))),
                        TransitFreshness.Network,
                    )
                    else -> error("Unexpected route request: $routeId")
                }
            }
        }
        val session = selectedSession(repository, routes = linkedSetOf(routeA, routeB))
        val viewModel = MapViewModel(
            repository,
            session,
            RuntimeLocationSession(scope = this),
            testClock(),
            ticker(pollMillis = 60_000, frameMillis = 60_000),
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertEquals(listOf(routeA, routeB), repository.requests)

            assertTrue(session.selectRoutes(repository.city.id, linkedSetOf(routeB, routeC)))
            this@runTest.runCurrent()

            assertEquals(
                listOf(routeA, routeB, routeB, routeC),
                repository.requests,
                "Replacement may restart a retained route, but it must never request removed A again.",
            )
            val afterReplacement = viewModel.container.stateFlow.value
            assertEquals(
                setOf(routeB, routeC),
                afterReplacement.renderState?.vehicles.orEmpty().map(MapVehicleMarker::routeId).toSet(),
            )
            assertEquals(setOf(routeB, routeC), afterReplacement.vehicleRoutes.map(VehicleRouteAccessibilityUi::routeId).toSet())
            assertTrue(
                repository.maximumVehicleRequestsInFlightByRoute.values.all { it <= 1 },
                "There must be no overlapping request for the same (city, route) key.",
            )

            lateA.complete(TransitLoadResult.Data(page(listOf(vehicle("late-a", routeA))), TransitFreshness.Network))
            this@runTest.runCurrent()

            val afterLateA = viewModel.container.stateFlow.value
            assertEquals(
                setOf(routeB, routeC),
                afterLateA.renderState?.vehicles.orEmpty().map(MapVehicleMarker::routeId).toSet(),
                "A late result from a removed route must not resurrect its marker.",
            )
            assertEquals(
                listOf(routeA, routeB, routeB, routeC),
                repository.requests,
                "Polling is driven only by the committed, resolved active selection.",
            )
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun vehicleCapabilityOffClearsTheLayerAndRejectsLateFramesUntilTheCapabilityReturns() = runTest {
        val lateResult = CompletableDeferred<TransitLoadResult<VehiclePage>>()
        var requests = 0
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA))).apply {
            vehicleHandler = { _, routeId ->
                this.requests += routeId
                requests += 1
                if (requests == 1) {
                    withContext(NonCancellable) { lateResult.await() }
                } else {
                    TransitLoadResult.Data(page(listOf(vehicle("fresh", routeId))), TransitFreshness.Network)
                }
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA))
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope = this), testClock(), ticker(pollMillis = 60_000))

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertEquals(1, repository.requests.size)

            session.selectCity(repository.city.copy(capabilities = repository.city.capabilities.copy(vehicles = false)))
            this@runTest.runCurrent()
            assertIs<VehicleLayerState.Unavailable>(viewModel.container.stateFlow.value.vehicleLayerState)
            assertTrue(viewModel.container.stateFlow.value.renderState?.vehicles.orEmpty().isEmpty())

            lateResult.complete(TransitLoadResult.Data(page(listOf(vehicle("late", routeA))), TransitFreshness.Network))
            this@runTest.runCurrent()
            assertIs<VehicleLayerState.Unavailable>(viewModel.container.stateFlow.value.vehicleLayerState)
            assertTrue(viewModel.container.stateFlow.value.renderState?.vehicles.orEmpty().isEmpty())

            session.selectCity(repository.city)
            this@runTest.runCurrent()
            assertEquals(listOf(routeA, routeA), repository.requests)
            assertEquals(listOf("fresh"), viewModel.container.stateFlow.value.renderState?.vehicles.orEmpty().map { it.id.value })
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.runCurrent()
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun routeStatusIsMixedButAccessibilityRowsRetainEachRoutePhaseAndCount() = runTest {
        val repository = ScriptedVehicleRepository(routes = listOf(route(routeA), route(routeB))).apply {
            vehicleHandler = { _, routeId ->
                requests += routeId
                when (routeId) {
                    routeA -> TransitLoadResult.Data(page(listOf(vehicle("live", routeA))), TransitFreshness.Network)
                    routeB -> TransitLoadResult.Failure(TransitFailure.Transport("timeout"))
                    else -> error("Unexpected route")
                }
            }
        }
        val session = selectedSession(repository, routes = setOf(routeA, routeB))
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope = this), testClock(), ticker())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()

            val state = viewModel.container.stateFlow.value
            assertIs<VehicleLayerState.Mixed>(state.vehicleLayerState)
            val rows = state.vehicleRoutes.associateBy(VehicleRouteAccessibilityUi::routeId)
            assertEquals(1, rows.getValue(routeA).vehicleCount)
            assertIs<VehicleLayerState.Live>(rows.getValue(routeA).layerState)
            assertEquals(0, rows.getValue(routeB).vehicleCount)
            assertIs<VehicleLayerState.Retryable>(rows.getValue(routeB).layerState)
            cancelViewModel(viewModel)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    private fun cancelViewModel(viewModel: MapViewModel) {
        viewModel.viewModelScope.cancel()
    }

    private fun TestScope.testClock() = object : VehicleRealtimeClock {
        override fun wallNow(): Instant = now + testScheduler.currentTime.toDuration(DurationUnit.MILLISECONDS)
        override fun monotonicNowMillis(): Long = testScheduler.currentTime
    }

    private fun ticker(pollMillis: Long = 8_000, frameMillis: Long = 8_000) = object : VehicleRealtimeTickerPolicy {
        override val pollIntervalMillis: Long = pollMillis
        override val frameIntervalMillis: Long = frameMillis
    }

    private fun selectedSession(repository: ScriptedVehicleRepository, routes: Set<RouteId>): RuntimeTransitSession =
        RuntimeTransitSession().also {
            it.selectCity(repository.city)
            it.selectRoutes(repository.city.id, routes)
        }

    private fun page(items: List<TransitVehicle>, observedAt: Instant = now) = VehiclePage(
        items = items,
        observedAt = observedAt,
        maxAgeSeconds = 30,
        stale = false,
    )

    private fun vehicle(
        id: String,
        route: RouteId,
        point: GeoPoint = origin,
        observedAt: Instant = now,
    ) = TransitVehicle(
        id = VehicleId(id),
        routeId = route,
        directionId = DirectionId("outbound"),
        position = point,
        bearing = 90.0,
        nextStopId = null,
        observedAt = observedAt,
        ageSeconds = 0,
        positionKind = VehiclePositionKind.Gps,
    )

    private fun route(id: RouteId) = TransitRoute(
        id = id,
        cityId = cityId,
        shortName = id.value,
        name = id.value,
        colorArgb = 0xFF0057B8,
    )

    private class ScriptedVehicleRepository(
        private val routes: List<TransitRoute>,
        vehiclesAvailable: Boolean = true,
    ) : TransitRepository {
        val city = TransitCity(
            id = cityId,
            name = "Test city",
            center = origin,
            capabilities = CityCapabilities(
                stops = true,
                vehicles = vehiclesAvailable,
                arrivals = false,
                routeShapes = false,
                journeyPlanning = false,
            ),
        )
        val requests = mutableListOf<RouteId>()
        var nearbyRequests = 0
        var maximumVehicleRequestsInFlight = 0
        private var vehicleRequestsInFlight = 0
        val maximumVehicleRequestsInFlightByRoute = mutableMapOf<RouteId, Int>()
        private val vehicleRequestsInFlightByRoute = mutableMapOf<RouteId, Int>()
        var vehicleHandler: suspend (CityId, RouteId) -> TransitLoadResult<VehiclePage> = { _, _ ->
            TransitLoadResult.Empty(TransitFreshness.Network)
        }

        override fun cities(): List<TransitCity> = listOf(city)

        override fun routes(cityId: CityId): List<TransitRoute> = if (cityId == city.id) routes else emptyList()

        override suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> {
            vehicleRequestsInFlight++
            maximumVehicleRequestsInFlight = maxOf(maximumVehicleRequestsInFlight, vehicleRequestsInFlight)
            val routeRequestsInFlight = (vehicleRequestsInFlightByRoute[routeId] ?: 0) + 1
            vehicleRequestsInFlightByRoute[routeId] = routeRequestsInFlight
            maximumVehicleRequestsInFlightByRoute[routeId] = maxOf(
                maximumVehicleRequestsInFlightByRoute[routeId] ?: 0,
                routeRequestsInFlight,
            )
            return try {
                vehicleHandler(cityId, routeId)
            } finally {
                vehicleRequestsInFlight--
                vehicleRequestsInFlightByRoute[routeId] = vehicleRequestsInFlightByRoute.getValue(routeId) - 1
            }
        }

        override suspend fun nearbyStops(
            cityId: CityId,
            center: GeoPoint,
            radiusMeters: Int,
            limit: Int,
            locale: com.denis.georgiatransit.shared.domain.model.TransitLocale,
        ): TransitLoadResult<List<com.denis.georgiatransit.shared.domain.model.TransitStop>> {
            nearbyRequests++
            return TransitLoadResult.Empty(TransitFreshness.Network)
        }
    }

    private companion object {
        val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val cityId = CityId("test-city")
        val routeA = RouteId("route-a")
        val routeB = RouteId("route-b")
        val routeC = RouteId("route-c")
        val origin = GeoPoint(41.715, 44.827)
    }
}
