package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpointPolicy
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.JourneyRequest
import com.denis.georgiatransit.shared.domain.model.JourneyResolution
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.distanceMetersTo
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus as Status

internal data class JourneyPlannerContext(
    val city: TransitCity?,
    val location: LocationState,
    val nowMillis: Long,
    val locale: TransitLocale,
    val visible: Boolean,
    val routes: List<TransitRoute>,
) {
    val available: Boolean get() = city?.capabilities?.tripPlanning == true
}

/** [Explicit] reuses the "My location" command flow; [Refresh] only re-reads an already granted fix. */
internal enum class JourneyLocationRequest { Explicit, Refresh }

/** [currentPoint] is the gated device fix used by [query], kept for the moved-50-m rule. */
internal data class JourneyPlannerStep(
    val state: JourneyPlannerUi,
    val query: JourneyPlanQuery? = null,
    val currentPoint: GeoPoint? = null,
    val location: JourneyLocationRequest? = null,
)

/** Pure planner transitions; no I/O, clocks, or jobs. */
internal object JourneyPlannerReducer {
    fun longPress(state: JourneyPlannerUi, point: GeoPoint, context: JourneyPlannerContext): JourneyPlannerStep {
        if (!context.available) return finish(state.copy(pin = null), context)
        val status = state.status
        if (state.isOpen && status is Status.PickingPoint) {
            return evaluate(state.with(status.side, JourneyEndpoint.MapPoint(point)).copy(pin = null), context)
        }
        return finish(state.copy(pin = point), context)
    }

    fun action(state: JourneyPlannerUi, action: JourneyPlannerAction, context: JourneyPlannerContext): JourneyPlannerStep =
        when (action) {
            JourneyPlannerAction.RouteHere -> state.pin?.let { pin ->
                val origin = state.origin.takeIf { state.isOpen }
                    ?: JourneyEndpoint.CurrentLocation.takeIf { context.location.permission is LocationPermissionState.Granted }
                evaluate(state.copy(pin = null, origin = origin, destination = JourneyEndpoint.MapPoint(pin)), context)
            } ?: finish(state, context)
            JourneyPlannerAction.RouteFromHere -> state.pin?.let { pin ->
                val destination = state.destination.takeIf { state.isOpen }
                evaluate(state.copy(pin = null, origin = JourneyEndpoint.MapPoint(pin), destination = destination), context)
            } ?: finish(state, context)
            JourneyPlannerAction.DismissPin -> finish(state.copy(pin = null), context)
            JourneyPlannerAction.SetOriginToMyLocation -> myLocation(state, JourneyPlanSide.Origin, context)
            JourneyPlannerAction.SetDestinationToMyLocation -> myLocation(state, JourneyPlanSide.Destination, context)
            JourneyPlannerAction.StartPickingOrigin -> picking(state, JourneyPlanSide.Origin, context)
            JourneyPlannerAction.StartPickingDestination -> picking(state, JourneyPlanSide.Destination, context)
            JourneyPlannerAction.Swap -> if (!state.isOpen) finish(state, context) else {
                evaluate(state.copy(origin = state.destination, destination = state.origin), context)
            }
            JourneyPlannerAction.Clear -> if (!state.isOpen) finish(state, context) else {
                finish(state.copy(pin = null, origin = null, destination = null, status = Status.EndpointsIncomplete), context)
            }
            is JourneyPlannerAction.SelectOption -> select(state, action, context)
            JourneyPlannerAction.Retry -> retry(state, context)
            JourneyPlannerAction.ClosePlanner -> finish(JourneyPlannerUi(), context)
        }

    /** City, capability, or location changes. Leaving a city drops endpoints and results entirely. */
    fun context(
        state: JourneyPlannerUi, context: JourneyPlannerContext, cityChanged: Boolean,
        lastCurrentPoint: GeoPoint?, wasAvailable: Boolean,
    ): JourneyPlannerStep {
        if (cityChanged) return finish(JourneyPlannerUi(), context)
        val updated = state.copy(pin = state.pin.takeIf { context.available })
        if (!updated.isOpen) return finish(updated, context)
        if (!context.available) return finish(updated.copy(status = Status.Unavailable), context)
        val status = updated.status
        val point = (JourneyLocationGate.check(context.location, context.nowMillis) as? JourneyLocationGate.Pass)?.point
        return when {
            !wasAvailable -> evaluate(updated, context)
            status is Status.LocationNeeded && point != null -> evaluate(updated, context)
            status is Status.Results && point != null && updated.usesCurrentLocation &&
                lastCurrentPoint != null && point.distanceMetersTo(lastCurrentPoint) > REPLAN_MOVED_METERS ->
                refresh(updated, context)
            else -> finish(updated, context)
        }
    }

    fun visibility(state: JourneyPlannerUi, context: JourneyPlannerContext): JourneyPlannerStep = when {
        !context.visible -> finish(state, context)
        state.status == Status.Loading -> evaluate(state, context)
        state.status is Status.Results -> refresh(state, context)
        else -> finish(state, context)
    }

