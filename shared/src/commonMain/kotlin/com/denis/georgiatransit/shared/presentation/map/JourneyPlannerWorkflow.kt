package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.LocationFreshnessClock
import com.denis.georgiatransit.shared.presentation.location.LocationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class JourneyPlanKey(val generation: Long, val cityId: CityId)

internal data class JourneyPlanOutcome(val key: JourneyPlanKey, val result: TransitLoadResult<JourneyPage>)

internal sealed interface JourneyPlannerEvent {
    data class Planned(val outcome: JourneyPlanOutcome) : JourneyPlannerEvent
    data object RefreshDue : JourneyPlannerEvent
    data class Buses(val update: JourneyBusesUpdate) : JourneyPlannerEvent
}

/**
 * Sole owner of the planner request, its generation, and the periodic re-plan ticker.
 * Latest wins: every launch or cancel advances the generation, so a late answer is never applied.
 */
internal class JourneyPlannerWorkflow(
    private val repository: JourneyPlanRepository,
    private val catalog: TransitRepository,
    private val clock: LocationFreshnessClock,
    vehicleIntervalMillis: Long,
    private val refreshIntervalMillis: Long = JOURNEY_REFRESH_INTERVAL_MILLIS,
) {
    private val buses = JourneyBusesWorkflow(catalog, vehicleIntervalMillis)
    private var generation = 0L
    private var requestJob: Job? = null
    private var refreshJob: Job? = null
    private var lastCurrentPoint: GeoPoint? = null
    private var wasAvailable = false

    /** Reads the live catalogue snapshot, which a plan with unknown routes may just have refreshed. */
    fun context(city: TransitCity?, location: LocationState, locale: TransitLocale, visible: Boolean) = JourneyPlannerContext(
        city, location, clock.nowEpochMillis(), locale, visible,
        city?.takeIf { it.capabilities.routes }?.let { catalog.routes(it.id).filter { route -> route.cityId == it.id } }.orEmpty(),
    )

    fun onLongPress(state: JourneyPlannerUi, point: GeoPoint, context: JourneyPlannerContext) =
        JourneyPlannerReducer.longPress(state, point, context)

    fun onAction(state: JourneyPlannerUi, action: JourneyPlannerAction, context: JourneyPlannerContext) =
        JourneyPlannerReducer.action(state, action, context)

    /** Only a capability that comes back re-plans; a BFF 501 under an enabled capability must not loop. */
    fun onContext(state: JourneyPlannerUi, context: JourneyPlannerContext, cityChanged: Boolean): JourneyPlannerStep {
        val step = JourneyPlannerReducer.context(state, context, cityChanged, lastCurrentPoint, wasAvailable || cityChanged)
        wasAvailable = context.available
        return step
    }

    fun onVisibility(state: JourneyPlannerUi, context: JourneyPlannerContext) = JourneyPlannerReducer.visibility(state, context)

    /** Checked inside the scheduled intent: cancellation alone cannot stop a non-cooperative client. */
    fun onEvent(state: JourneyPlannerUi, event: JourneyPlannerEvent, context: JourneyPlannerContext): JourneyPlannerStep? =
        when (event) {
            is JourneyPlannerEvent.Planned -> event.outcome.takeIf { isCurrent(it, context.city) }
                ?.let { JourneyPlannerReducer.outcome(state, it.result, context) }
            JourneyPlannerEvent.RefreshDue -> JourneyPlannerReducer.refresh(state, context)
            is JourneyPlannerEvent.Buses -> event.update.takeIf(buses::isCurrent)
                ?.let { JourneyPlannerReducer.buses(state, it, context) }
        }

    private fun isCurrent(outcome: JourneyPlanOutcome, city: TransitCity?): Boolean =
        outcome.key.generation == generation && city?.id == outcome.key.cityId && city.capabilities.tripPlanning

    fun execute(scope: CoroutineScope, step: JourneyPlannerStep, context: JourneyPlannerContext, onEvent: (JourneyPlannerEvent) -> Unit) {
        val status = step.state.status
        val city = context.city
        when {
            city == null || !context.visible || !context.available -> cancel()
            step.query != null -> launch(scope, context, step.query, step.currentPoint) { onEvent(JourneyPlannerEvent.Planned(it)) }
            status is JourneyPlannerStatus.Results && !status.isRefreshing ->
                scheduleRefresh(scope) { onEvent(JourneyPlannerEvent.RefreshDue) }
            status != JourneyPlannerStatus.Loading && status !is JourneyPlannerStatus.Results -> cancel()
        }
        buses.sync(scope, busKey(step.state, context)) { onEvent(JourneyPlannerEvent.Buses(it)) }
    }

    /** Buses are looked up only for a visible, selected option that boards transit, under the arrivals capability. */
    private fun busKey(state: JourneyPlannerUi, context: JourneyPlannerContext): JourneyBusKey? {
        val city = context.city?.takeIf { context.visible && context.available && it.capabilities.arrivals } ?: return null
        val option = state.selectedOption() ?: return null
        val boarding = option.boarding ?: return null
        return JourneyBusKey(city.id, option.id, boarding, context.locale, city.capabilities.vehiclePositions)
    }

    fun cancel() {
        generation++
        requestJob?.cancel()
        refreshJob?.cancel()
        requestJob = null
        refreshJob = null
    }

    private fun launch(
        scope: CoroutineScope, context: JourneyPlannerContext, query: JourneyPlanQuery, currentPoint: GeoPoint?,
        onOutcome: (JourneyPlanOutcome) -> Unit,
    ) {
        val city = checkNotNull(context.city)
        cancel()
        val key = JourneyPlanKey(generation, city.id)
        lastCurrentPoint = currentPoint
        requestJob = scope.launch {
            val result = guarded { repository.plan(city.id, query) }
            val known = context.routes.mapTo(mutableSetOf()) { it.id }
            val unknownRoute = (result as? TransitLoadResult.Data)?.value?.items.orEmpty()
                .any { journey -> journey.segments.any { it.routeId != null && it.routeId !in known } }
            // Route badges and line colours come from the catalogue, which a fresh map may not have loaded yet.
            if (unknownRoute && city.capabilities.routes) guarded { catalog.refreshRoutes(RouteListRequest(city.id, context.locale)) }
            if (key.generation == generation) onOutcome(JourneyPlanOutcome(key, result))
        }
    }

    private suspend fun <T> guarded(block: suspend () -> TransitLoadResult<T>): TransitLoadResult<T> = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        TransitLoadResult.Failure(TransitFailure.Transport("Journey planning failed"))
    }

    private fun scheduleRefresh(scope: CoroutineScope, onRefreshDue: () -> Unit) {
        if (refreshJob?.isActive == true) return
        val scheduled = generation
        refreshJob = scope.launch {
            delay(refreshIntervalMillis)
            if (scheduled == generation) onRefreshDue()
        }
    }
}

/** ADR 0022 ETA refresh cadence for a visible plan. */
internal const val JOURNEY_REFRESH_INTERVAL_MILLIS = 60_000L
