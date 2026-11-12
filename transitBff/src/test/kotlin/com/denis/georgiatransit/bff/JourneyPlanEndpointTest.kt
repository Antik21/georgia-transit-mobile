package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.config.BffConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JourneyPlanEndpointTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val from = """{"latitude":41.7141,"longitude":44.8253}"""
    private val to = """{"latitude":41.7192,"longitude":44.8351}"""

    @Test
    fun `demo fixture returns a no-store CLIENT_ESTIMATE plan with continuous segments`() = testApplication {
        installBff()
        val response = plan("""{"from":$from,"to":$to,"maxTransfers":1,"transferBufferSeconds":60,"locale":"en"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNoStore(response)
        assertEquals(1, response.headers.getAll(HttpHeaders.XRequestId)?.size)
        val page = response.objectBody()
        assertEquals("CLIENT_ESTIMATE", page.getValue("source").jsonPrimitive.content)
        assertTrue(page.getValue("realtime").jsonPrimitive.boolean)
        val items = page.getValue("items").jsonArray.map { it.jsonObject }
        val transit = items.first { journey -> journey.getValue("transfers").jsonPrimitive.content == "0" && journey.getValue("legs").jsonArray.isNotEmpty() }
        assertEquals(
            listOf("WALK", "TRANSIT", "WALK"),
            transit.getValue("segments").jsonArray.map { it.jsonObject.getValue("mode").jsonPrimitive.content },
        )
        val ride = transit.getValue("segments").jsonArray[1].jsonObject
        assertEquals("LIVE_VEHICLE", ride.getValue("waitBasis").jsonPrimitive.content)
        assertEquals("demo:fixture:vehicle:blue-01", ride.getValue("vehicleId").jsonPrimitive.content)
        assertEquals("LIVE", transit.getValue("timingQuality").jsonPrimitive.content)
        val walk = items.single { it.getValue("legs").jsonArray.isEmpty() }
        assertEquals(listOf("OPTIMAL", "LEAST_WALKING"), transit.labelNames())
        assertEquals(listOf("FASTEST"), walk.labelNames())
        assertEquals(transit, items.first(), "the OPTIMAL option leads the page")
    }

    @Test
    fun `the reverse demo request labels the walk OPTIMAL and the bus LEAST_WALKING`() = testApplication {
        installBff()
        val response = plan("""{"from":$to,"to":{"latitude":41.7140,"longitude":44.8262},"locale":"ru"}""")

        val items = response.objectBody().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(listOf("OPTIMAL", "FASTEST"), listOf("LEAST_WALKING")), items.map { it.labelNames() })
        assertTrue(items.first().getValue("legs").jsonArray.isEmpty())
    }

    @Test
    fun `invalid bodies return 400 without echoing coordinates and keep no-store headers`() = testApplication {
        installBff()
        val future = Instant.now().plusSeconds(3 * 3_600).toString()
        val bodies = listOf(
            "{",
            """{"from":{"latitude":91.123456,"longitude":44.8253},"to":$to,"locale":"en"}""",
            """{"from":{"latitude":10.123456,"longitude":20.654321},"to":$to,"locale":"en"}""",
            """{"from":$from,"to":$to,"locale":"fr"}""",
            """{"from":$from,"to":$to,"mode":"FASTEST","locale":"en"}""",
            """{"from":$from,"to":$to,"maxTransfers":3,"locale":"en"}""",
            """{"from":$from,"to":$to,"transferBufferSeconds":601,"locale":"en"}""",
            """{"from":$from,"to":$to,"departureAt":"$future","locale":"en"}""",
            """{"from":$from,"to":$to,"departureAt":"2030-01-01T00:00:00+04:00","locale":"en"}""",
            """{"from":$from,"to":$to,"origin":"device","locale":"en"}""",
            """{"from":$from,"to":$to}""",
        )
        bodies.forEach { body ->
            val response = plan(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("INVALID_ARGUMENT", response.errorCode(), body)
            assertNoStore(response)
            val text = response.bodyAsText()
            listOf("91.123456", "10.123456", "20.654321", "41.7141", "44.8253", "41.7192", "44.8351").forEach { coordinate ->
                assertFalse(text.contains(coordinate), "$coordinate leaked for $body")
            }
        }
    }

    @Test
    fun `unknown city and non-JSON content keep no-store headers`() = testApplication {
        installBff()
        val missing = plan("""{"from":$from,"to":$to,"locale":"en"}""", city = "kutaisi")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertNoStore(missing)

        val text = client.post("/v1/cities/demo/journeys/plan") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("from=41.7141")
        }
        assertEquals(HttpStatusCode.BadRequest, text.status)
        assertNoStore(text)
    }

    @Test
    fun `metrics carry only the static journey_plan operation and no coordinates`() = testApplication {
        installBff()
        plan("""{"from":$from,"to":$to,"locale":"ka"}""")

        val exposition = client.get("/metrics").bodyAsText()
        assertTrue(exposition.contains("bff_http_requests_total{operation=\"journey_plan\",status_class=\"2xx\"} 1"))
        assertTrue(exposition.contains("operation=\"journey_plan\""))
        listOf("41.7141", "44.8253", "41.7192", "44.8351").forEach { assertFalse(exposition.contains(it), it) }
    }

    private suspend fun ApplicationTestBuilder.plan(body: String, city: String = "demo"): HttpResponse =
        client.post("/v1/cities/$city/journeys/plan") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

    private fun ApplicationTestBuilder.installBff() {
        application { transitBffModule(BffConfig.fromEnvironment(mapOf("BFF_FIXTURES_ENABLED" to "true"))) }
    }

    private fun assertNoStore(response: HttpResponse) {
        assertEquals("no-store, no-cache, max-age=0", response.headers[HttpHeaders.CacheControl])
        assertEquals("no-cache", response.headers[HttpHeaders.Pragma])
    }

    private suspend fun HttpResponse.objectBody(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.labelNames(): List<String> =
        get("labels")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private suspend fun HttpResponse.errorCode(): String =
        objectBody().getValue("error").jsonObject.getValue("code").jsonPrimitive.content
}
