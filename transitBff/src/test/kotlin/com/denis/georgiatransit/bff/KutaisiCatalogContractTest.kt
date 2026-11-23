package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.config.BatumiThetaActivationConfig
import com.denis.georgiatransit.bff.config.BffConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KutaisiCatalogContractTest {
    @Test
    fun `registration alone does not expose Kutaisi stops or vector coverage`() = testApplication {
        application { transitBffModule(BffConfig.fromEnvironment(mapSettings + ("BFF_KUTAISI_OSM_ENABLED" to "true"))) }

        assertEquals(emptyList(), Json.decodeFromString<List<City>>(client.get("/v1/cities").bodyAsText()))
        assertEquals(HttpStatusCode.NotFound, client.get(nearbyPath()).status)
        assertEquals(setOf("gt-vector-batumi"), styleSources(client.get("/v1/map/style.json").bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/map/vector/14/10135/6065.pbf").status)
    }

    @Test
    fun `enabled Kutaisi shares the API and style without overriding Batumi capabilities`() = withControlFiles { overrides ->
        testApplication {
            val environment = mapSettings + overrides + mapOf(
                "BFF_KUTAISI_OSM_ENABLED" to "true",
                "BATUMI_THETA_ENABLED" to "true",
                "BATUMI_THETA_OPERATOR_ACKNOWLEDGEMENT" to BatumiThetaActivationConfig.Acknowledgement,
            )
            application { transitBffModule(BffConfig.fromEnvironment(environment)) }

            val cities = Json.decodeFromString<List<City>>(client.get("/v1/cities").bodyAsText())
            assertEquals(listOf("batumi", "kutaisi"), cities.map(City::id))
            assertTrue(cities.first().capabilities.routes)
            assertTrue(cities.first().capabilities.routeGeometry)
            val kutaisi = cities.last()
            assertTrue(kutaisi.capabilities.stops)
            assertFalse(kutaisi.capabilities.routes)
            assertFalse(kutaisi.capabilities.routeGeometry)
            assertFalse(kutaisi.capabilities.vehiclePositions)
            assertFalse(kutaisi.capabilities.arrivals)
            assertFalse(kutaisi.capabilities.officialArrivals)
            assertFalse(kutaisi.capabilities.tripPlanning)
            assertEquals("https://www.openstreetmap.org/copyright", kutaisi.attribution.single().url)
            assertEquals(
                setOf("gt-vector-batumi", "gt-vector-kutaisi"),
                styleSources(client.get("/v1/map/style.json").bodyAsText()),
            )

            val response = client.get(nearbyPath())
            assertEquals(HttpStatusCode.OK, response.status)
            val stops = Json.decodeFromString<List<Stop>>(response.bodyAsText())
            assertEquals(listOf("kutaisi:osm:stop:2069821676", "kutaisi:osm:stop:2069821675"), stops.map(Stop::id))
            assertEquals("Bus stop", stops.first().name.en)
            assertEquals("Aghmashenebeli Square", stops.last().name.en)
            assertTrue(stops.all { it.routeIds.isEmpty() && it.mode == "bus" })
            assertEquals(emptyList(), Json.decodeFromString<List<Stop>>(client.get(nearbyPath(radiusMeters = 20)).bodyAsText()))

            val paliashvili = "/v1/cities/kutaisi/stops/nearby?lat=42.271577&lon=42.700961&radiusMeters=500&limit=20"
            val localStops = Json.decodeFromString<List<Stop>>(client.get(paliashvili).bodyAsText())
            assertEquals("kutaisi:osm:stop:5063547522", localStops.first().id)
            assertTrue(localStops.none { it.id == "kutaisi:osm:stop:11843259176" })

            val route = "/v1/cities/kutaisi/routes/kutaisi:osm:route:3"
            listOf(
                "/v1/cities/kutaisi/routes",
                "$route/directions/kutaisi:osm:direction:3-a/shape",
                "/v1/cities/kutaisi/vehicles?routeId=kutaisi:osm:route:3",
                "/v1/cities/kutaisi/stops/${stops.first().id}/arrivals?limit=5",
                "/v1/cities/kutaisi/journeys?fromLat=42.2716&fromLon=42.7054&toLat=42.26&toLon=42.69" +
                    "&departureAt=2030-01-01T00%3A00%3A00Z&maxTransfers=0",
            ).forEach { path ->
                assertEquals(HttpStatusCode.NotImplemented, client.get(path).status, path)
            }
        }
    }

    private fun nearbyPath(radiusMeters: Int = 500): String =
        "/v1/cities/kutaisi/stops/nearby?lat=42.271608&lon=42.705448&radiusMeters=$radiusMeters&limit=2"

    private fun styleSources(body: String): Set<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("sources").jsonObject.keys

    private fun withControlFiles(block: (Map<String, String>) -> Unit) {
        val parent = Path.of("build/kutaisi-control-tests").toAbsolutePath()
        Files.createDirectories(parent)
        val dirMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val root = Files.createTempDirectory(parent, "case-", dirMode)
        try {
            val state = Files.createDirectory(root.resolve("state"), dirMode)
            val control = Files.createFile(root.resolve("cities.json"), fileMode)
            val kutaisiControl = readControl("kutaisi-osm-capability-control.example.json")
            val kutaisi = kutaisiControl.getValue("cities").jsonArray.single()
            val batumi = readControl("batumi-theta-capability-control.example.json").getValue("cities").jsonArray.single().jsonObject
            // Keep the actual Batumi adapter registered without starting an upstream live worker.
            val batumiCapabilities = batumi.getValue("capabilities").jsonObject + mapOf(
                "vehiclePositions" to JsonPrimitive(false),
                "arrivals" to JsonPrimitive(false),
            )
            val batumiStatic = JsonObject(batumi + ("capabilities" to JsonObject(batumiCapabilities)))
            val cities = JsonArray(listOf(batumiStatic, kutaisi))
            Files.writeString(control, JsonObject(kutaisiControl + ("cities" to cities)).toString())
            block(mapOf(
                "BFF_CAPABILITY_CONTROL_PATH" to control.toString(),
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to state.toString(),
            ))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun readControl(name: String): JsonObject =
        Json.parseToJsonElement(Files.readString(Path.of(name))).jsonObject

    private val mapSettings = mapOf(
        "BFF_MAP_ENABLED" to "true",
        "BFF_MAP_PUBLIC_BASE_URL" to "http://127.0.0.1:8080",
    )
}
