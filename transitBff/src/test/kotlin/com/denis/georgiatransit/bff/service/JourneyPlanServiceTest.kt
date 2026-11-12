package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.FakeAdapter
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.RequestRateLimited
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import com.denis.georgiatransit.bff.capabilities
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.planning.GeoBounds
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.planning.PlanningBudgetExceeded
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import com.denis.georgiatransit.bff.text
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private class PlanningAdapter(tripPlanning: Boolean = true) :
    FakeAdapter(City("test", text, GeoPoint(41.7, 44.8), 13.0, capabilities(tripPlanning = tripPlanning))) {
    var ready = true
    var planResult: suspend (PlanningBudget) -> RealtimeJourneys = {
        RealtimeJourneys(emptyList(), ArrivalSource.CLIENT_ESTIMATE, false, Instant.parse("2030-01-01T00:00:00Z"), false)
    }
    val planCalls = AtomicInteger()

    override val serviceBounds = GeoBounds(41.0, 42.0, 44.0, 45.0)
    override val tripPlanningReady: Boolean get() = ready

    override suspend fun planJourney(query: JourneyPlanQuery, budget: PlanningBudget): RealtimeJourneys {
        planCalls.incrementAndGet()
        return planResult(budget)
    }
}

class JourneyPlanServiceTest {
    private val query = JourneyPlanQuery(
        from = GeoPoint(41.7, 44.8),
        to = GeoPoint(41.71, 44.81),
        departureAt = Instant.parse("2030-01-01T00:00:00Z"),
        maxTransfers = 2,
        transferBufferSeconds = 60,
        locale = "en",
    )

    private fun service(adapter: FakeAdapter, budget: kotlin.time.Duration = 300.milliseconds) =
        JourneyPlanService(IntrinsicCapabilitySnapshotSource(ProviderRegistry(listOf(adapter))), planningBudget = budget)

    @Test
    fun `an empty validated plan is returned as a CLIENT_ESTIMATE page`() = runBlocking<Unit> {
        val page = service(PlanningAdapter()).plan("test", query)
        assertEquals(ArrivalSource.CLIENT_ESTIMATE, page.source)
        assertEquals(emptyList(), page.items)
    }

    @Test
    fun `capability off, not ready, or no planner fails closed with 501`() = runBlocking<Unit> {
        assertFailsWith<CapabilityNotAvailable> { service(PlanningAdapter(tripPlanning = false)).plan("test", query) }
        val notReady = PlanningAdapter().also { it.ready = false }
        assertFailsWith<CapabilityNotAvailable> { service(notReady).plan("test", query) }
        assertEquals(0, notReady.planCalls.get())
        assertFailsWith<CapabilityNotAvailable> { service(FakeAdapter()).plan("test", query) }
    }

    @Test
    fun `points outside the service bounds are rejected without echoing them`() = runBlocking<Unit> {
        val failure = assertFailsWith<InvalidArgument> {
            service(PlanningAdapter()).plan("test", query.copy(to = GeoPoint(43.123456, 44.8)))
        }
        assertFalse(failure.message.contains("43.123456"))
    }

    @Test
    fun `a fifth concurrent plan is rejected with 429`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val adapter = PlanningAdapter().apply {
            planResult = {
                entered.incrementAndGet()
                release.await()
                RealtimeJourneys(emptyList(), ArrivalSource.CLIENT_ESTIMATE, false, Instant.parse("2030-01-01T00:00:00Z"), false)
            }
        }
        val service = service(adapter, budget = 5.seconds)
        val running = List(4) { async(kotlinx.coroutines.Dispatchers.Default) { service.plan("test", query) } }
        withTimeout(2_000) { while (entered.get() < 4) delay(5) }

        assertFailsWith<RequestRateLimited> { service.plan("test", query) }
        release.complete(Unit)
        running.awaitAll()
        service.plan("test", query)
    }

    @Test
    fun `exceeding the planning budget maps to 503`() = runBlocking<Unit> {
        val budgetExceeded = PlanningAdapter().apply { planResult = { throw PlanningBudgetExceeded() } }
        assertFailsWith<UpstreamUnavailable> { service(budgetExceeded).plan("test", query) }
        val slow = PlanningAdapter().apply {
            planResult = {
                delay(1_000)
                error("unreachable")
            }
        }
        assertFailsWith<UpstreamUnavailable> { service(slow, budget = 50.milliseconds).plan("test", query) }
    }

    @Test
    fun `an invalid planner response is a 502 rather than a published page`() = runBlocking<Unit> {
        val invalid = PlanningAdapter().apply {
            planResult = {
                RealtimeJourneys(emptyList(), ArrivalSource.SCHEDULE, false, Instant.parse("2030-01-01T00:00:00Z"), false)
            }
        }
        assertFailsWith<UpstreamBadGateway> { service(invalid).plan("test", query) }
    }

    @Test
    fun `city listing hides tripPlanning while the adapter is not ready`() = runBlocking<Unit> {
        val adapter = PlanningAdapter().also { it.ready = false }
        val transit = TransitService(ProviderRegistry(listOf(adapter)), 60, 60, 1)
        assertFalse(transit.cities().single().capabilities.tripPlanning)
        adapter.ready = true
        assertEquals(true, transit.cities().single().capabilities.tripPlanning)
        transit.close()
    }
}
