package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneyLeg
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus as Status

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyPlannerViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block(this)
    }

    @Test
    fun pointToPointPlanningWorksWithoutLocationAndSendsRoundedPoints() = runTest {
        val planner = FakePlanRepository()
        val viewModel = viewModel(planner)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, GeoPoint(41.640049, 41.630051), B)

            val results = assertIs<Status.Results>(plannerState(viewModel).status)
            assertEquals(JourneyId("demo:fixture:journey:one"), results.selectedId)
            assertEquals(listOf(JourneyPlanQuery(GeoPoint(41.64, 41.6301), B, 2, 60, planner.queries[0].locale)), planner.queries)
            assertTrue(plannerState(viewModel).render.lines.isNotEmpty())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun aLateAnswerFromANonCooperativeClientNeverReplacesTheLatestPlan() = runTest {
        val first = CompletableDeferred<Unit>()
        val planner = FakePlanRepository { query ->
            // Ignores cancellation like a client that cannot be interrupted mid-request.
            if (query.to == B) withContext(NonCancellable) { first.await() }
            page(if (query.to == B) "late" else "latest")
        }
        val viewModel = viewModel(planner)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, A, B)
            assertEquals(Status.Loading, plannerState(viewModel).status)
            viewModel.dispatchAction(JourneyPlannerAction.StartPickingDestination)
            longPress(viewModel, C)
            first.complete(Unit)
            this@runTest.runCurrent()

            val results = assertIs<Status.Results>(plannerState(viewModel).status)
            assertEquals(JourneyId("demo:fixture:journey:latest"), results.selectedId)
            assertEquals(2, planner.queries.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun visibleResultsReplanEveryMinuteAndStopWhenHiddenOrClosed() = runTest {
        val planner = FakePlanRepository()
        val viewModel = viewModel(planner)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, A, B)
            this@runTest.advanceTimeBy(JOURNEY_REFRESH_INTERVAL_MILLIS + 1)
            this@runTest.runCurrent()
            assertEquals(2, planner.queries.size)
            assertIs<Status.Results>(plannerState(viewModel).status)

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(false))
            this@runTest.advanceTimeBy(3 * JOURNEY_REFRESH_INTERVAL_MILLIS)
            this@runTest.runCurrent()
            assertEquals(2, planner.queries.size, "A hidden map must not re-plan")

            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runTest.runCurrent()
            assertEquals(3, planner.queries.size, "Returning to the map refreshes the visible answer")
            viewModel.dispatchAction(JourneyPlannerAction.ClosePlanner)
            this@runTest.advanceTimeBy(3 * JOURNEY_REFRESH_INTERVAL_MILLIS)
            this@runTest.runCurrent()
            assertEquals(3, planner.queries.size)
            assertEquals(JourneyPlannerUi(), plannerState(viewModel))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun leavingTheCityClearsThePlannerAndDropsItsPendingAnswer() = runTest {
        val pending = CompletableDeferred<Unit>()
        val planner = FakePlanRepository { pending.await(); page("one") }
        val session = RuntimeTransitSession().also { it.selectCity(CITY) }
        val viewModel = viewModel(planner, session)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, A, B)
            session.selectCity(OTHER_CITY)
            this@runTest.runCurrent()
            pending.complete(Unit)
            this@runTest.runCurrent()

            assertEquals(JourneyPlannerUi(), plannerState(viewModel))
            longPress(viewModel, A)
            assertNull(plannerState(viewModel).pin, "A city without trip planning ignores the long press")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun alreadyThereNeedsNoRequestAndMyLocationUsesTheExplicitCommandFlow() = runTest {
        val planner = FakePlanRepository()
        val viewModel = viewModel(planner)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, A, GeoPoint(A.latitude + 0.0005, A.longitude))
            assertIs<Status.AlreadyThere>(plannerState(viewModel).status)
            assertTrue(planner.queries.isEmpty())

            viewModel.dispatchAction(JourneyPlannerAction.SetOriginToMyLocation)
            this@runTest.runCurrent()

            assertIs<LocationPlatformCommand.RequestPermission>(awaitLocationCommand())
            assertEquals(Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.PermissionRequired),
                plannerState(viewModel).status)
            assertTrue(planner.queries.isEmpty())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun aPlanThroughAnUnknownRouteLoadsTheCatalogueForItsColours() = runTest {
        val catalog = PlannerTransitRepository()
        val planner = FakePlanRepository { page("ride", transitRoute = ROUTE.id) }
        val session = RuntimeTransitSession().also { it.selectCity(CITY.copy(capabilities = CITY.capabilities.copy(routes = true))) }
        val viewModel = viewModel(planner, session, repository = catalog)

        viewModel.test(this) {
            runOnCreate()
            start(viewModel)
            planBetween(viewModel, A, B)

            val ride = assertIs<Status.Results>(plannerState(viewModel).status).options.single().legs
                .filterIsInstance<JourneyLegUi.Ride>().single()
            assertEquals(1, catalog.refreshes)
            assertEquals("B7", ride.route.label)
            assertEquals(ROUTE.colorArgb, ride.route.backgroundArgb)
            cancelAndIgnoreRemainingItems()
        }
    }

    private suspend fun TestScope.start(viewModel: MapViewModel) {
        runCurrent()
        viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
        runCurrent()
    }

    private fun TestScope.planBetween(viewModel: MapViewModel, from: GeoPoint, to: GeoPoint) {
        longPress(viewModel, from)
        viewModel.dispatchAction(JourneyPlannerAction.RouteFromHere)
        runCurrent()
        assertEquals(Status.PickingPoint(JourneyPlanSide.Destination), plannerState(viewModel).status)
        longPress(viewModel, to)
    }

    private fun TestScope.longPress(viewModel: MapViewModel, point: GeoPoint) {
        viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.MapLongPressed(point)))
        runCurrent()
    }

    private fun plannerState(viewModel: MapViewModel) = viewModel.container.stateFlow.value.journeyPlanner

    private fun TestScope.viewModel(
        planner: JourneyPlanRepository,
        session: RuntimeTransitSession = RuntimeTransitSession().also { it.selectCity(CITY) },
        locationSession: LocationSession = RuntimeLocationSession(scope = this, nowMillis = { NOW_MILLIS + testScheduler.currentTime }),
        repository: TransitRepository = PlannerTransitRepository(),
    ) = MapViewModel(
        repository = repository,
        session = session,
        locationSession = locationSession,
        journeyPlanRepository = planner,
        journeyPlanClock = { NOW_MILLIS + testScheduler.currentTime },
    )

    private suspend fun OrbitTestContext<ViewState, SideEffect, MapViewModel>.awaitLocationCommand(): LocationPlatformCommand {
        while (true) {
            val item = awaitItem()
            if (item is Item.SideEffectItem) return (item.value as SideEffect.HandleLocation).command
        }
    }

    private class FakePlanRepository(
        private val answer: suspend (JourneyPlanQuery) -> TransitLoadResult<JourneyPage> = { page("one") },
    ) : JourneyPlanRepository {
        val queries = mutableListOf<JourneyPlanQuery>()

        override suspend fun plan(cityId: CityId, query: JourneyPlanQuery): TransitLoadResult<JourneyPage> {
            queries += query
            return answer(query)
        }
    }

    private class PlannerTransitRepository : TransitRepository {
        var refreshes = 0
        private var loaded = emptyList<TransitRoute>()
        override fun cities() = listOf(CITY, OTHER_CITY)
        override fun routes(cityId: CityId) = loaded

        override suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> {
            refreshes++
            loaded = listOf(ROUTE)
            return TransitLoadResult.Data(loaded, TransitFreshness.Network)
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_900_000_000_000L
        val A = GeoPoint(41.6400, 41.6300)
        val B = GeoPoint(41.6500, 41.6400)
        val C = GeoPoint(41.6600, 41.6500)
        val CITY = TransitCity(
            id = CityId("batumi"), name = "Batumi", center = A,
            capabilities = CityCapabilities(stops = false, vehicles = false, arrivals = false, routeShapes = false, journeyPlanning = true),
        )
        val OTHER_CITY = CITY.copy(id = CityId("kutaisi"), name = "Kutaisi",
            capabilities = CITY.capabilities.copy(journeyPlanning = false))

        val ROUTE = TransitRoute(RouteId("batumi:fixture:route:orange"), CityId("batumi"), "B7", "Orange", 0xFFE4572EL)

        fun page(id: String, transitRoute: RouteId? = null): TransitLoadResult<JourneyPage> {
            val start = Instant.fromEpochMilliseconds(NOW_MILLIS)
            val end = Instant.fromEpochMilliseconds(NOW_MILLIS + 600_000)
            val walk = TransitJourneySegment(start, end, JourneySegmentMode.Walk, fromPosition = A, toPosition = B, distanceMeters = 900.0)
            val segment = transitRoute?.let {
                walk.copy(mode = JourneySegmentMode.Transit, routeId = it, directionId = DirectionId("batumi:fixture:direction:orange"),
                    fromStopId = StopId("batumi:fixture:stop:a"), toStopId = StopId("batumi:fixture:stop:b"), distanceMeters = null)
            } ?: walk
            val legs = listOfNotNull(transitRoute?.let {
                TransitJourneyLeg(it, segment.directionId!!, segment.fromStopId!!, segment.toStopId!!, start, end)
            })
            val journey = TransitJourney(JourneyId("demo:fixture:journey:$id"), start, end, 0, legs, listOf(segment))
            return TransitLoadResult.Data(JourneyPage(listOf(journey), start, ArrivalSource.ClientEstimate), TransitFreshness.Network)
        }
    }
}
