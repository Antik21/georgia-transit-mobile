package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyLabel
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus as Status

class JourneyPlannerReducerTest {
    @Test
    fun longPressNeedsTheTripPlanningCapabilityAndOnlyDropsAPin() {
        val unavailable = JourneyPlannerReducer.longPress(JourneyPlannerUi(), B, context(tripPlanning = false))
        val available = JourneyPlannerReducer.longPress(JourneyPlannerUi(), B, context(location = denied()))

        assertNull(unavailable.state.pin)
        assertEquals(B, available.state.pin)
        assertEquals(false, available.state.isOpen)
        assertNull(available.query)
        assertNull(available.location, "A long press must never ask for location")
    }

    @Test
    fun routeHereDefaultsToMyLocationOnlyWhenAccessIsAlreadyGranted() {
        val pinned = JourneyPlannerUi(pin = B)

        val denied = JourneyPlannerReducer.action(pinned, JourneyPlannerAction.RouteHere, context(location = denied()))
        val granted = JourneyPlannerReducer.action(pinned, JourneyPlannerAction.RouteHere, context(location = fix(A)))

        assertNull(denied.state.origin)
        assertEquals(Status.PickingPoint(JourneyPlanSide.Origin), denied.state.status)
        assertNull(denied.location)
        assertEquals(JourneyEndpoint.CurrentLocation, granted.state.origin)
        assertEquals(Status.Loading, granted.state.status)
        assertEquals(A, assertNotNull(granted.query).from)
        assertEquals(A, granted.currentPoint)
    }

    @Test
    fun pickingAPointByLongPressCompletesThePlanWithRoundedCoordinates() {
        val picking = JourneyPlannerUi(isOpen = true, destination = JourneyEndpoint.MapPoint(B),
            status = Status.PickingPoint(JourneyPlanSide.Origin))

        val step = JourneyPlannerReducer.longPress(picking, GeoPoint(41.640049, 41.630051), context(location = denied()))

        assertEquals(Status.Loading, step.state.status)
        assertEquals(GeoPoint(41.64, 41.6301), assertNotNull(step.query).from)
        assertNull(step.state.pin)
        assertEquals(GeoPoint(41.640049, 41.630051), step.state.render.origin, "Markers keep the exact chosen point")
    }

    @Test
    fun myLocationMovesInsteadOfDuplicatingAndAsksThroughTheExplicitFlow() {
        val open = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.CurrentLocation,
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)

        val step = JourneyPlannerReducer.action(open, JourneyPlannerAction.SetDestinationToMyLocation, context(location = denied()))

