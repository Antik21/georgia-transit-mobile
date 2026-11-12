package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.JourneyLabel
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitArrival
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyPlannerBridgeTest {
    @Test
    fun hostCommandsDriveThePlannerAndReceiveDisplayReadyOptions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val queries = mutableListOf<JourneyPlanQuery>()
            var answer: TransitLoadResult<JourneyPage> = plan()
            val planner = JourneyPlanRepository { _, query -> queries += query; answer }
            val vm = viewModel(store, planner)
            val entry = MapBridge(vm, store, CITY) {}
            try {
                var state: PlannerState? = null
                entry.planner.subscribe { state = it }
                entry.setVisible(true)
                settle()
                assertEquals("idle", state?.status)

                entry.planner.longPressMap(A.latitude, A.longitude)
                settle()
                assertEquals(A.latitude, state?.pin?.latitude)
                assertEquals(false, state?.open)
                entry.planner.routeFromHere()
                settle()
                assertEquals("pickingPoint", state?.status)
                assertEquals("destination", state?.side)
                assertEquals("point", state?.origin?.kind)
                assertEquals("none", state?.destination?.kind)

                entry.planner.longPressMap(B.latitude, B.longitude)
                settle()
                val results = assertNotNull(state)
                assertEquals("results", results.status)
                val option = results.options.single()
                assertTrue(option.selected)
                assertEquals(21, option.totalMinutes)
                assertEquals("live", option.timing)
                assertEquals(listOf("walk", "ride", "walk"), option.legs.map { it.kind })
                assertEquals("access", option.legs[0].role)
                assertEquals(300, option.legs[0].meters)
                assertEquals("D1", option.legs[1].routeLabel)
                assertEquals("#0057b8", option.legs[1].routeColor)
                assertEquals(6, option.legs[1].stopCount)
                assertEquals(2, option.legs[1].waitMinutes)
                assertTrue(option.legs[1].waitLive)
                assertEquals(listOf("walk", "transit", "walk"), results.route.lines.map { it.kind })
                assertEquals("#0057b8", results.route.lines[1].color)
                assertEquals(A.latitude, results.route.origin?.latitude)

                entry.planner.selectOption("demo:fixture:journey:one")
                settle()
                assertSame(results.route, state?.route, "An unchanged route keeps its identity for the renderer")

                entry.planner.swap()
                settle()
                assertEquals(B.latitude, state?.origin?.point?.latitude)
                assertEquals(B, queries.last().from)

                answer = TransitLoadResult.Failure(TransitFailure.CapabilityUnavailable("off", "req"))
                entry.planner.retry()
                settle()
                assertEquals("unavailable", state?.status, "A refresh that loses the capability drops the answer")
                entry.planner.clear()
                settle()
                assertEquals("endpointsIncomplete", state?.status)
                assertTrue(state?.route?.lines?.isEmpty() == true)

                entry.planner.longPressMap(A.latitude, A.longitude)
                settle()
                entry.planner.routeHere()
                settle()
                assertEquals("pickingPoint", state?.status, "Without granted access the origin stays empty")
                assertEquals("origin", state?.side)
                entry.planner.useMyLocationFor("origin")
                settle()
                assertEquals("locationNeeded", state?.status)
                assertEquals("permissionRequired", state?.reason)
                entry.planner.pickPointFor("origin")
                entry.planner.longPressMap(B.latitude, B.longitude)
                settle()
                assertEquals("unavailable", state?.status)

                entry.planner.close()
                settle()
                assertEquals(false, state?.open)
                assertNull(state?.route?.origin)

                val requests = queries.size
                entry.dispose()
                entry.planner.longPressMap(A.latitude, A.longitude)
                entry.planner.retry()
                settle()
                assertEquals(requests, queries.size)
            } finally {
                entry.dispose()
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aResubscribedRendererNeverSeesARouteRevisionTwice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val entry = MapBridge(viewModel(store, JourneyPlanRepository { _, _ -> plan() }), store, CITY) {}
            try {
                val revisions = mutableListOf<String>()
                entry.planner.subscribe { revisions += it.route.revision }
                entry.setVisible(true)
                settle()
                entry.planner.longPressMap(A.latitude, A.longitude)
                entry.planner.routeFromHere()
                settle()
                entry.planner.longPressMap(B.latitude, B.longitude)
                settle()
                val before = revisions.maxOf(String::toLong)
                // A renderer keeps its "already framed" revision across a re-subscribe; a restarted counter would reuse it.
                var resubscribed: String? = null
                entry.planner.subscribe { resubscribed = it.route.revision }
                settle()

                assertTrue(before >= 2, "The plan must have produced a route with lines: $before")
                assertTrue(assertNotNull(resubscribed).toLong() > before, "Got $resubscribed after $before")
            } finally {
                entry.dispose()
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theSelectedOptionCarriesLabelsAndItsTwoSoonestBuses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Repository()
        try {
            val vm = viewModel(store, { _, _ -> plan(withWalk = true) }, LIVE_CITY, repository)
            val entry = MapBridge(vm, store, LIVE_CITY) {}
            try {
                var state: PlannerState? = null
                entry.planner.subscribe { state = it }
                entry.setVisible(true)
                settle()
                entry.planner.longPressMap(A.latitude, A.longitude)
                entry.planner.routeFromHere()
                settle()
                entry.planner.longPressMap(B.latitude, B.longitude)
                settle()

                val ride = assertNotNull(state).options.first()
                assertEquals(listOf("optimal", "fastest"), ride.labels.map { it.key }, "Labels come in the fixed display order")
                assertEquals(listOf("#00796b", "#c2410c"), ride.labels.map { it.color })
                assertEquals("bus", ride.legs[1].routeMode)
                assertEquals(listOf(3, 11), ride.nearestEtas.toList())
                assertEquals(listOf("demo:fixture:vehicle:v1", "demo:fixture:vehicle:v2"), state?.buses?.map { it.id })
                assertEquals("#0057b8", state?.buses?.first()?.color)
                assertTrue(state?.options?.last()?.labels?.isEmpty() == true)

                entry.planner.selectOption("demo:fixture:journey:walk")
                settle()
                val arrivalCalls = repository.arrivalCalls
                settle()
                assertTrue(state?.buses?.isEmpty() == true, "A walk-only option shows no buses")
                assertTrue(state?.options?.all { it.nearestEtas.isEmpty() } == true)
                assertEquals(arrivalCalls, repository.arrivalCalls, "Nothing polls without a selected transit option")
            } finally {
                entry.dispose()
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.viewModel(
        store: ViewModelStore, planner: JourneyPlanRepository, city: TransitCity = CITY, repository: TransitRepository = Repository(),
    ): MapViewModel {
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val location = RuntimeLocationSession(scope = backgroundScope, nowMillis = { NOW })
        val factory = viewModelFactory {
            initializer {
                MapViewModel(repository, session, location, journeyPlanRepository = planner, journeyPlanClock = { NOW })
            }
        }
        return ViewModelProvider.create(store, factory)["map", MapViewModel::class]
    }

    private suspend fun TestScope.settle() {
        repeat(5) {
            withContext(Dispatchers.Default) { delay(20) }
            runCurrent()
        }
    }

    private fun plan(withWalk: Boolean = false): TransitLoadResult<JourneyPage> {
        val start = Instant.fromEpochMilliseconds(NOW)
        fun at(minute: Int) = Instant.fromEpochMilliseconds(NOW + minute * 60_000L)
        val segments = listOf(
            TransitJourneySegment(start, at(4), JourneySegmentMode.Walk, toStopId = STOP_A, fromPosition = A,
                toPosition = GeoPoint(41.641, 41.631), distanceMeters = 300.0, geometryApproximate = true),
            TransitJourneySegment(at(6), at(17), JourneySegmentMode.Transit, routeId = ROUTE, directionId = DirectionId("demo:fixture:direction:blue"),
                fromStopId = STOP_A, toStopId = STOP_B, geometry = "_p~iF~ps|U_ulLnnqC", intermediateStopCount = 5,
                waitSeconds = 120, waitBasis = JourneyWaitBasis.LiveVehicle),
            TransitJourneySegment(at(17), at(21), JourneySegmentMode.Walk, fromStopId = STOP_B, fromPosition = GeoPoint(41.649, 41.639),
                toPosition = B, distanceMeters = 220.0),
        )
        val journey = TransitJourney(JourneyId("demo:fixture:journey:one"), start, at(21), 0, emptyList(), segments,
            durationSeconds = 1260, labels = listOf(JourneyLabel.Fastest, JourneyLabel.Optimal))
        val walk = TransitJourney(JourneyId("demo:fixture:journey:walk"), start, at(30), 0, emptyList(),
            listOf(TransitJourneySegment(start, at(30), JourneySegmentMode.Walk, fromPosition = A, toPosition = B, distanceMeters = 2000.0)))
        val items = if (withWalk) listOf(journey, walk) else listOf(journey)
        return TransitLoadResult.Data(JourneyPage(items, start, ArrivalSource.ClientEstimate), TransitFreshness.Network)
    }

    private class Repository : TransitRepository {
        var arrivalCalls = 0
        override fun cities() = listOf(CITY)
        override fun routes(cityId: CityId) = listOf(TransitRoute(ROUTE, CITY.id, "D1", "Demo Blue", 0xFF0057B8L))

        override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale): TransitLoadResult<ArrivalPage> {
            arrivalCalls++
            fun arrival(vehicle: String?, minutes: Int, route: RouteId = ROUTE) = TransitArrival(
                stopId, route, vehicle?.let { VehicleId("demo:fixture:vehicle:$it") }, null, LocalizedText.fromLegacy("Park"),
                null, null, minutes, realtime = true, cancelled = false, source = ArrivalSource.OfficialRealtime,
            )
            val items = listOf(arrival("v3", 15), arrival(null, 1), arrival("v9", 2, RouteId("demo:fixture:route:other")),
                arrival("v2", 11), arrival("v1", 3))
            return TransitLoadResult.Data(ArrivalPage(items, ArrivalSource.OfficialRealtime, Instant.fromEpochMilliseconds(NOW), false),
                TransitFreshness.Network)
        }

        override suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> {
            val items = listOf("v1", "v2", "v3").mapIndexed { index, id ->
                TransitVehicle(VehicleId("demo:fixture:vehicle:$id"), routeId, null, GeoPoint(41.642 + index * 0.002, 41.632), 90.0,
                    null, null, 0, VehiclePositionKind.Gps)
            }
            return TransitLoadResult.Data(VehiclePage(items, Instant.fromEpochMilliseconds(NOW), 30, false), TransitFreshness.Network)
        }
    }

    private companion object {
        const val NOW = 1_900_000_000_000L
        val A = GeoPoint(41.64, 41.63)
        val B = GeoPoint(41.65, 41.64)
        val ROUTE = RouteId("demo:fixture:route:blue")
        val STOP_A = StopId("demo:fixture:stop:a")
        val STOP_B = StopId("demo:fixture:stop:b")
        val CITY: TransitCity = TransitCity(
            id = CityId("batumi"), name = "Batumi", center = A,
            capabilities = CityCapabilities(stops = false, vehicles = false, arrivals = false, routeShapes = false,
                journeyPlanning = true, routes = true),
        )
        val LIVE_CITY = CITY.copy(capabilities = CITY.capabilities.copy(arrivals = true, vehicles = true))
    }
}
