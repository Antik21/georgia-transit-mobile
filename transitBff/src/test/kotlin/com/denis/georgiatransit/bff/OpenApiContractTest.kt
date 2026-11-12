package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyPlanRequest
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.JourneyTimingQuality
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import com.denis.georgiatransit.bff.planning.JourneyPlanMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.yaml.snakeyaml.Yaml

private val wireJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

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
            "/v1/cities/{cityId}/routes/{routeId}/arrivals" to "ArrivalPage",
            "/v1/cities/{cityId}/routes/{routeId}/directions/{directionId}/stops" to "Stops",
            "/v1/cities/{cityId}/routes/{routeId}/directions/{directionId}/shape" to "Shape",
            "/v1/cities/{cityId}/vehicles" to "VehiclePage",
            "/v1/cities/{cityId}/stops/nearby" to "Stops",
            "/v1/cities/{cityId}/stops/{stopId}/arrivals" to "ArrivalPage",
            "/v1/cities/{cityId}/journeys" to "JourneyPage",
        )

        assertEquals(
            expectedSchemas.keys + setOf(
                "/metrics",
                "/v1/cities/{cityId}/walking-estimate",
                "/v1/cities/{cityId}/journeys/plan",
                "/v1/map/style.json",
                "/v1/map/tiles/{z}/{x}/{y}.png",
            ),
            paths.keys,
        )
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
            assertEquals(
                "#/components/responses/InternalError",
                get.map("responses").map("500")["\$ref"],
                path,
            )
        }

        val walking = paths.map("/v1/cities/{cityId}/walking-estimate").map("post")
        assertEquals(
            "#/components/schemas/WalkingEstimateRequest",
            walking.map("requestBody").map("content").map("application/json").map("schema")["\$ref"],
        )
        val walkingSuccess = walking.map("responses").map("200")
        assertEquals(
            "#/components/schemas/WalkingEstimate",
            walkingSuccess.map("content").map("application/json").map("schema")["\$ref"],
        )
        assertEquals(
            "#/components/headers/NoStoreCacheControl",
            walkingSuccess.map("headers").map("Cache-Control")["\$ref"],
        )
        assertEquals("#/components/responses/InternalError", walking.map("responses").map("500")["\$ref"])

        val plan = paths.map("/v1/cities/{cityId}/journeys/plan").map("post")
        assertEquals("planJourneyOptions", plan["operationId"])
        assertEquals(
            "#/components/schemas/JourneyPlanRequest",
            plan.map("requestBody").map("content").map("application/json").map("schema")["\$ref"],
        )
        val planSuccess = plan.map("responses").map("200")
        assertEquals(
            "#/components/schemas/JourneyPage",
            planSuccess.map("content").map("application/json").map("schema")["\$ref"],
        )
        assertEquals("#/components/headers/RequestId", planSuccess.map("headers").map("X-Request-ID")["\$ref"])
        assertEquals("#/components/headers/NoStoreCacheControl", planSuccess.map("headers").map("Cache-Control")["\$ref"])
        mapOf(
            "400" to "InvalidArgument",
            "404" to "CityNotFound",
            "429" to "RateLimited",
            "501" to "CapabilityNotAvailable",
            "503" to "UpstreamUnavailable",
            "500" to "InternalError",
        ).forEach { (status, response) ->
            assertEquals("#/components/responses/$response", plan.map("responses").map(status)["\$ref"], status)
        }

        val metrics = paths.map("/metrics").map("get")
        val success = metrics.map("responses").map("200")
        assertEquals(
            "string",
            success.map("content").map("text/plain; version=0.0.4; charset=utf-8").map("schema")["type"],
        )
        assertEquals(
            "Exporter disabled by BFF_METRICS_ENABLED=false.",
            metrics.map("responses").map("404")["description"],
        )
        assertFalse(success.containsKey("headers"), "internal metrics never carries a client request ID")
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
                "INTERNAL_ERROR",
                "CAPABILITY_NOT_AVAILABLE",
                "UPSTREAM_BAD_RESPONSE",
                "UPSTREAM_UNAVAILABLE",
                "UPSTREAM_TIMEOUT",
            ),
            codes.toSet(),
        )
    }

    @Test
    fun `OpenAPI city metadata carries seven capabilities and additive attribution`() {
        val schemas = loadOpenApi().map("components").map("schemas")
        val capabilities = schemas.map("CityCapabilities")
        assertEquals(
            listOf("routes", "stops", "routeGeometry", "vehiclePositions", "officialArrivals", "tripPlanning", "arrivals"),
            capabilities["required"],
        )
        assertEquals(
            setOf("routes", "stops", "routeGeometry", "vehiclePositions", "officialArrivals", "tripPlanning", "arrivals"),
            capabilities.map("properties").keys,
        )
        assertEquals(
            listOf("id", "name", "center", "defaultZoom", "capabilities", "availability"),
            schemas.map("City")["required"],
        )
        assertEquals(listOf("readiness", "source"), schemas.map("CityAvailability")["required"])
        assertEquals("#/components/schemas/CityAvailability", schemas.map("City").map("properties").map("availability")["\$ref"])
        assertEquals("#/components/schemas/AttributionLink", schemas.map("City").map("properties").map("attribution").map("items")["\$ref"])
        assertEquals(listOf("id", "label", "url"), schemas.map("AttributionLink")["required"])
        assertEquals("^https://", schemas.map("AttributionLink").map("properties").map("url")["pattern"])
    }

    @Test
    fun `OpenAPI keeps legacy journey legs required while adding complete typed segments and page provenance`() {
        val schemas = loadOpenApi().map("components").map("schemas")
        val journey = schemas.map("Journey")
        val page = schemas.map("JourneyPage")

        assertEquals(listOf("id", "departureAt", "arrivalAt", "transfers", "legs"), journey["required"])
        assertTrue(journey.map("properties").containsKey("segments"))
        assertEquals(
            listOf("departureAt", "arrivalAt", "mode"),
            schemas.map("JourneySegment")["required"],
        )
        assertEquals(
            listOf("TRANSIT", "WALK", "BICYCLE", "CAR", "OTHER"),
            schemas.map("JourneySegmentMode")["enum"],
        )
        assertEquals(listOf("items", "observedAt", "source", "realtime", "stale"), page["required"])
    }

    @Test
    fun `OpenAPI walking request and response are strict coordinate-minimal contracts`() {
        val schemas = loadOpenApi().map("components").map("schemas")
        val request = schemas.map("WalkingEstimateRequest")
        val response = schemas.map("WalkingEstimate")

        assertEquals(listOf("from", "to", "locale"), request["required"])
        assertEquals(setOf("from", "to", "locale"), request.map("properties").keys)
        assertEquals(listOf("distanceMeters", "durationSeconds", "observedAt"), response["required"])
        assertEquals(setOf("distanceMeters", "durationSeconds", "observedAt"), response.map("properties").keys)
        assertFalse(response.map("properties").containsKey("from"))
        assertFalse(response.map("properties").containsKey("to"))
    }

    @Test
    fun `OpenAPI journey plan request and additive journey fields match the BFF wire models`() {
        val schemas = loadOpenApi().map("components").map("schemas")
        val request = schemas.map("JourneyPlanRequest")
        assertEquals(listOf("from", "to", "locale"), request["required"])
        val requestJson = wireJson.encodeToJsonElement(
            JourneyPlanRequest(GeoPoint(41.6, 41.6), GeoPoint(41.61, 41.61), "2030-01-01T00:00:00Z", locale = "en"),
        ).jsonObject
        assertEquals(request.map("properties").keys, requestJson.keys)
        assertEquals(2, request.map("properties").map("maxTransfers")["maximum"])
        assertEquals(600, request.map("properties").map("transferBufferSeconds")["maximum"])
        assertEquals(JourneyPlanMode.entries.map { it.name }, schemas.map("JourneyPlanMode")["enum"])
        assertEquals(JourneyWaitBasis.entries.map { it.name }, schemas.map("JourneyWaitBasis")["enum"])
        assertEquals(JourneyTimingQuality.entries.map { it.name }, schemas.map("JourneyTimingQuality")["enum"])

        val segment = JourneySegment(
            departureAt = "2030-01-01T00:00:00Z",
            arrivalAt = "2030-01-01T00:10:00Z",
            mode = JourneySegmentMode.TRANSIT,
            routeId = "demo:fixture:route:blue",
            directionId = "demo:fixture:direction:blue-outbound",
            fromStopId = "demo:fixture:stop:center",
            toStopId = "demo:fixture:stop:park",
            fromPosition = GeoPoint(41.7, 44.8),
            toPosition = GeoPoint(41.71, 44.81),
            distanceMeters = 100.0,
            geometry = "_p~iF~ps|U",
            geometryApproximate = false,
            intermediateStopCount = 1,
            waitSeconds = 60,
            waitBasis = JourneyWaitBasis.LIVE_VEHICLE,
            vehicleId = "demo:fixture:vehicle:blue-01",
        )
        val journey = Journey(
            id = "demo:fixture:journey:j1",
            departureAt = segment.departureAt,
            arrivalAt = segment.arrivalAt,
            transfers = 0,
            legs = emptyList(),
            segments = listOf(segment),
            durationSeconds = 600,
            walkingDistanceMeters = 0.0,
            timingQuality = JourneyTimingQuality.LIVE,
        )
        assertEquals(
            schemas.map("JourneySegment").map("properties").keys,
            wireJson.encodeToJsonElement(segment).jsonObject.keys,
        )
        assertEquals(
            schemas.map("Journey").map("properties").keys,
            wireJson.encodeToJsonElement(journey).jsonObject.keys,
        )
        assertEquals(
            "#/components/schemas/VehicleId",
            schemas.map("JourneySegment").map("properties").map("vehicleId")["\$ref"],
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
            "Error500",
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
