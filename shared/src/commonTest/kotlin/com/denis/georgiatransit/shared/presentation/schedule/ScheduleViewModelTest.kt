package com.denis.georgiatransit.shared.presentation.schedule

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.DirectionSchedule
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.ScheduledStop
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    private val kutaisi = TransitCity(
        id = CityId("kutaisi"),
        name = "Kutaisi",
        center = GeoPoint(42.27, 42.71),
        capabilities = CityCapabilities(true, true, true, true, false, schedules = true),
    )
    private val route = route("3", "outbound", "inbound")
    private val otherRoute = route("4", "north", "south")
    private val today = LocalDate(2026, 11, 19)
    private val tomorrow = LocalDate(2026, 11, 20)

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block()
    }

    @Test
    fun filterChangesLoadTheRequestedTimetableAndLeaveMapSelectionIntact() = runTest {
        val session = RuntimeTransitSession().apply {
            selectCity(kutaisi)
            selectRoutes(kutaisi.id, setOf(route.id))
        }
        val slow = CompletableDeferred<TransitLoadResult<DirectionSchedule>>()
        val repository = ScheduleRepository { request ->
            when {
                request.route == route.id && request.direction == route.directions.last().id -> slow.await()
                request.date == tomorrow -> TransitLoadResult.Empty(TransitFreshness.Network)
                else -> schedule(request.date)
            }
        }
        val viewModel = ScheduleViewModel(kutaisi.id, repository, session, today)
        viewModel.test(this) {
            runOnCreate()
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.Russian))
            this@runTest.runCurrent()
            assertIs<ScheduleContent.Available>(viewModel.container.stateFlow.value.content)

            viewModel.dispatchAction(Action.DirectionSelected(route.directions.last().id))
            this@runTest.runCurrent()
            assertEquals(ScheduleContent.Loading, viewModel.container.stateFlow.value.content)

            viewModel.dispatchAction(Action.RouteSelected(route.id))
            this@runTest.runCurrent()
            assertEquals(route.directions.last().id, viewModel.container.stateFlow.value.directionId)
            assertEquals(2, repository.requests.size)

            viewModel.dispatchAction(Action.RouteSelected(otherRoute.id))
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.DateSelected(tomorrow))
            this@runTest.runCurrent()
            slow.complete(schedule(today))
            this@runTest.runCurrent()

            assertEquals(
                listOf(
                    Request(route.id, route.directions.first().id, today, TransitLocale.Russian),
                    Request(route.id, route.directions.last().id, today, TransitLocale.Russian),
                    Request(otherRoute.id, otherRoute.directions.first().id, today, TransitLocale.Russian),
                    Request(otherRoute.id, otherRoute.directions.first().id, tomorrow, TransitLocale.Russian),
                ),
                repository.requests,
            )
            assertEquals(ScheduleContent.NoTimes, viewModel.container.stateFlow.value.content)
            assertEquals(setOf(route.id), session.selectedRouteIds.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun retryKeepsTheFailedRequestAndIgnoresAnotherTapWhileLoading() = runTest {
        val session = RuntimeTransitSession().apply { selectCity(kutaisi) }
        val failure = TransitLoadResult.Failure(TransitFailure.Transport("fixture"))
        var response = CompletableDeferred<TransitLoadResult<DirectionSchedule>>(failure)
        val repository = ScheduleRepository { request ->
            if (request.date == tomorrow) response.await() else schedule(request.date)
        }
        val locale = TransitLocale.Russian
        val direction = route.directions.last().id
        val viewModel = ScheduleViewModel(kutaisi.id, repository, session, today)
        viewModel.test(this) {
            runOnCreate()
            viewModel.dispatchAction(Action.LocaleChanged(locale))
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.DirectionSelected(direction))
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.DateSelected(tomorrow))
            this@runTest.runCurrent()
            val failed = viewModel.container.stateFlow.value
            assertEquals(ScheduleContent.Error, failed.content)

            response = CompletableDeferred()
            viewModel.dispatchAction(Action.Retry)
            this@runTest.runCurrent()
            viewModel.dispatchAction(Action.Retry)
            this@runTest.runCurrent()
            assertEquals(failed.copy(content = ScheduleContent.Retrying), viewModel.container.stateFlow.value)
            val requested = Request(route.id, direction, tomorrow, locale)
            assertEquals(listOf(requested, requested), repository.requests.filter { it.date == tomorrow })

            response.complete(failure)
            this@runTest.runCurrent()
            assertEquals(failed, viewModel.container.stateFlow.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun capabilityRemovalCancelsTheRequestAndHidesItsResults() = runTest {
        val session = RuntimeTransitSession().apply { selectCity(kutaisi) }
        val pending = CompletableDeferred<TransitLoadResult<DirectionSchedule>>()
        val repository = ScheduleRepository { pending.await() }
        val viewModel = ScheduleViewModel(kutaisi.id, repository, session, today)
        viewModel.test(this) {
            runOnCreate()
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.English))
            this@runTest.runCurrent()
            assertEquals(1, repository.requests.size)

            session.selectCity(kutaisi.copy(capabilities = kutaisi.capabilities.copy(schedules = false)))
            this@runTest.runCurrent()
            pending.complete(schedule(today))
            viewModel.dispatchAction(Action.DateSelected(tomorrow))
            this@runTest.runCurrent()

            assertEquals(ScheduleContent.Unavailable, viewModel.container.stateFlow.value.content)
            assertEquals(1, repository.requests.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun route(number: String, vararg directions: String) = TransitRoute(
        id = RouteId("kutaisi:test:route:$number"),
        cityId = kutaisi.id,
        shortName = number,
        name = number,
        colorArgb = 0,
        directions = directions.map {
            TransitDirection(
                id = DirectionId("kutaisi:test:direction:$it"),
                name = LocalizedText.fromLegacy(it),
                headsign = LocalizedText.fromLegacy(it),
            )
        },
    )

    private fun schedule(date: LocalDate) = TransitLoadResult.Data(
        DirectionSchedule(
            serviceDate = date,
            timeZone = TimeZone.of("Asia/Tbilisi"),
            stops = listOf(
                ScheduledStop(
                    id = StopId("kutaisi:test:stop:1"),
                    name = LocalizedText.fromLegacy("Station"),
                    arrivalTimes = listOf(Instant.parse("${date}T04:00:00Z")),
                ),
            ),
        ),
        TransitFreshness.Network,
    )

    private data class Request(val route: RouteId, val direction: DirectionId, val date: LocalDate, val locale: TransitLocale)

    private inner class ScheduleRepository(
        private val respond: suspend (Request) -> TransitLoadResult<DirectionSchedule>,
    ) : TransitRepository {
        val requests = mutableListOf<Request>()

        override fun cities() = listOf(kutaisi)
        override fun routes(cityId: CityId) = listOf(route, otherRoute)

        override suspend fun directionSchedule(
            cityId: CityId,
            routeId: RouteId,
            directionId: DirectionId,
            date: LocalDate,
            locale: TransitLocale,
        ): TransitLoadResult<DirectionSchedule> {
            assertEquals(kutaisi.id, cityId)
            val request = Request(routeId, directionId, date, locale)
            requests += request
            return respond(request)
        }
    }
}