    /** Periodic or moved re-plan: the visible answer stays until the new one replaces it. */
    fun refresh(state: JourneyPlannerUi, context: JourneyPlannerContext): JourneyPlannerStep {
        val status = state.status as? Status.Results ?: return finish(state, context)
        if (!context.available) return finish(state.copy(status = Status.Unavailable), context)
        val gate = JourneyLocationGate.check(context.location, context.nowMillis)
        val point = (gate as? JourneyLocationGate.Pass)?.point
        if (state.usesCurrentLocation && point == null) return finish(state.copy(status = status.copy(isStale = true)), context)
        return when (val resolution = resolve(state, point, context)) {
            is JourneyResolution.Ready -> finish(
                state.copy(status = status.copy(isRefreshing = true)), context, resolution.query,
                point.takeIf { state.usesCurrentLocation },
            )
            is JourneyResolution.AlreadyThere ->
                finish(state.copy(status = Status.AlreadyThere(resolution.walkMeters, resolution.walkMinutes)), context)
            else -> finish(state, context)
        }
    }

    fun outcome(
        state: JourneyPlannerUi, result: TransitLoadResult<JourneyPage>, context: JourneyPlannerContext,
    ): JourneyPlannerStep {
        val previous = state.status
        if (previous != Status.Loading && previous !is Status.Results) return finish(state, context)
        val next = when (result) {
            is TransitLoadResult.Data -> {
                val options = JourneyPlanUiMapper.options(result.value, context.routes)
                if (options.isEmpty()) Status.Empty else Status.Results(
                    options = options,
                    selectedId = (previous as? Status.Results)?.selectedId?.takeIf { id -> options.any { it.id == id } }
                        ?: options.first().id,
                )
            }
            is TransitLoadResult.Empty -> Status.Empty
            is TransitLoadResult.Failure -> {
                val failed = result.error.toPlannerStatus()
                if (previous is Status.Results && failed is Status.Error) {
                    previous.copy(isRefreshing = false, isStale = true)
                } else failed
            }
        }
        return finish(state.copy(status = next), context)
    }

    private fun evaluate(
        candidate: JourneyPlannerUi, context: JourneyPlannerContext, explicit: Boolean = false,
    ): JourneyPlannerStep {
        val state = candidate.copy(isOpen = true)
        if (!context.available) return finish(state.copy(status = Status.Unavailable), context)
        val origin = state.origin
        val destination = state.destination
        val gate = JourneyLocationGate.check(context.location, context.nowMillis)
        val point = (gate as? JourneyLocationGate.Pass)?.point
        val locationRequest = when {
            !state.usesCurrentLocation || point != null -> null
            explicit -> JourneyLocationRequest.Explicit
            (gate as? JourneyLocationGate.Needed)?.reason == JourneyLocationReason.Locating -> JourneyLocationRequest.Refresh
            else -> null
        }
        if (origin == null && destination == null) return finish(state.copy(status = Status.EndpointsIncomplete), context)
        if (origin == null || destination == null) {
            val missing = if (origin == null) JourneyPlanSide.Origin else JourneyPlanSide.Destination
            return finish(state.copy(status = Status.PickingPoint(missing)), context, location = locationRequest)
        }
        if (state.usesCurrentLocation && gate is JourneyLocationGate.Needed) {
            val side = if (origin == JourneyEndpoint.CurrentLocation) JourneyPlanSide.Origin else JourneyPlanSide.Destination
            return finish(state.copy(status = Status.LocationNeeded(side, gate.reason)), context, location = locationRequest)
        }
        return when (val resolution = resolve(state, point, context)) {
            is JourneyResolution.Ready -> finish(
                state.copy(status = Status.Loading), context, resolution.query, point.takeIf { state.usesCurrentLocation },
            )
            is JourneyResolution.AlreadyThere ->
                finish(state.copy(status = Status.AlreadyThere(resolution.walkMeters, resolution.walkMinutes)), context)
            JourneyResolution.NeedsCurrentLocation -> finish(
                state.copy(status = Status.LocationNeeded(JourneyPlanSide.Origin, JourneyLocationReason.Locating)), context,
            )
            JourneyResolution.Invalid ->
                finish(state.copy(status = Status.Error(retryable = false, JourneyPlanErrorReason.Rejected)), context)
        }
    }

    private fun resolve(state: JourneyPlannerUi, point: GeoPoint?, context: JourneyPlannerContext): JourneyResolution {
        val request = JourneyRequest(state.origin ?: return JourneyResolution.Invalid,
            state.destination ?: return JourneyResolution.Invalid)
        return JourneyEndpointPolicy.resolve(request, point, context.locale)
    }

