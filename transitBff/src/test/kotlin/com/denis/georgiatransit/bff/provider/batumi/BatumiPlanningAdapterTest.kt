package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.planning.JourneyPlanMode
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.provider.BatumiAllBusesClient
import com.denis.georgiatransit.bff.provider.BatumiThetaCatalogClient
import com.denis.georgiatransit.bff.provider.BatumiThetaTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.service.TransitService
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BatumiPlanningAdapterTest {
    private val line = eastLine("A", latitude = 41.640, count = 10)
    private val catalogPayload = catalogJson(listOf(FixtureRoute("1", line)))

    private val activation = BffConfig.fromEnvironment(
        mapOf(
            "BATUMI_THETA_ENABLED" to "true",
            "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to "I_APPROVE_THETA_PRODUCTION_USE",
        ),
    ).batumiTheta

    private class StubCatalogClient(private val payload: String) : BatumiThetaCatalogClient {
        override suspend fun getDbData(): String = payload
        override suspend fun getBusLocsOnRoute(rawRouteId: String): String = "{\"data\":null}"
        override fun close() = Unit
    }

    private class StubAllBusesClient(private val payload: String) : BatumiAllBusesClient {
        override suspend fun getAllBuses(): String = payload
        override fun close() = Unit
    }

    @Test
    fun `legacy GET journeys stays 501 for Batumi even with tripPlanning enabled`() = runBlocking<Unit> {
        BatumiThetaTransitProviderAdapter(activation, StubCatalogClient(catalogPayload)).use { adapter ->
            assertTrue(adapter.city.capabilities.tripPlanning, "the intrinsic capability lets the control document enable it")
            val service = TransitService(ProviderRegistry(listOf(adapter)), 60, 60, 1)
            val query = JourneyQuery(GeoPoint(41.64, 41.60), GeoPoint(41.64, 41.63), Instant.now(), "en", 1)

            assertFailsWith<CapabilityNotAvailable> { service.journeys("batumi", query) }
            assertFalse(service.cities().single().capabilities.tripPlanning, "no live snapshot yet")
            service.close()
        }
    }

    @Test
    fun `the global feed makes planning ready and plans from the published graph`() = runBlocking<Unit> {
        val vehicle = line[0]
        val allBuses = "{\"data\":{\"1\":[{\"Name\":\"bus-1\",\"Lat\":${vehicle.latitude},\"Lon\":${vehicle.longitude},\"Status\":1}]}}"
        BatumiThetaTransitProviderAdapter(
            activation = activation,
            client = StubCatalogClient(catalogPayload),
            allBusesClient = StubAllBusesClient(allBuses),
            cityFeedPollInterval = Duration.ofMillis(50),
        ).use { adapter ->
            assertFalse(adapter.tripPlanningReady)
            adapter.startGlobalFeed(enabled = { true })
            withTimeout(5_000) { while (!adapter.tripPlanningReady) delay(10) }

            val query = JourneyPlanQuery(near(line[2]), near(line[8]), Instant.now(), 2, JourneyPlanMode.FASTEST, 60, "en")
            val page = adapter.planJourney(query, PlanningBudget.unbounded())

            val ride = page.items.single().transitSegments.single()
            assertEquals(adapter.city.id, ride.routeId?.substringBefore(':'))
            assertTrue(ride.vehicleId!!.startsWith("batumi:theta:vehicle:"))
            assertEquals(BatumiServiceBounds, adapter.serviceBounds)
        }
    }
}
