package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.config.BffConfig
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.event.Level
import org.slf4j.event.SubstituteLoggingEvent
import org.slf4j.helpers.SubstituteLogger

private val json = Json { ignoreUnknownKeys = false }

class ApplicationContractTest {
    @Test
    fun `health reports ready with fixtures and not ready without adapters`() {
        testApplication {
            installBff(fixtures = true)
            val response = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf("status", "mode"), response.objectBody().keys)
            assertEquals("ready", response.objectBody()["status"]?.jsonPrimitive?.content)
        }
        testApplication {
            installBff(fixtures = false)
            val response = client.get("/healthz")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not_ready", response.objectBody()["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `all fixture endpoints return raw documented response shapes`() = testApplication {
        installBff()
        val endpoints = listOf(
            "/v1/cities" to null,
            "/v1/cities/demo/routes" to null,
            "/v1/cities/demo/routes/demo:fixture:route:blue" to
                setOf("id", "providerId", "shortName", "longName", "color", "textColor", "mode", "directions"),
            "/v1/cities/demo/routes/demo:fixture:route:blue/directions/demo:fixture:direction:blue-outbound/stops" to null,
            "/v1/cities/demo/routes/demo:fixture:route:blue/directions/demo:fixture:direction:blue-outbound/shape" to
                setOf("format", "precision", "value", "updatedAt"),
            "/v1/cities/demo/vehicles?routeId=demo:fixture:route:blue" to
                setOf("items", "observedAt", "maxAgeSeconds", "stale"),
            "/v1/cities/demo/stops/nearby?lat=41.715137&lon=44.827096&radiusMeters=50000&limit=10" to null,
            "/v1/cities/demo/stops/demo:fixture:stop:center/arrivals?limit=10" to
                setOf("items", "source", "observedAt", "stale"),
            "/v1/cities/demo/journeys?fromLat=41.715&fromLon=44.827&toLat=41.718&toLon=44.833&" +
                "departureAt=2030-01-01T00%3A00%3A00Z&maxTransfers=0" to setOf("items", "observedAt"),
        )

        endpoints.forEach { (path, expectedKeys) ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertSingleRequestId(response)
            val element = json.parseToJsonElement(response.bodyAsText())
            if (expectedKeys == null) {
                assertTrue(element is JsonArray, path)
            } else {
                assertEquals(expectedKeys, element.jsonObject.keys, path)
            }
        }

        val city = client.get("/v1/cities").arrayBody().single().jsonObject
        assertEquals(setOf("id", "name", "center", "defaultZoom", "capabilities"), city.keys)
        val route = client.get("/v1/cities/demo/routes").arrayBody().single().jsonObject
        assertEquals(setOf("id", "providerId", "shortName", "longName", "color", "textColor", "mode", "directions"), route.keys)
        val stop = client.get(
            "/v1/cities/demo/stops/nearby?lat=41.715137&lon=44.827096&radiusMeters=50000&limit=1",
        ).arrayBody().single().jsonObject
        assertEquals(setOf("id", "providerId", "code", "name", "position", "routeIds", "mode"), stop.keys)

        val shape = client.get(
            "/v1/cities/demo/routes/demo:fixture:route:blue/directions/demo:fixture:direction:blue-outbound/shape",
        ).objectBody()
        assertEquals(setOf("format", "precision", "value", "updatedAt"), shape.keys)

        val vehiclePage = client.get(
            "/v1/cities/demo/vehicles?routeId=demo:fixture:route:blue",
        ).objectBody()
        assertEquals(
            setOf(
                "id",
                "routeId",
                "directionId",
                "position",
                "bearing",
                "nextStopId",
                "observedAt",
                "ageSeconds",
                "positionKind",
            ),
            vehiclePage.getValue("items").jsonArray.single().jsonObject.keys,
        )

        val arrivalPage = client.get(
            "/v1/cities/demo/stops/demo:fixture:stop:center/arrivals?limit=1",
        ).objectBody()
        assertEquals(
            setOf(
                "stopId",
                "routeId",
                "tripId",
                "headsign",
                "scheduledAt",
                "expectedAt",
                "expectedInMinutes",
                "realtime",
                "cancelled",
                "source",
            ),
            arrivalPage.getValue("items").jsonArray.single().jsonObject.keys,
        )

        val journeyPage = client.get(
            "/v1/cities/demo/journeys?fromLat=41.715&fromLon=44.827&toLat=41.718&toLon=44.833&" +
                "departureAt=2030-01-01T00%3A00%3A00Z&maxTransfers=0",
        ).objectBody()
        val journey = journeyPage.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "departureAt", "arrivalAt", "transfers", "legs"), journey.keys)
        assertEquals(
            setOf("routeId", "directionId", "fromStopId", "toStopId", "departureAt", "arrivalAt"),
            journey.getValue("legs").jsonArray.single().jsonObject.keys,
        )
    }

    @Test
    fun `request ID is propagated once or safely generated`() = testApplication {
        installBff()
        val supplied = client.get("/v1/cities") { header(HttpHeaders.XRequestId, "qa.request-123") }
        assertEquals(listOf("qa.request-123"), supplied.headers.getAll(HttpHeaders.XRequestId))

        val invalid = client.get("/v1/cities") { header(HttpHeaders.XRequestId, "unsafe request id") }
        val generated = invalid.headers.getAll(HttpHeaders.XRequestId)
        assertNotNull(generated)
        assertEquals(1, generated.size)
        assertNotEquals("unsafe request id", generated.single())
        assertTrue(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(generated.single()))
    }

    @Test
    fun `errors contain exact safe envelope and matching request ID`() = testApplication {
        installBff()
        val response = client.get("/v1/cities/Demo/routes") { header(HttpHeaders.XRequestId, "validation-1") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("validation-1"), response.headers.getAll(HttpHeaders.XRequestId))
        val root = response.objectBody()
        assertEquals(setOf("error"), root.keys)
        val error = root.getValue("error").jsonObject
        assertEquals(setOf("code", "message", "requestId"), error.keys)
        assertEquals("INVALID_ARGUMENT", error.getValue("code").jsonPrimitive.content)
        assertEquals("validation-1", error.getValue("requestId").jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains("Exception"))
    }

    @Test
    fun `unexpected throwable returns and logs only fixed redacted data`() {
        val events = LinkedBlockingQueue<SubstituteLoggingEvent>()
        val logger = SubstituteLogger("redaction-test", events, false)
        val stderr = ByteArrayOutputStream()
        val originalStderr = System.err
        try {
            System.setErr(PrintStream(stderr, true, StandardCharsets.UTF_8))
            testApplication {
                environment { log = logger }
                application {
                    transitBffModule(
                        BffConfig.fromEnvironment(mapOf("BFF_FIXTURES_ENABLED" to "true")),
                    )
                    routing {
                        get("/_test/unexpected") {
                            throwRedactionProbe()
                        }
                    }
                }
                val response = client.get("/_test/unexpected?token=query-probe-secret") {
                    header(HttpHeaders.XRequestId, "internal-500")
                    header("X-Probe", "header-probe-secret")
                }

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals(listOf("internal-500"), response.headers.getAll(HttpHeaders.XRequestId))
                assertEquals(null, response.headers[HttpHeaders.RetryAfter])
                val error = response.objectBody().getValue("error").jsonObject
                assertEquals(setOf("code", "message", "requestId"), error.keys)
                assertEquals("INTERNAL_ERROR", error.getValue("code").jsonPrimitive.content)
                assertEquals("The service encountered an internal error", error.getValue("message").jsonPrimitive.content)
                assertEquals("internal-500", error.getValue("requestId").jsonPrimitive.content)
            }
        } finally {
            System.setErr(originalStderr)
        }

        val errorEvents = events.filter { it.level == Level.ERROR }
        assertEquals(1, errorEvents.size)
        assertEquals(
            "request_failure classification=internal status=500 requestId=internal-500",
            errorEvents.single().message,
        )
        assertEquals(null, errorEvents.single().throwable)
        val captured = events.joinToString("\n") { event ->
            listOfNotNull(event.message, event.arguments?.joinToString(), event.throwable?.stackTraceToString()).joinToString()
        } + stderr.toString(StandardCharsets.UTF_8)
        listOf(
            "throwable-probe-secret",
            RedactionProbeException::class.java.name,
            "throwRedactionProbe",
            "query-probe-secret",
            "header-probe-secret",
        ).forEach { secret -> assertFalse(captured.contains(secret), secret) }
    }

    @Test
    fun `ETag handles exact weak list and wildcard matches and varies by representation`() = testApplication {
        installBff()
        val all = client.get("/v1/cities/demo/routes")
        val etag = assertNotNull(all.headers[HttpHeaders.ETag])
        assertTrue(Regex("\"[0-9a-f]{64}\"").matches(etag))

        listOf(etag, "W/$etag", "\"other\", W/$etag", "*").forEach { value ->
            val response = client.get("/v1/cities/demo/routes") { header(HttpHeaders.IfNoneMatch, value) }
            assertEquals(HttpStatusCode.NotModified, response.status, value)
            assertEquals(etag, response.headers[HttpHeaders.ETag])
        }
        val different = client.get("/v1/cities/demo/routes?mode=metro")
        assertEquals(HttpStatusCode.OK, different.status)
        assertNotEquals(etag, different.headers[HttpHeaders.ETag])
        assertEquals("[]", different.bodyAsText())
    }

    @Test
    fun `strict IDs validation bounds and absent entities map to exact errors`() = testApplication {
        installBff()
        val cases = listOf(
            "/v1/cities/demo/vehicles" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/vehicles?routeId=demo:fixture:route" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/vehicles?routeId=other:fixture:route:blue" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/vehicles?routeId=demo:fixture:stop:blue" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/routes?locale=fr" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/routes?mode=train" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/stops/nearby?lat=NaN&lon=0&radiusMeters=1&limit=1" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/stops/nearby?lat=0&lon=181&radiusMeters=1&limit=1" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/stops/nearby?lat=0&lon=0&radiusMeters=0&limit=1" to Pair(HttpStatusCode.BadRequest, "INVALID_ARGUMENT"),
            "/v1/cities/demo/stops/demo:fixture:stop:missing/arrivals?limit=1" to Pair(HttpStatusCode.NotFound, "STOP_NOT_FOUND"),
            "/v1/cities/demo/routes/demo:fixture:route:missing" to Pair(HttpStatusCode.NotFound, "ROUTE_NOT_FOUND"),
            "/v1/cities/missing/routes" to Pair(HttpStatusCode.NotFound, "CITY_NOT_FOUND"),
        )
        cases.forEach { (path, expected) ->
            val response = client.get(path)
            assertEquals(expected.first, response.status, path)
            assertEquals(expected.second, response.errorCode(), path)
        }
    }

    @Test
    fun `journey maxTransfers accepts zero through six and rejects outside range`() = testApplication {
        installBff()
        fun path(maxTransfers: Int) =
            "/v1/cities/demo/journeys?fromLat=41.7&fromLon=44.8&toLat=41.8&toLon=44.9&" +
                "departureAt=2030-01-01T00%3A00%3A00Z&maxTransfers=$maxTransfers"

        (0..6).forEach { assertEquals(HttpStatusCode.OK, client.get(path(it)).status, "$it") }
        listOf(-1, 7).forEach {
            val response = client.get(path(it))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_ARGUMENT", response.errorCode())
        }
    }
}

private class RedactionProbeException(message: String) : RuntimeException(message)

private fun throwRedactionProbe(): Nothing = throw RedactionProbeException("throwable-probe-secret")

private fun ApplicationTestBuilder.installBff(fixtures: Boolean = true) {
    application {
        transitBffModule(
            BffConfig.fromEnvironment(mapOf("BFF_FIXTURES_ENABLED" to fixtures.toString())),
        )
    }
}

private suspend fun HttpResponse.objectBody(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

private suspend fun HttpResponse.arrayBody(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray

private suspend fun HttpResponse.errorCode(): String =
    objectBody().getValue("error").jsonObject.getValue("code").jsonPrimitive.content

private fun assertSingleRequestId(response: HttpResponse) {
    val values = assertNotNull(response.headers.getAll(HttpHeaders.XRequestId))
    assertEquals(1, values.size)
}
