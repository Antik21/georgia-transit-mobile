@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPlanMode
import com.denis.georgiatransit.shared.presentation.map.Action
import com.denis.georgiatransit.shared.presentation.map.JourneyLegUi
import com.denis.georgiatransit.shared.presentation.map.JourneyOptionUi
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanLineKind
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanRenderUi
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerAction
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerUi
import com.denis.georgiatransit.shared.presentation.map.MapPlatformEvent
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@JsExport
class PlannerPoint(val latitude: Double, val longitude: Double)

/** [kind] is `none`, `myLocation`, or `point`; a point carries its coordinates. */
@JsExport
class PlannerEndpoint(val kind: String, val point: PlannerPoint?)

/** A `walk` leg uses [role], [meters], and [approximate]; a `ride` leg uses the route and wait fields. */
@JsExport
class PlannerLeg(
    val kind: String,
    val minutes: Int,
    val role: String? = null,
    val meters: Int? = null,
    val approximate: Boolean = false,
    val routeId: String? = null,
    val routeLabel: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val stopCount: Int? = null,
    val waitMinutes: Int? = null,
    val waitLive: Boolean = false,
)

@JsExport
class PlannerOption(
    val id: String,
    val totalMinutes: Int,
    val arrivalEpochMillis: Double,
    val transfers: Int,
    val walkingMeters: Int?,
    val timing: String?,
    val selected: Boolean,
    val legs: Array<PlannerLeg>,
)

/** A planned line: `transit` in its route colour, `walk` drawn dashed by the renderer. */
@JsExport
class PlannerLine(val kind: String, val color: String, val points: Array<RoutePoint>)

@JsExport
class PlannerRoute(
    val revision: String,
    val lines: Array<PlannerLine>,
    val origin: PlannerPoint?,
    val destination: PlannerPoint?,
)

/** Display-ready planner state; every time in it is an approximate estimate. */
@JsExport
class PlannerState(
    val open: Boolean,
    val status: String,
    val pin: PlannerPoint?,
    val origin: PlannerEndpoint,
    val destination: PlannerEndpoint,
    val mode: String,
    val side: String?,
    val reason: String?,
    val retryable: Boolean,
    val refreshing: Boolean,
    val stale: Boolean,
    val walkMeters: Int?,
    val walkMinutes: Int?,
    val options: Array<PlannerOption>,
    val route: PlannerRoute,
)

/** Planner commands and state for the Map entry; it lives and dies with its [MapBridge]. */
@JsExport
class JourneyPlannerBridge internal constructor(
    private val viewModel: MapViewModel,
    private val scope: CoroutineScope,
    private val isDisposed: () -> Boolean,
) {
    private var subscription: Job? = null

    fun subscribe(onState: (PlannerState) -> Unit) {
        check(!isDisposed())
        subscription?.cancel()
        val newSubscription = scope.launch(start = CoroutineStart.LAZY) {
            var revision = 0L
            var render: JourneyPlanRenderUi? = null
            var projected: PlannerRoute? = null
            viewModel.container.stateFlow.map { it.journeyPlanner }.distinctUntilChanged().collect { planner ->
                // The renderer redraws lines only when this route object changes identity.
                val route = projected?.takeIf { render == planner.render } ?: planner.render.toJs(++revision)
                render = planner.render
                projected = route
                notifyHost { onState(planner.toJs(route)) }
            }
        }
        subscription = newSubscription
        newSubscription.start()
    }

    fun unsubscribe() {
        subscription?.cancel()
        subscription = null
    }

    fun longPressMap(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        dispatch(Action.MapEventReceived(MapPlatformEvent.MapLongPressed(GeoPoint(latitude, longitude))))
    }

    fun routeHere() = dispatch(JourneyPlannerAction.RouteHere)
    fun routeFromHere() = dispatch(JourneyPlannerAction.RouteFromHere)
    fun dismissPin() = dispatch(JourneyPlannerAction.DismissPin)

    fun useMyLocationFor(side: String) = when (side) {
        "origin" -> dispatch(JourneyPlannerAction.SetOriginToMyLocation)
        "destination" -> dispatch(JourneyPlannerAction.SetDestinationToMyLocation)
        else -> Unit
    }

    fun pickPointFor(side: String) = when (side) {
        "origin" -> dispatch(JourneyPlannerAction.StartPickingOrigin)
        "destination" -> dispatch(JourneyPlannerAction.StartPickingDestination)
        else -> Unit
    }

    fun swap() = dispatch(JourneyPlannerAction.Swap)
    fun clear() = dispatch(JourneyPlannerAction.Clear)
    fun selectOption(id: String) = dispatch(JourneyPlannerAction.SelectOption(JourneyId(id)))

    fun setMode(mode: String) {
        val planMode = when (mode) {
            "fastest" -> JourneyPlanMode.Fastest
            "lessWalking" -> JourneyPlanMode.LessWalking
            "fewerTransfers" -> JourneyPlanMode.FewerTransfers
            else -> return
        }
        dispatch(JourneyPlannerAction.ChangeMode(planMode))
    }

    fun retry() = dispatch(JourneyPlannerAction.Retry)
    fun close() = dispatch(JourneyPlannerAction.ClosePlanner)

    private fun dispatch(action: Action) {
        if (!isDisposed()) viewModel.dispatchAction(action)
    }
}

