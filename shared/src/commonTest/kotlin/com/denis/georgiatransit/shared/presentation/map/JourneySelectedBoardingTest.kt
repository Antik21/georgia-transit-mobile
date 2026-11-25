package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus as Status

class JourneySelectedBoardingTest {
    @Test
    fun movingKeepsTheExpandedVehicleThroughEmptyAndReplacementPlansUntilItPasses() {
        val vehicle = VehicleId("demo:fixture:vehicle:one")
        val segment = TransitJourneySegment(NOW, NOW, JourneySegmentMode.Transit, routeId = RouteId("route"),
            fromStopId = StopId("boarding"), toStopId = StopId("destination"), fromPosition = A,
            vehicleId = vehicle, waitBasis = JourneyWaitBasis.LiveVehicle)
        val live = TransitJourney(JourneyId("chosen"), NOW, NOW, 0, emptyList(), listOf(segment))
        val loading = JourneyPlannerUi(isOpen = true, showingResults = true, origin = JourneyEndpoint.CurrentLocation,
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)
        val page = TransitLoadResult.Data(JourneyPage(listOf(live), NOW), TransitFreshness.Network)
        val start = GeoPoint(A.latitude + 0.0003, A.longitude)
        val far = JourneyPlannerReducer.outcome(loading, page, context(start)).state
        val near = context(A)
        val movement = JourneyPlannerReducer.context(far, near, cityChanged = false, lastCurrentPoint = start, wasAvailable = true)
        assertNull(movement.query)
        val retained = JourneyPlannerReducer.outcome(movement.state, TransitLoadResult.Empty(TransitFreshness.Network), near).state
        assertEquals(vehicle, retained.selectedOption()?.boarding?.plannedVehicleId)
        assertEquals(0, (retained.selectedOption()!!.legs.first() as JourneyLegUi.Walk).minutes)
        assertTrue((far.selectedOption()!!.legs.first() as JourneyLegUi.Walk).minutes > 0)
        val other = live.copy(id = JourneyId("other"), segments = live.segments.map {
            if (it.vehicleId == vehicle) it.copy(vehicleId = VehicleId("demo:fixture:vehicle:two")) else it })
        val updated = JourneyPlannerReducer.outcome(retained, page.copy(value = JourneyPage(listOf(other), NOW)), near).state
        val selected = updated.selectedOption()!!
        val key = JourneyBusKey(near.city!!.id, selected.id, selected.boarding!!, TransitLocale.English, false)
        val noEvidence = JourneyBusesUpdate(key, 1, emptyList(), emptyMap())
        assertEquals(selected, JourneyPlannerReducer.buses(updated, noEvidence, near)?.state?.selectedOption())
        val switched = JourneyPlannerReducer.action(updated, JourneyPlannerAction.SelectOption(JourneyId("other")), near)
        assertEquals(1, assertIs<Status.Results>(switched.state.status).options.size)
        val passed = JourneyPlannerReducer.buses(updated, noEvidence.copy(passedAt = NOW), near)!!
        assertEquals(JourneyId("other"), passed.state.selectedOption()?.id)
        assertEquals(listOf(other.id), assertIs<Status.Results>(JourneyPlannerReducer.outcome(passed.state, page, near).state.status).options.map { it.id })
    }

    @Test
    fun updatingAccessAtTheStopPreservesTheEgressWhenTheRideHasNoGeometry() {
        val end = GeoPoint(B.latitude + 0.001, B.longitude)
        val ride = TransitJourneySegment(NOW, NOW, JourneySegmentMode.Transit, routeId = RouteId("route"),
            fromStopId = StopId("boarding"), toStopId = StopId("exit"), fromPosition = A,
            vehicleId = VehicleId("bus"), waitBasis = JourneyWaitBasis.LiveVehicle)
        val egress = TransitJourneySegment(NOW, NOW, JourneySegmentMode.Walk, fromPosition = B, toPosition = end)
        val journey = TransitJourney(JourneyId("chosen"), NOW, NOW, 0, emptyList(), listOf(ride, egress))
        val option = JourneyPlanUiMapper.options(JourneyPage(listOf(journey), NOW), emptyList()).single()
        assertEquals(listOf(B, end), option.lines.single().points)
        val approaching = option.withCurrentAccessWalk(end)
        assertEquals(listOf(true, false), approaching.lines.map { it.isAccessWalk })
        val atStop = approaching.withCurrentAccessWalk(A).withCurrentAccessWalk(A)
        assertEquals(option.lines, atStop.lines)
    }

    private val NOW = Instant.fromEpochMilliseconds(1_900_000_000_000L)
    private val A = GeoPoint(41.64, 41.63)
    private val B = GeoPoint(41.65, 41.64)

    private fun context(point: GeoPoint): JourneyPlannerContext {
        val now = NOW.toEpochMilliseconds()
        val location = LocationState(
            permission = LocationPermissionState.Granted(LocationPrecision.Precise),
            fix = UserLocationFix(point, 20.0, LocationPrecision.Precise, now, now + 120_000),
        )
        val city = TransitCity(id = CityId("batumi"), name = "Batumi", center = A,
            capabilities = CityCapabilities(stops = true, vehicles = true, arrivals = true, routeShapes = true, journeyPlanning = true))
        return JourneyPlannerContext(city, location, now, TransitLocale.English, visible = true, routes = emptyList())
    }
}
