package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.RequestRateLimited
import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import com.denis.georgiatransit.bff.control.CapabilitySnapshotSource
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.ProviderOutcome
import com.denis.georgiatransit.bff.observability.ProviderTelemetryLabels
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.observability.TelemetryOperation
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.planning.PlanningBudgetExceeded
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyPlanResponseValidator
import com.denis.georgiatransit.bff.provider.ProviderFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val DefaultMaximumConcurrentPlans = 4
private val DefaultPlanningBudget = 300.milliseconds

/**
 * `POST /journeys/plan` orchestration (ADR 0022, ADR 0023). Coordinates stay request-scoped: no
 * cache, SingleFlight, LKG, coordinate-derived key, or coordinate-bearing telemetry exists here.
 */
class JourneyPlanService(
    private val capabilitySnapshots: CapabilitySnapshotSource,
    private val observability: BffObservability? = null,
    maximumConcurrentPlans: Int = DefaultMaximumConcurrentPlans,
    private val planningBudget: Duration = DefaultPlanningBudget,
) {
    private val permits = Semaphore(maximumConcurrentPlans)

    suspend fun plan(cityId: String, query: JourneyPlanQuery): JourneyPage {
        val effectiveCity = capabilitySnapshots.current().city(cityId)
        val adapter = effectiveCity.adapter
        if (!effectiveCity.city.capabilities.tripPlanning || !adapter.tripPlanningReady) {
            throw CapabilityNotAvailable("Trip planning is not available for city '$cityId'")
        }
        val bounds = adapter.serviceBounds ?: throw CapabilityNotAvailable("Trip planning is not available for city '$cityId'")
        if (!bounds.contains(query.from) || !bounds.contains(query.to)) {
            throw InvalidArgument("from and to must be inside the city service area")
        }
        if (!permits.tryAcquire()) throw RequestRateLimited("Journey planning is at capacity", retryAfterSeconds = 1)
        try {
            return planWithinBudget(cityId, effectiveCity.adapter, query)
        } finally {
            permits.release()
        }
    }

    private suspend fun planWithinBudget(
        cityId: String,
        adapter: CityTransitProviderAdapter,
        query: JourneyPlanQuery,
    ): JourneyPage {
        val labels = ProviderTelemetryLabels(
            city = cityId,
            provider = adapter.telemetryProvider,
            capability = TelemetryCapability.TRIP_PLANNING,
            operation = TelemetryOperation.JOURNEY_PLAN,
        )
        val startedAt = System.nanoTime()
        try {
            val result = withTimeout(planningBudget) {
                withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    adapter.planJourney(
                        query,
                        PlanningBudget(startedAt + planningBudget.inWholeNanoseconds) { !context.isActive },
                    )
                }
            }
            val page = JourneyPage(
                items = result.items,
                observedAt = result.observedAt.toString(),
                source = result.source,
                realtime = result.realtime,
                stale = result.stale,
            )
            JourneyPlanResponseValidator.page(cityId, page)
            record(labels, ProviderOutcome.SUCCESS, startedAt)
            observability?.recordDataResult(labels, result.observedAt, page.stale, page.items.size, page.realtime, probe = false)
            return page
        } catch (_: TimeoutCancellationException) {
            throw budgetExceeded(labels, startedAt)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: PlanningBudgetExceeded) {
            throw budgetExceeded(labels, startedAt)
        } catch (failure: ProviderFailure) {
            record(labels, failure.telemetryOutcome(), startedAt)
            throw failure.toServiceFailure()
        }
    }

    private fun budgetExceeded(labels: ProviderTelemetryLabels, startedAt: Long): UpstreamUnavailable {
        record(labels, ProviderOutcome.TIMEOUT, startedAt)
        return UpstreamUnavailable("Journey planning exceeded its time budget", retryAfterSeconds = 1)
    }

    private fun record(labels: ProviderTelemetryLabels, outcome: ProviderOutcome, startedAt: Long) {
        observability?.recordProviderResult(labels, outcome, System.nanoTime() - startedAt)
    }
}
