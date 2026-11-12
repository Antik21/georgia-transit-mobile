package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitArrival
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.LocationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyBusesWorkflowTest {
    @Test
    fun theTwoSoonestVehiclesOfTheLegRouteArePolledUntilTheSelectionChanges() = runTest {
        val repository = BusRepository()
        val workflow = JourneyBusesWorkflow(repository, vehicleIntervalMillis = 8_000)
        val updates = mutableListOf<JourneyBusesUpdate>()

        workflow.sync(backgroundScope, key(OPTION_A)) { updates += it }
        runCurrent()
        val first = updates.last()
        assertEquals(listOf(V1 to 3, V2 to 11), first.soonest, "Other routes, missing vehicles and later buses are ignored")
        assertEquals(setOf(V1, V2, V3), first.positions.keys)
        val projected = JourneyBusesProjector.project(option(OPTION_A), first)
        assertEquals(listOf(V1, V2), projected.vehicles.map { it.id })
        assertEquals(listOf(3, 11), projected.etaMinutes)

        advanceTimeBy(8_001)
        assertEquals(2, repository.vehicleCalls, "Positions follow the vehicle cadence")
        assertEquals(1, repository.arrivalCalls)
        advanceTimeBy(JOURNEY_ARRIVALS_INTERVAL_MILLIS)
        assertEquals(2, repository.arrivalCalls, "Arrivals follow the stop-arrivals cadence")

        workflow.sync(backgroundScope, key(OPTION_B)) { updates += it }
        assertFalse(workflow.isCurrent(first), "A late update for the previous selection is rejected")
        workflow.sync(backgroundScope, null) { updates += it }
        val calls = repository.arrivalCalls + repository.vehicleCalls
        advanceTimeBy(10 * JOURNEY_ARRIVALS_INTERVAL_MILLIS)
        assertEquals(calls, repository.arrivalCalls + repository.vehicleCalls, "Nothing polls without a selection")
    }

    @Test
    fun fewerThanTwoBusesShowWhatExistsAndPositionsNeedTheirCapability() = runTest {
        val repository = BusRepository(arrivals = listOf(arrival(V2, 7)))
        val workflow = JourneyBusesWorkflow(repository, vehicleIntervalMillis = 8_000)
        val updates = mutableListOf<JourneyBusesUpdate>()

        workflow.sync(backgroundScope, key(OPTION_A, positions = false)) { updates += it }
        advanceTimeBy(30_000)

        assertEquals(listOf(V2 to 7), updates.last().soonest)
        assertEquals(0, repository.vehicleCalls)
        assertTrue(JourneyBusesProjector.project(option(OPTION_A), updates.last()).vehicles.isEmpty())
    }

    @Test
    fun theArrivalsCapabilityAHiddenMapAndAClosedPlannerStopEveryPoll() = runTest {
        val repository = BusRepository()
        val workflow = JourneyPlannerWorkflow({ _, _ -> error("No plan expected") }, repository, { 0L }, 8_000)
        val results = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A), destination = JourneyEndpoint.MapPoint(A),
            status = JourneyPlannerStatus.Results(listOf(option(OPTION_A)), OPTION_A))

        execute(workflow, results, context(arrivals = false))
        assertEquals(0, repository.arrivalCalls, "Without the arrivals capability nothing is polled")
        execute(workflow, results, context())
        assertEquals(1, repository.arrivalCalls)
        execute(workflow, results, context(visible = false))
        execute(workflow, results, context())
        assertEquals(2, repository.arrivalCalls, "A hidden map stops polling and a visible one starts afresh")
        execute(workflow, JourneyPlannerUi(), context())
        advanceTimeBy(10 * JOURNEY_ARRIVALS_INTERVAL_MILLIS)
        assertEquals(2, repository.arrivalCalls, "Closing the planner stops polling")
    }

    private fun TestScope.execute(workflow: JourneyPlannerWorkflow, state: JourneyPlannerUi, context: JourneyPlannerContext) {
        workflow.execute(backgroundScope, JourneyPlannerStep(state), context) {}
        runCurrent()
    }

    private fun context(arrivals: Boolean = true, visible: Boolean = true) = JourneyPlannerContext(
        city = CITY.copy(capabilities = CITY.capabilities.copy(arrivals = arrivals)),
        location = LocationState(), nowMillis = 0L, locale = TransitLocale.English, visible = visible, routes = emptyList(),
    )

    private fun key(option: JourneyId, positions: Boolean = true) =
        JourneyBusKey(CITY.id, option, BOARDING, TransitLocale.English, positions)

    private fun option(id: JourneyId) = JourneyOptionUi(
        id = id, totalMinutes = 20, arrivalAt = Instant.fromEpochMilliseconds(0), transfers = 0, walkingMeters = 300, timing = null,
        legs = listOf(JourneyLegUi.Ride(JourneyRouteBadgeUi(ROUTE, "11", 0xFF0057B8L, 0xFFFFFFFFL), 4, 10, 2, true)),
        boarding = BOARDING,
    )

    private class BusRepository(
        private val arrivals: List<TransitArrival> = listOf(
            arrival(V3, 15), arrival(null, 1), arrival(VehicleId("demo:fixture:vehicle:other"), 1, RouteId("demo:fixture:route:other")),
            arrival(V2, 11), arrival(V1, 3),
        ),
    ) : TransitRepository {
        var arrivalCalls = 0
        var vehicleCalls = 0
        override fun cities() = listOf(CITY)
        override fun routes(cityId: CityId) = emptyList<TransitRoute>()

        override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale): TransitLoadResult<ArrivalPage> {
            arrivalCalls++
            return TransitLoadResult.Data(ArrivalPage(arrivals, ArrivalSource.OfficialRealtime, Instant.fromEpochMilliseconds(0), false),
                TransitFreshness.Network)
        }

        override suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> {
            vehicleCalls++
            val items = listOf(V1, V2, V3).map { TransitVehicle(it, routeId, null, A, null, null, null, 0, VehiclePositionKind.Gps) }
            return TransitLoadResult.Data(VehiclePage(items, Instant.fromEpochMilliseconds(0), 30, false), TransitFreshness.Network)
        }
    }

    private companion object {
        val A = GeoPoint(41.64, 41.63)
        val ROUTE = RouteId("demo:fixture:route:blue")
        val BOARDING = JourneyBoarding(StopId("demo:fixture:stop:a"), ROUTE)
        val OPTION_A = JourneyId("demo:fixture:journey:a")
        val OPTION_B = JourneyId("demo:fixture:journey:b")
        val V1 = VehicleId("demo:fixture:vehicle:v1")
        val V2 = VehicleId("demo:fixture:vehicle:v2")
        val V3 = VehicleId("demo:fixture:vehicle:v3")
        val CITY = TransitCity(
            id = CityId("batumi"), name = "Batumi", center = A,
            capabilities = CityCapabilities(stops = false, vehicles = true, arrivals = true, routeShapes = false, journeyPlanning = true),
        )

        fun arrival(vehicle: VehicleId?, minutes: Int, route: RouteId = ROUTE) = TransitArrival(
            StopId("demo:fixture:stop:a"), route, vehicle, null, LocalizedText.fromLegacy("Park"), null, null, minutes,
            realtime = true, cancelled = false, source = ArrivalSource.OfficialRealtime,
        )
    }
}
