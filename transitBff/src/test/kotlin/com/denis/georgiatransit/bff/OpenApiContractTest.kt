package com.denis.georgiatransit.bff

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class OpenApiContractTest {
    @Test
    fun `OpenAPI parses and contains every implemented path with raw schemas`() {
        val document = loadOpenApi()
        val paths = document.map("paths")
        val expectedSchemas = mapOf(
            "/healthz" to "HealthResponse",
            "/v1/cities" to "Cities",
            "/v1/cities/{cityId}/routes" to "Routes",
            "/v1/cities/{cityId}/routes/{routeId}" to "Route",
            "/v1/cities/{cityId}/routes/{routeId}/directions/{directionId}/stops" to "Stops",
            "/v1/cities/{cityId}/routes/{routeId}/directions/{directionId}/shape" to "Shape",
            "/v1/cities/{cityId}/vehicles" to "VehiclePage",
            "/v1/cities/{cityId}/stops/nearby" to "Stops",
            "/v1/cities/{cityId}/stops/{stopId}/arrivals" to "ArrivalPage",
            "/v1/cities/{cityId}/journeys" to "JourneyPage",
        )

        assertEquals(expectedSchemas.keys, paths.keys)
        expectedSchemas.forEach { (path, schema) ->
            val get = paths.map(path).map("get")
            val success = get.map("responses").map("200")
            val schemaRef = success.map("content").map("application/json").map("schema")["\$ref"]
            assertEquals("#/components/schemas/$schema", schemaRef, path)
            assertEquals(
                "#/components/headers/RequestId",
                success.map("headers").map("X-Request-ID")["\$ref"],
                path,
            )
        }
    }

    @Test
    fun `OpenAPI request ID error and public ID schemas are strict`() {
        val schemas = loadOpenApi().map("components").map("schemas")
        val requestId = schemas.map("RequestId")
        assertEquals(128, requestId["maxLength"])
        assertEquals("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", requestId["pattern"])
        assertEquals("^[^:\\s]+:[^:\\s]+:[^:\\s]+:[^:\\s]+$", schemas.map("PublicId")["pattern"])

        val error = schemas.map("ErrorEnvelope").map("properties").map("error")
        assertEquals(listOf("code", "message", "requestId"), error["required"])
        val codes = error.map("properties").map("code")["enum"] as List<*>
        assertEquals(
            setOf(
                "INVALID_ARGUMENT",
                "CITY_NOT_FOUND",
                "ROUTE_NOT_FOUND",
                "STOP_NOT_FOUND",
                "PROVIDER_ID_CHANGED",
                "RATE_LIMITED",
                "CAPABILITY_NOT_AVAILABLE",
                "UPSTREAM_BAD_RESPONSE",
                "UPSTREAM_UNAVAILABLE",
                "UPSTREAM_TIMEOUT",
            ),
            codes.toSet(),
        )
    }

    @Test
    fun `all reusable errors carry request ID and use the raw error envelope`() {
        val responses = loadOpenApi().map("components").map("responses")
        val concreteErrors = listOf(
            "Error400",
            "Error404City",
            "Error404Route",
            "Error404Stop",
            "Error409",
            "Error429",
            "Error501",
            "Error502",
            "Error503",
            "Error504",
        )
        concreteErrors.forEach { name ->
            val response = responses.map(name)
            assertEquals(
                "#/components/headers/RequestId",
                response.map("headers").map("X-Request-ID")["\$ref"],
                name,
            )
            assertEquals(
                "#/components/schemas/ErrorEnvelope",
                response.map("content").map("application/json").map("schema")["\$ref"],
                name,
            )
        }
        assertTrue(responses.map("Error429").map("headers").containsKey("Retry-After"))
    }
}

private fun loadOpenApi(): Map<String, Any?> {
    val candidates = listOf(
        Path.of("docs/openapi/transit-bff-v1.yaml"),
        Path.of("../docs/openapi/transit-bff-v1.yaml"),
    )
    val path = assertNotNull(candidates.firstOrNull(Files::isRegularFile), "OpenAPI contract was not found")
    @Suppress("UNCHECKED_CAST")
    return Yaml().load<Map<String, Any?>>(path.readText())
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(name: String): Map<String, Any?> =
    assertNotNull(this[name] as? Map<String, Any?>, "Expected mapping at $name")
