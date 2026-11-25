package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.BatumiThetaCatalogClient
import com.denis.georgiatransit.bff.provider.BatumiThetaTransitProviderAdapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BatumiRouteNamesTest {
    @Test
    fun `catalog and detail publish the same localized name and keep the technical direction`() = runBlocking {
        adapter().use { adapter ->
            val route = adapter.routes("ru", "bus").single()

            assertEquals(route, adapter.route(route.id, "ka"))
            assertEquals("10", route.shortName)
            assertEquals(
                LocalizedText(
                    ru = "Аэропорт — Ботанический сад",
                    en = "Airport — Botanical Garden",
                    ka = "აეროპორტი — ბოტანიკური ბაღი",
                    uk = "Аеропорт — Ботанічний сад",
                    de = "Flughafen — Botanischer Garten",
                    fr = "Aéroport — Jardin botanique",
                    ar = "المطار — الحديقة النباتية",
                    hi = "हवाई अड्डा — बोटैनिकल गार्डन",
                    zh = "机场 — 植物园",
                    th = "สนามบิน — สวนพฤกษศาสตร์",
                ),
                route.longName,
            )
            assertEquals("Technical direction", route.directions.single().headsign.en)
        }
    }

    @Test
    fun `a different route ID or a changed number uses provider text`() = runBlocking {
        adapter(providerId = "new-route").use { adapter ->
            assertEquals(LocalizedText("10", "10", "10"), adapter.routes("en", null).single().longName)
        }
        adapter(shortName = "99").use { adapter ->
            assertEquals(LocalizedText("99", "99", "99"), adapter.routes("en", null).single().longName)
        }
    }

    private fun adapter(
        providerId: String = "64901c96e25b40c6e2150b34",
        shortName: String = "10",
    ): BatumiThetaTransitProviderAdapter {
        val activation = BffConfig.fromEnvironment(
            mapOf(
                "BATUMI_THETA_ENABLED" to "true",
                "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to "I_APPROVE_THETA_PRODUCTION_USE",
            ),
        ).batumiTheta
        val client = object : BatumiThetaCatalogClient {
            override suspend fun getDbData(): String = """
                {
                  "routesNames": {"$providerId": {"RouteNameEN": "$shortName", "RouteNameKA": "$shortName"}},
                  "busStops": {
                    "a": {"Lat": 41.64, "Lon": 41.64, "routes": {"$providerId": {"Order": 1}}},
                    "b": {"Lat": 41.64, "Lon": 41.65, "routes": {"$providerId": {"Order": 2}}}
                  },
                  "routeCoordinatesGrouped": {"$providerId": [
                    {"lat": 41.64, "lon": 41.64},
                    {"lat": 41.64, "lon": 41.65}
                  ]}
                }
            """.trimIndent()

            override suspend fun getBusLocsOnRoute(rawRouteId: String): String = error("No live request expected")
            override fun close() = Unit
        }
        return BatumiThetaTransitProviderAdapter(activation, client)
    }
}
