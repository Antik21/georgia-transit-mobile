package com.denis.georgiatransit.bff.provider.staticcatalog

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.geo.decodePolyline
import com.denis.georgiatransit.bff.transitBffModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class BorjomiCatalogTest {
    @Test
    fun `bundled catalog exposes the two observed stop sequences for each service`() = testApplication {
        val settings = mapOf(
            "BFF_HOST" to "127.0.0.1",
            "BFF_FIXTURES_ENABLED" to "true",
            "BFF_BORJOMI_STATIC_CSV" to Path.of("data/borjomi/stops.csv").toAbsolutePath().toString(),
        )
        application { transitBffModule(BffConfig.fromEnvironment(settings)) }
        val city = Json.decodeFromString<List<City>>(client.get("/v1/cities").bodyAsText()).single { it.id == "borjomi" }
        assertFalse(city.capabilities.vehiclePositions)
        assertFalse(city.capabilities.tripPlanning)
        val base = "/v1/cities/borjomi/routes"
        val routes = Json.decodeFromString<List<Route>>(client.get(base).bodyAsText())
        val counts = listOf(listOf(6, 7), listOf(13, 13), listOf(14, 12), listOf(19, 18), listOf(5, 4))
        assertEquals(counts.size, routes.size)
        val uniqueStops = mutableSetOf<String>()
        for ((index, route) in routes.withIndex()) {
            assertEquals(2, route.directions.size)
            for ((directionIndex, direction) in route.directions.withIndex()) {
                val path = "$base/${route.id}/directions/${direction.id}"
                val stops = Json.decodeFromString<List<Stop>>(client.get("$path/stops").bodyAsText())
                assertEquals(counts[index][directionIndex], stops.size)
                assertEquals(direction.headsign, stops.last().name)
                assertEquals("Market", (if (directionIndex == 0) stops.first() else stops.last()).name.en)
                if (index == 0 && directionIndex == 1) {
                    assertEquals(
                        listOf("5347577576", "4656251613", "4881995056", "4881993866", "5349539726", "4881989946", "4876650706"),
                        stops.map(Stop::code),
                    )
                }
                uniqueStops.addAll(stops.map { it.id })
                val shape = Json.decodeFromString<Shape>(client.get("$path/shape").bodyAsText())
                assertEquals(stops.size, decodePolyline(shape.value).size)
            }
        }
        assertEquals(64, uniqueStops.size)
    }
}
