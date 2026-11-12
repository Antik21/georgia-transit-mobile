@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.presentation.map.Action
import com.denis.georgiatransit.shared.presentation.map.JourneyBusesUi
import com.denis.georgiatransit.shared.presentation.map.JourneyLabelUi
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
    val routeMode: String? = null,
)

/** A highlight label: [key] picks the localized text, the colours come from the shared palette. */
@JsExport
class PlannerLabel(val key: String, val color: String, val textColor: String)

/** One of the soonest buses to the selected option's first boarding stop. */
@JsExport
class PlannerBus(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Double?,
    val etaMinutes: Int,
    val label: String,
    val color: String,
    val textColor: String,
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
    val labels: Array<PlannerLabel> = emptyArray(),
    /** Approximate minutes of the soonest buses; only the selected option has them. */
    val nearestEtas: Array<Int> = emptyArray(),
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
    val side: String?,
    val reason: String?,
    val retryable: Boolean,
    val refreshing: Boolean,
    val stale: Boolean,
    val walkMeters: Int?,
    val walkMinutes: Int?,
    val options: Array<PlannerOption>,
    val route: PlannerRoute,
    val buses: Array<PlannerBus> = emptyArray(),
    /** Frame the route only when this changes; periodic refreshes keep it. */
    val cameraFitRevision: String = "0",
)

/** Planner commands and state for the Map entry; it lives and dies with its [MapBridge]. */
@JsExport
class JourneyPlannerBridge internal constructor(
    private val viewModel: MapViewModel,
    private val scope: CoroutineScope,
    private val isDisposed: () -> Boolean,
) {
    private var subscription: Job? = null
    // Owned by the bridge, not the subscription: a renderer that outlives a re-subscribe must never see a revision twice.
    private var routeRevision = 0L

    fun subscribe(onState: (PlannerState) -> Unit) {
        check(!isDisposed())
        subscription?.cancel()
        val newSubscription = scope.launch(start = CoroutineStart.LAZY) {
            var render: JourneyPlanRenderUi? = null
            var projected: PlannerRoute? = null
            viewModel.container.stateFlow.map { it.journeyPlanner }.distinctUntilChanged().collect { planner ->
                // The renderer redraws lines only when this route object changes identity.
                val route = projected?.takeIf { render == planner.render } ?: planner.render.toJs(++routeRevision)
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
            results.options.map { it.toJs(it.id == results.selectedId, buses) }.toTypedArray()
        } ?: emptyArray(),
        route = route,
        cameraFitRevision = cameraFitRevision.toString(),
        buses = buses?.vehicles.orEmpty().map { bus ->
            PlannerBus(
                id = bus.id.value, latitude = bus.position.latitude, longitude = bus.position.longitude,
                bearing = bus.bearingDegrees, etaMinutes = bus.etaMinutes, label = bus.route.label,
                color = bus.route.backgroundArgb.toCssColor(), textColor = bus.route.textArgb.toCssColor(),
            )
        }.toTypedArray(),
    )
}

private fun JourneyOptionUi.toJs(selected: Boolean, buses: JourneyBusesUi?) = PlannerOption(
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
                waitMinutes = leg.waitMinutes, waitLive = leg.waitIsLive, routeMode = leg.route.mode.name.lowercase(),
            )
        }
    }.toTypedArray(),
    labels = labels.map { it.toJs() }.toTypedArray(),
    nearestEtas = buses?.takeIf { selected && it.optionId == id }?.etaMinutes.orEmpty().toTypedArray(),
)

private fun JourneyLabelUi.toJs() = PlannerLabel(
    key = when (this) {
        JourneyLabelUi.Optimal -> "optimal"
        JourneyLabelUi.Fastest -> "fastest"
        JourneyLabelUi.LeastWalking -> "leastWalking"
        JourneyLabelUi.NoTransfers -> "noTransfers"
    },
    color = backgroundArgb.toCssColor(),
    textColor = textArgb.toCssColor(),
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