    private fun myLocation(state: JourneyPlannerUi, side: JourneyPlanSide, context: JourneyPlannerContext): JourneyPlannerStep {
        if (!state.isOpen) return finish(state, context)
        val other = if (side == JourneyPlanSide.Origin) JourneyPlanSide.Destination else JourneyPlanSide.Origin
        var next = state.with(side, JourneyEndpoint.CurrentLocation)
        // At most one side may be "My location" (ADR 0023): choosing it moves it rather than duplicating it.
        if (next.endpoint(other) == JourneyEndpoint.CurrentLocation) next = next.with(other, null)
        return evaluate(next, context, explicit = true)
    }

    private fun picking(state: JourneyPlannerUi, side: JourneyPlanSide, context: JourneyPlannerContext): JourneyPlannerStep =
        if (!state.isOpen) finish(state, context) else finish(state.copy(pin = null, status = Status.PickingPoint(side)), context)

    private fun select(
        state: JourneyPlannerUi, action: JourneyPlannerAction.SelectOption, context: JourneyPlannerContext,
    ): JourneyPlannerStep {
        val status = state.status as? Status.Results ?: return finish(state, context)
        if (status.options.none { it.id == action.id }) return finish(state, context)
        return finish(state.copy(status = status.copy(selectedId = action.id)), context)
    }

    private fun retry(state: JourneyPlannerUi, context: JourneyPlannerContext): JourneyPlannerStep = when (val status = state.status) {
        is Status.Results -> refresh(state, context)
        is Status.Error, Status.Empty, Status.Unavailable -> if (state.isOpen) evaluate(state, context) else finish(state, context)
        is Status.LocationNeeded -> evaluate(state, context, explicit = status.reason != JourneyLocationReason.Locating)
        else -> finish(state, context)
    }

    /** Null when [update] no longer matches the selected option, e.g. after a late poll. */
    fun buses(state: JourneyPlannerUi, update: JourneyBusesUpdate, context: JourneyPlannerContext): JourneyPlannerStep? {
        val selected = state.selectedOption()?.takeIf { it.id == update.key.optionId && it.boarding == update.key.boarding }
            ?: return null
        return finish(state.copy(buses = JourneyBusesProjector.project(selected, update)), context)
    }

    private fun finish(
        state: JourneyPlannerUi, context: JourneyPlannerContext, query: JourneyPlanQuery? = null,
        currentPoint: GeoPoint? = null, location: JourneyLocationRequest? = null,
    ): JourneyPlannerStep {
        val selected = state.selectedOption()
        val buses = state.buses?.takeIf { it.optionId == selected?.id && it.boarding == selected.boarding }
        return JourneyPlannerStep(state.copy(render = render(state, context), buses = buses), query, currentPoint, location)
    }


    private fun render(state: JourneyPlannerUi, context: JourneyPlannerContext): JourneyPlanRenderUi {
        if (!state.isOpen) return JourneyPlanRenderUi()
        val current = (JourneyLocationGate.check(context.location, context.nowMillis) as? JourneyLocationGate.Pass)?.point
        val status = state.status as? Status.Results
        return JourneyPlanRenderUi(
            lines = status?.options?.firstOrNull { it.id == status.selectedId }?.lines ?: JourneyPlanRenderUi().lines,
            origin = state.origin.pointOrNull(current),
            destination = state.destination.pointOrNull(current),
        )
    }

    private fun JourneyEndpoint?.pointOrNull(current: GeoPoint?): GeoPoint? = when (this) {
        JourneyEndpoint.CurrentLocation -> current
        is JourneyEndpoint.MapPoint -> point
        null -> null
    }

    private fun JourneyPlannerUi.endpoint(side: JourneyPlanSide) = if (side == JourneyPlanSide.Origin) origin else destination

    private fun JourneyPlannerUi.with(side: JourneyPlanSide, endpoint: JourneyEndpoint?) =
        if (side == JourneyPlanSide.Origin) copy(origin = endpoint) else copy(destination = endpoint)

    private val JourneyPlannerUi.usesCurrentLocation: Boolean
        get() = origin == JourneyEndpoint.CurrentLocation || destination == JourneyEndpoint.CurrentLocation

    private fun TransitFailure.toPlannerStatus(): Status = when (this) {
        is TransitFailure.CapabilityUnavailable, is TransitFailure.CityNotFound, is TransitFailure.Configuration -> Status.Unavailable
        is TransitFailure.InvalidArgument -> Status.Error(retryable = false, JourneyPlanErrorReason.Rejected)
        is TransitFailure.RateLimited -> Status.Error(retryable = true, JourneyPlanErrorReason.Busy)
        is TransitFailure.Transport, is TransitFailure.Timeout -> Status.Error(retryable = true, JourneyPlanErrorReason.Network)
        else -> Status.Error(retryable = true, JourneyPlanErrorReason.Service)
    }

    private const val REPLAN_MOVED_METERS = 50.0
}

internal fun JourneyPlannerUi.selectedOption(): JourneyOptionUi? =
    (status as? Status.Results)?.let { results -> results.options.firstOrNull { it.id == results.selectedId } }