private fun JourneyPlannerUi.toJs(route: PlannerRoute): PlannerState {
    val status = status
    return PlannerState(
        open = isOpen,
        status = when (status) {
            JourneyPlannerStatus.Idle -> "idle"
            is JourneyPlannerStatus.PickingPoint -> "pickingPoint"
            JourneyPlannerStatus.EndpointsIncomplete -> "endpointsIncomplete"
            JourneyPlannerStatus.Loading -> "loading"
            is JourneyPlannerStatus.Results -> "results"
            is JourneyPlannerStatus.AlreadyThere -> "alreadyThere"
            JourneyPlannerStatus.Empty -> "empty"
            is JourneyPlannerStatus.Error -> "error"
            JourneyPlannerStatus.Unavailable -> "unavailable"
            is JourneyPlannerStatus.LocationNeeded -> "locationNeeded"
        },
        pin = pin?.toPlannerPoint(),
        origin = origin.toJs(),
        destination = destination.toJs(),
        mode = when (mode) {
            JourneyPlanMode.Fastest -> "fastest"
            JourneyPlanMode.LessWalking -> "lessWalking"
            JourneyPlanMode.FewerTransfers -> "fewerTransfers"
        },
        side = when (status) {
            is JourneyPlannerStatus.PickingPoint -> status.side.toJs()
            is JourneyPlannerStatus.LocationNeeded -> status.side.toJs()
            else -> null
        },
        reason = when (status) {
            is JourneyPlannerStatus.LocationNeeded -> status.reason.name.replaceFirstChar { it.lowercase() }
            is JourneyPlannerStatus.Error -> status.reason.name.replaceFirstChar { it.lowercase() }
            else -> null
        },
        retryable = status is JourneyPlannerStatus.Error && status.retryable,
        refreshing = status is JourneyPlannerStatus.Results && status.isRefreshing,
        stale = status is JourneyPlannerStatus.Results && status.isStale,
        walkMeters = (status as? JourneyPlannerStatus.AlreadyThere)?.walkMeters,
        walkMinutes = (status as? JourneyPlannerStatus.AlreadyThere)?.walkMinutes,
        options = (status as? JourneyPlannerStatus.Results)?.let { results ->
            results.options.map { it.toJs(it.id == results.selectedId) }.toTypedArray()
        } ?: emptyArray(),
        route = route,
    )
}

private fun JourneyOptionUi.toJs(selected: Boolean) = PlannerOption(
    id = id.value,
    totalMinutes = totalMinutes,
    arrivalEpochMillis = arrivalAt.toEpochMilliseconds().toDouble(),
    transfers = transfers,
    walkingMeters = walkingMeters,
    timing = timing?.name?.replaceFirstChar { it.lowercase() },
    selected = selected,
    legs = legs.map { leg ->
        when (leg) {
            is JourneyLegUi.Walk -> PlannerLeg(
                kind = "walk", minutes = leg.minutes, role = leg.role.name.replaceFirstChar { it.lowercase() },
                meters = leg.meters, approximate = leg.isApproximate,
            )
            is JourneyLegUi.Ride -> PlannerLeg(
                kind = "ride", minutes = leg.rideMinutes, routeId = leg.route.routeId.value,
                routeLabel = leg.route.label, routeColor = leg.route.backgroundArgb.toCssColor(),
                routeTextColor = leg.route.textArgb.toCssColor(), stopCount = leg.stopCount,
                waitMinutes = leg.waitMinutes, waitLive = leg.waitIsLive,
            )
        }
    }.toTypedArray(),
)

private fun JourneyPlanRenderUi.toJs(revision: Long) = PlannerRoute(
    revision = revision.toString(),
    lines = lines.map { line ->
        PlannerLine(
            kind = if (line.kind == JourneyPlanLineKind.Transit) "transit" else "walk",
            color = line.colorArgb.toCssColor(),
            points = line.points.map { RoutePoint(it.latitude, it.longitude) }.toTypedArray(),
        )
    }.toTypedArray(),
    origin = origin?.toPlannerPoint(),
    destination = destination?.toPlannerPoint(),
)

private fun JourneyEndpoint?.toJs() = when (this) {
    null -> PlannerEndpoint("none", null)
    JourneyEndpoint.CurrentLocation -> PlannerEndpoint("myLocation", null)
    is JourneyEndpoint.MapPoint -> PlannerEndpoint("point", point.toPlannerPoint())
}

private fun JourneyPlanSide.toJs() = if (this == JourneyPlanSide.Origin) "origin" else "destination"

private fun GeoPoint.toPlannerPoint() = PlannerPoint(latitude, longitude)