        assertNull(step.state.origin)
        assertEquals(JourneyEndpoint.CurrentLocation, step.state.destination)
        assertEquals(Status.PickingPoint(JourneyPlanSide.Origin), step.state.status)
        assertEquals(JourneyLocationRequest.Explicit, step.location)
    }

    @Test
    fun theLocationGateRejectsOldInaccurateAndMissingFixes() {
        val planner = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.CurrentLocation,
            destination = JourneyEndpoint.MapPoint(B), status = Status.Empty)

        assertEquals(Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.PermissionRequired), retry(planner, denied()))
        assertEquals(Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.Locating), retry(planner, fix(A, ageMillis = 120_001)))
        assertEquals(Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.Approximate),
            retry(planner, fix(A, accuracy = 251.0, precision = LocationPrecision.Approximate)))
        assertEquals(Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.Locating),
            retry(planner, LocationState(permission = LocationPermissionState.Granted(LocationPrecision.Precise))))
        assertEquals(Status.Loading, retry(planner, fix(A, ageMillis = 120_000, accuracy = 250.0)))
        // An approximate-only grant still plans when its fix happens to meet the bound.
        assertEquals(Status.Loading, retry(planner, fix(A, accuracy = 200.0, precision = LocationPrecision.Approximate)))
    }

    @Test
    fun pointsCloserThan150MetresAreAlreadyThereWithoutARequest() {
        val planner = JourneyPlannerUi(pin = GeoPoint(A.latitude + 0.0005, A.longitude),
            origin = JourneyEndpoint.MapPoint(A), isOpen = true, status = Status.PickingPoint(JourneyPlanSide.Destination))

        val step = JourneyPlannerReducer.action(planner, JourneyPlannerAction.RouteHere, context(location = denied()))

        assertIs<Status.AlreadyThere>(step.state.status)
        assertNull(step.query)
    }

    @Test
    fun swapExchangesBothSidesUnchangedAndReplans() {
        val planner = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.CurrentLocation,
            destination = JourneyEndpoint.MapPoint(B), status = Status.Empty)

        val step = JourneyPlannerReducer.action(planner, JourneyPlannerAction.Swap, context(location = fix(A)))

        assertEquals(JourneyEndpoint.MapPoint(B), step.state.origin)
        assertEquals(JourneyEndpoint.CurrentLocation, step.state.destination)
        assertEquals(B, assertNotNull(step.query).from)
        assertEquals(A, step.query?.to)
    }

    @Test
    fun resultsKeepTheSelectionAcrossARefreshAndDrawOnlyTheSelectedOption() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)

        val first = JourneyPlannerReducer.outcome(loading, page("one", "two"), context()).state
        val selected = JourneyPlannerReducer.action(first, JourneyPlannerAction.SelectOption(JourneyId("demo:fixture:journey:two")), context()).state
        val refreshing = JourneyPlannerReducer.refresh(selected, context())
        val refreshed = JourneyPlannerReducer.outcome(refreshing.state, page("two", "three"), context()).state
        val failed = JourneyPlannerReducer.outcome(JourneyPlannerReducer.refresh(refreshed, context()).state,
            TransitLoadResult.Failure(TransitFailure.Timeout()), context()).state

        val results = assertIs<Status.Results>(refreshed.status)
        assertTrue(assertIs<Status.Results>(refreshing.state.status).isRefreshing)
        assertNotNull(refreshing.query)
        assertEquals(JourneyId("demo:fixture:journey:two"), results.selectedId)
        assertEquals(listOf(JourneyPlanLineKind.Walk, JourneyPlanLineKind.Transit, JourneyPlanLineKind.Walk),
            refreshed.render.lines.map { it.kind })
        assertEquals(0xFF0057B8L, refreshed.render.lines[1].colorArgb)
        assertTrue(assertIs<Status.Results>(failed.status).isStale, "A failed refresh keeps the answer but marks it stale")
    }

    @Test
    fun onlyANewPlanOrAnotherOptionReframesTheCamera() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)

        val first = JourneyPlannerReducer.outcome(loading, page("one", "two"), context()).state
        val sameOption = JourneyPlannerReducer.action(first, JourneyPlannerAction.SelectOption(JourneyId("demo:fixture:journey:one")), context()).state
        val refreshed = JourneyPlannerReducer.outcome(JourneyPlannerReducer.refresh(first, context()).state, page("one", "three"), context()).state
        val other = JourneyPlannerReducer.action(refreshed, JourneyPlannerAction.SelectOption(JourneyId("demo:fixture:journey:three")), context()).state

        assertEquals(1, first.cameraFitRevision)
        assertEquals(1, sameOption.cameraFitRevision, "re-selecting the shown option keeps the user's camera")
        assertEquals(1, refreshed.cameraFitRevision, "a periodic refresh keeps the user's camera")
        assertEquals(2, other.cameraFitRevision)
    }

    @Test
    fun aRefreshWithNewIdsKeepsTheChosenRouteSequence() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)
        fun page(direct: String, transfer: String) = TransitLoadResult.Data(
            JourneyPage(listOf(journey(direct), journey(transfer, transfer = true)), NOW, ArrivalSource.ClientEstimate),
            TransitFreshness.Network,
        )

        val first = JourneyPlannerReducer.outcome(loading, page("one", "two"), context()).state
        val chosen = JourneyPlannerReducer.action(first, JourneyPlannerAction.SelectOption(JourneyId("demo:fixture:journey:two")), context()).state
        val refreshed = JourneyPlannerReducer.outcome(JourneyPlannerReducer.refresh(chosen, context()).state, page("x", "y"), context()).state

        assertEquals(JourneyId("demo:fixture:journey:y"), assertIs<Status.Results>(refreshed.status).selectedId)
    }

    @Test
    fun closingThePlannerKeepsTheCameraRevisionSoTheNextPlanIsFramed() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)
        val first = JourneyPlannerReducer.outcome(loading, page("one"), context()).state

        val closed = JourneyPlannerReducer.action(first, JourneyPlannerAction.ClosePlanner, context()).state
        val next = JourneyPlannerReducer.outcome(closed.copy(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading), page("two"), context()).state

        assertEquals(first.cameraFitRevision, closed.cameraFitRevision)
        assertTrue(next.cameraFitRevision > first.cameraFitRevision, "a renderer that framed the first plan must frame the next one")
    }

    @Test
    fun outcomesMapEmptyErrorsAndCapabilityLoss() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)
        fun outcome(result: TransitLoadResult<JourneyPage>) = JourneyPlannerReducer.outcome(loading, result, context()).state.status

        assertEquals(Status.Empty, outcome(page()))
        assertEquals(Status.Unavailable, outcome(TransitLoadResult.Failure(TransitFailure.CapabilityUnavailable("off", null))))
        assertEquals(Status.Error(false, JourneyPlanErrorReason.Rejected), outcome(TransitLoadResult.Failure(TransitFailure.InvalidArgument("x", null))))
        assertEquals(Status.Error(true, JourneyPlanErrorReason.Network), outcome(TransitLoadResult.Failure(TransitFailure.Transport("x"))))
        assertEquals(Status.Error(true, JourneyPlanErrorReason.Busy), outcome(TransitLoadResult.Failure(TransitFailure.RateLimited("x", null, null))))
    }

    @Test
    fun leavingTheCityOrLosingTheCapabilityClearsThePlanner() {
        val planner = JourneyPlannerUi(isOpen = true, pin = B, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)

        val left = JourneyPlannerReducer.context(planner, context(), cityChanged = true, lastCurrentPoint = null, wasAvailable = true)
        val disabled = JourneyPlannerReducer.context(planner, context(tripPlanning = false), cityChanged = false,
            lastCurrentPoint = null, wasAvailable = true)

        assertEquals(JourneyPlannerUi(), left.state)
        assertEquals(Status.Unavailable, disabled.state.status)
        assertNull(disabled.state.pin)
    }

    @Test
    fun aMoveOfMoreThan50MetresReplansVisibleResults() {
        val results = JourneyPlannerReducer.outcome(
            JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.CurrentLocation,
                destination = JourneyEndpoint.MapPoint(B), status = Status.Loading),
            page("one"), context(location = fix(A)),
        ).state

        val near = JourneyPlannerReducer.context(results, context(location = fix(GeoPoint(A.latitude + 0.0004, A.longitude))), false, A, true)
        val far = JourneyPlannerReducer.context(results, context(location = fix(GeoPoint(A.latitude + 0.0005, A.longitude))), false, A, true)

        assertNull(near.query)
        assertEquals(GeoPoint(41.6405, 41.63), assertNotNull(far.query).from)
    }

    @Test
    fun transferWalksAreShownOnlyBetweenDifferentStops() {
        val journey = journey("one", transfer = true)
        val options = JourneyPlanUiMapper.options(JourneyPage(listOf(journey, journey("two", transfer = true, sameStop = true)),
            NOW, ArrivalSource.ClientEstimate), routes)

        assertEquals(listOf("Walk", "Ride", "Walk", "Ride", "Walk"), options[0].legs.map { if (it is JourneyLegUi.Walk) "Walk" else "Ride" })
        assertEquals(JourneyWalkRole.Transfer, (options[0].legs[2] as JourneyLegUi.Walk).role)
        assertEquals(listOf("Walk", "Ride", "Ride", "Walk"), options[1].legs.map { if (it is JourneyLegUi.Walk) "Walk" else "Ride" })
        val ride = options[0].legs[1] as JourneyLegUi.Ride
        assertEquals(6, ride.stopCount)
        assertEquals(2, ride.waitMinutes)
        assertTrue(ride.waitIsLive)
        assertEquals("D1", ride.route.label)
        assertEquals(false, (options[0].legs[3] as JourneyLegUi.Ride).waitIsLive)
        assertEquals(JourneyTimingUi.Mixed, options[0].timing)
    }

    @Test
    fun labelsUseTheFixedDisplayOrderAndTheFirstServerOptionIsSelected() {
        val loading = JourneyPlannerUi(isOpen = true, origin = JourneyEndpoint.MapPoint(A),
            destination = JourneyEndpoint.MapPoint(B), status = Status.Loading)
        val labelled = journey("labelled").copy(labels = listOf(JourneyLabel.NoTransfers, JourneyLabel.Optimal))
        val page = TransitLoadResult.Data(JourneyPage(listOf(labelled, journey("plain")), NOW, ArrivalSource.ClientEstimate),
            TransitFreshness.Network)

        val results = assertIs<Status.Results>(JourneyPlannerReducer.outcome(loading, page, context()).state.status)

        assertEquals(JourneyId("demo:fixture:journey:labelled"), results.selectedId)
        assertEquals(listOf(JourneyLabelUi.Optimal, JourneyLabelUi.NoTransfers), results.options[0].labels)
        assertTrue(results.options[1].labels.isEmpty())
        val boarding = assertNotNull(results.options[0].boarding)
        assertEquals(STOP_A to ROUTE, boarding.stopId to boarding.routeId)
    }

    private fun retry(planner: JourneyPlannerUi, location: LocationState) =
        JourneyPlannerReducer.action(planner, JourneyPlannerAction.Retry, context(location = location)).state.status

    private fun context(tripPlanning: Boolean = true, location: LocationState = denied()) = JourneyPlannerContext(
        city = city.copy(capabilities = city.capabilities.copy(journeyPlanning = tripPlanning)),
        location = location,
        nowMillis = NOW_MILLIS,
        locale = TransitLocale.English,
        visible = true,
        routes = routes,
    )

    private fun denied() = LocationState(permission = LocationPermissionState.Denied)

    private fun fix(
        point: GeoPoint, ageMillis: Long = 1_000, accuracy: Double = 20.0, precision: LocationPrecision = LocationPrecision.Precise,
    ) = LocationState(
        permission = LocationPermissionState.Granted(precision),
        fix = UserLocationFix(point, accuracy, precision, NOW_MILLIS - ageMillis, NOW_MILLIS - ageMillis + 120_000),
    )

    private fun page(vararg ids: String): TransitLoadResult<JourneyPage> =
        TransitLoadResult.Data(JourneyPage(ids.map { journey(it) }, NOW, ArrivalSource.ClientEstimate), TransitFreshness.Network)

    private fun journey(id: String, transfer: Boolean = false, sameStop: Boolean = false): TransitJourney {
        val segments = buildList {
            add(walk(0, 4, null, STOP_A))
            add(ride(6, 17, STOP_A, STOP_B, JourneyWaitBasis.LiveVehicle))
            if (transfer) {
                val next = if (sameStop) STOP_B else STOP_C
                if (!sameStop) add(walk(17, 19, STOP_B, next))
                add(ride(22, 30, next, STOP_D, JourneyWaitBasis.HeadwayEstimate))
            }
            add(walk(if (transfer) 30 else 17, if (transfer) 34 else 21, if (transfer) STOP_D else STOP_B, null))
        }
        return TransitJourney(
            id = JourneyId("demo:fixture:journey:$id"),
            departureAt = segments.first().departureAt,
            arrivalAt = segments.last().arrivalAt,
            transfers = if (transfer) 1 else 0,
            legs = emptyList(),
            segments = segments,
        )
    }

    private fun walk(from: Int, to: Int, fromStop: StopId?, toStop: StopId?) = TransitJourneySegment(
        departureAt = at(from), arrivalAt = at(to), mode = JourneySegmentMode.Walk, fromStopId = fromStop, toStopId = toStop,
        fromPosition = A, toPosition = B, distanceMeters = 300.0, geometryApproximate = true,
    )

    private fun ride(from: Int, to: Int, fromStop: StopId, toStop: StopId, basis: JourneyWaitBasis) = TransitJourneySegment(
        departureAt = at(from), arrivalAt = at(to), mode = JourneySegmentMode.Transit, routeId = ROUTE,
        directionId = DirectionId("demo:fixture:direction:blue"), fromStopId = fromStop, toStopId = toStop,
        geometry = "_p~iF~ps|U_ulLnnqC", intermediateStopCount = 5, waitSeconds = 101, waitBasis = basis,
    )

    private fun at(minute: Int) = Instant.fromEpochMilliseconds(NOW.toEpochMilliseconds() + minute * 60_000L)

    private companion object {
        const val NOW_MILLIS = 1_900_000_000_000L
        val NOW = Instant.fromEpochMilliseconds(NOW_MILLIS)
        val A = GeoPoint(41.6400, 41.6300)
        val B = GeoPoint(41.6500, 41.6400)
        val ROUTE = RouteId("demo:fixture:route:blue")
        val STOP_A = StopId("demo:fixture:stop:a")
        val STOP_B = StopId("demo:fixture:stop:b")
        val STOP_C = StopId("demo:fixture:stop:c")
        val STOP_D = StopId("demo:fixture:stop:d")
        val city = TransitCity(
            id = CityId("batumi"), name = "Batumi", center = A,
            capabilities = CityCapabilities(stops = true, vehicles = true, arrivals = true, routeShapes = true, journeyPlanning = true),
        )
        val routes = listOf(TransitRoute(ROUTE, city.id, "D1", "Demo Blue", 0xFF0057B8L))
    }
}
