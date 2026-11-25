package com.denis.georgiatransit.bff.provider.zugdidi

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.BffConfigurationException
import com.denis.georgiatransit.bff.geo.decodePolyline
import com.denis.georgiatransit.bff.transitBffModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ZugdidiPreviewTest {
    @Test
    fun `routes and direction stops publish localized labels in order over the BFF`() {
        val csv = Files.createTempFile("zugdidi-test", ".csv")
        try {
            csv.writeText(
                "\uFEFF" + """
                route,direction,origin,destination,sequence,yandex_stop_id,stop_name,latitude,longitude,source_url
                1,1,Университет,Улица Барамия,1,101,Университет,42.52,41.83,
                1,1,Университет,Улица Барамия,2,102,"Улица Тамар Мепе, 28",42.51,41.84,
                1,1,Университет,Улица Барамия,3,103,Улица Барамия,42.48,41.86,
                1,2,Улица Барамия,Университет,1,103,Улица Барамия,42.48,41.86,
                1,2,Улица Барамия,Университет,2,101,Университет,42.52,41.83,
                2,1,Фарфоровый завод,Фарфоровый завод,1,104,Фарфоровый завод,42.53,41.88,
                2,1,Фарфоровый завод,Фарфоровый завод,2,103,Улица Барамия,42.48,41.86,
                2,1,Фарфоровый завод,Фарфоровый завод,3,104,Фарфоровый завод,42.53,41.88,
                """.trimIndent(),
            )
            testApplication {
                application { transitBffModule(BffConfig.fromEnvironment(mapOf("BFF_ZUGDIDI_PREVIEW_CSV" to csv.toString()))) }
                val locales = listOf("ru", "en", "ka", "uk", "de", "fr", "ar", "hi", "zh", "th")
                val base = "/v1/cities/zugdidi/routes"
                for (locale in locales) {
                    val response = client.get("$base?locale=$locale")
                    assertEquals(HttpStatusCode.OK, response.status)
                    val (route, loop) = Json.decodeFromString<List<Route>>(response.bodyAsText())
                    assertEquals("Universität → Merab-Baramia-Straße", route.longName.de)
                    assertEquals("Університет", route.directions.last().headsign.uk)
                    assertEquals("Porcelain Factory ↻ via Merab Baramia Street", loop.longName.en)
                    for (current in listOf(route, loop)) {
                        for (direction in current.directions) {
                            val path = "$base/${current.id}/directions/${direction.id}"
                            val directionResponse = client.get("$path/stops?locale=$locale")
                            assertEquals(HttpStatusCode.OK, directionResponse.status)
                            val stops = Json.decodeFromString<List<Stop>>(directionResponse.bodyAsText())
                            val expected = when (direction.id) {
                                "zugdidi:fixture:direction:1-1" -> listOf("101", "102", "103")
                                "zugdidi:fixture:direction:1-2" -> listOf("103", "101")
                                else -> listOf("104", "103")
                            }
                            assertEquals(expected, stops.map(Stop::code))
                            if ("102" in expected) assertEquals("28, rue Tamar-Mepe", stops[1].name.fr)
                            assertTrue(stops.all { !it.name.ar.isNullOrBlank() && !it.name.zh.isNullOrBlank() })
                        }
                    }
                }
                val shapePath = "$base/zugdidi:fixture:route:2/directions/zugdidi:fixture:direction:2-1/shape"
                val shape = Json.decodeFromString<Shape>(client.get(shapePath).bodyAsText())
                val points = decodePolyline(shape.value)
                assertEquals(3, points.size)
                assertEquals(points.first(), points.last())
            }
        } finally {
            Files.deleteIfExists(csv)
        }
    }

    @Test
    fun `production exposes only capabilities accepted by an operator document`() {
        val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "zugdidi-test-", privateDirectory)
        try {
            val csv = Files.createFile(root.resolve("stops.csv"), privateFile)
            val control = Files.createFile(root.resolve("capabilities.json"), privateFile)
            val state = Files.createDirectory(root.resolve("state"), privateDirectory)
            csv.writeText(
                """
                route,direction,origin,destination,sequence,yandex_stop_id,stop_name,latitude,longitude,source_url
                1,1,,,1,101,Университет,42.52,41.83,
                1,1,,,2,103,Улица Барамия,42.48,41.86,
                """.trimIndent(),
            )
            control.writeText(
                """
                {
                  "revision":"zugdidi-test",
                  "cities":[{
                    "id":"zugdidi","enabled":true,
                    "availability":{"readiness":"PRODUCTION_READY","source":"REVIEWED_ADAPTER"},
                    "capabilities":{"routes":true,"stops":true,"routeGeometry":true,"vehiclePositions":false,
                                    "officialArrivals":false,"arrivals":false,"tripPlanning":false}
                  }]
                }
                """.trimIndent(),
            )
            val settings = mapOf(
                "BFF_MODE" to "production",
                "BFF_ZUGDIDI_STATIC_CSV" to csv.toString(),
                "BFF_CAPABILITY_CONTROL_PATH" to control.toString(),
                "BFF_CAPABILITY_CONTROL_STATE_DIR" to state.toString(),
            )
            testApplication {
                application { transitBffModule(BffConfig.fromEnvironment(settings)) }
                val city = Json.decodeFromString<List<City>>(client.get("/v1/cities").bodyAsText()).single()
                assertEquals(CityReadiness.PRODUCTION_READY, city.availability.readiness)
                assertEquals(41.48, city.mapBounds?.west)
                val routes = client.get("/v1/cities/zugdidi/routes?locale=de")
                assertEquals(HttpStatusCode.OK, routes.status)
                val route = Json.decodeFromString<List<Route>>(routes.bodyAsText()).single()
                assertEquals("zugdidi:static:route:1", route.id)
                assertEquals(HttpStatusCode.NotImplemented, client.get("/v1/cities/zugdidi/vehicles?routeId=${route.id}").status)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fixture cannot activate outside loopback development`() {
        val settings = mapOf("BFF_ZUGDIDI_PREVIEW_CSV" to "/tmp/zugdidi-preview.csv")
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(settings + ("BFF_MODE" to "production"))
        }
        assertFailsWith<BffConfigurationException> {
            BffConfig.fromEnvironment(settings + ("BFF_HOST" to "0.0.0.0"))
        }
    }
}
