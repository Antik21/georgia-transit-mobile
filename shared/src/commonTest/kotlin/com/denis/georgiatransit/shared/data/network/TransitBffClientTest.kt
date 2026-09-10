package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransitBffClientTest {
    @Test
    fun everyPublishedEndpointUsesOneSafePathSegmentEncodedQueriesAndEndpointTimeouts() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = TransitBffClient(mockHttpClient { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/cities") -> jsonResponse(citiesJson())
                request.url.encodedPath.endsWith("/shape") -> jsonResponse(shapeJson())
                request.url.encodedPath.endsWith("/stops") -> jsonResponse("[]")
                request.url.encodedPath.endsWith("/routes") -> jsonResponse(routesJson())
                request.url.encodedPath.contains("/routes/") -> jsonResponse(routeJson())
                request.url.encodedPath.endsWith("/vehicles") -> jsonResponse(vehiclePageJson())
                request.url.encodedPath.endsWith("/nearby") -> jsonResponse("[]")
                request.url.encodedPath.endsWith("/arrivals") -> jsonResponse(arrivalPageJson())
                request.url.encodedPath.endsWith("/journeys") -> jsonResponse(journeyPageJson())
                else -> error("Unexpected path ${request.url}")
            }
        }, endpoint())
        val city = CityId("demo")
        val route = RouteId("demo:fixture:route:blue/unsafe")
        val direction = DirectionId("demo:fixture:direction:outbound/unsafe")
        val stop = StopId("demo:fixture:stop:center/unsafe")

        client.cities()
        client.routes(city, TransitLocale.Russian, TransitMode.Metro, null)
        client.route(city, route, TransitLocale.Georgian)
        client.directionStops(city, route, direction, TransitLocale.English)
        client.directionShape(city, route, direction)
        client.vehicles(city, route, direction)
        client.nearbyStops(city, GeoPoint(41.7, 44.8), 100, 2, TransitLocale.Russian)
        client.arrivals(city, stop, 2, TransitLocale.English)
        client.journeys(city, GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), kotlin.time.Instant.parse("2030-01-01T00:00:00Z"), TransitLocale.Georgian, 2)

        assertEquals(9, requests.size)
        assertTrue(requests.all { it.method.value == "GET" })
        assertTrue(requests.any { it.url.encodedPath.contains("blue%2Funsafe") })
        assertTrue(requests.any { it.url.encodedPath.contains("outbound%2Funsafe") })
        assertTrue(requests.any { it.url.encodedPath.contains("center%2Funsafe") })
        assertEquals("ru", requests[1].url.parameters["locale"])
        assertEquals("metro", requests[1].url.parameters["mode"])
        assertEquals("demo:fixture:route:blue/unsafe", requests[5].url.parameters["routeId"])
        assertEquals("demo:fixture:direction:outbound/unsafe", requests[5].url.parameters["directionId"])
        assertEquals("2", requests[6].url.parameters["limit"])
        assertEquals("2030-01-01T00:00:00Z", requests[8].url.parameters["departureAt"])
        assertTimeouts(requests[0], 20_000L)
        assertTimeouts(requests[5], 8_000L)
        assertTimeouts(requests[7], 8_000L)
        assertTimeouts(requests[8], 20_000L)
    }

    @Test
    fun retriesOnlyPermittedStatusesAndTransportFailuresAtMostTwiceWithDeterministicDelay() = runTest {
        var attempts = 0
        val retryDelay = ExponentialRetryDelay(RetryJitter { base -> base / 2 })
        val client = TransitBffClient(mockHttpClient {
            attempts += 1
            if (attempts < 3) jsonResponse(errorJson("RATE_LIMITED"), HttpStatusCode.TooManyRequests, retryAfter = "1")
            else jsonResponse(citiesJson())
        }, endpoint(), retryDelay)

        val result = client.cities()

        assertIs<TransitLoadResult.Data<*>>(result)
        assertEquals(3, attempts)
        assertEquals(375L, retryDelay.delayMillis(1, null))
        assertEquals(750L, retryDelay.delayMillis(2, null))
        assertEquals(30_000L, retryDelay.delayMillis(2, 86_400))

        attempts = 0
        val noRetryClient = TransitBffClient(mockHttpClient {
            attempts += 1
            jsonResponse(errorJson("INTERNAL_ERROR"), HttpStatusCode.InternalServerError)
        }, endpoint(), retryDelay)
        assertIs<TransitLoadResult.Failure>(noRetryClient.cities())
        assertEquals(1, attempts)

        attempts = 0
        val unsupportedStatusClient = TransitBffClient(mockHttpClient {
            attempts += 1
            jsonResponse(errorJson("CAPABILITY_NOT_AVAILABLE"), HttpStatusCode.NotImplemented)
        }, endpoint(), retryDelay)
        assertIs<TransitLoadResult.Failure>(unsupportedStatusClient.cities())
        assertEquals(1, attempts)
    }

    @Test
    fun walkingEstimateUsesOneNoStorePostWithoutRetryOrCoordinateQueryParameters() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = TransitBffClient(mockHttpClient { request ->
            requests += request
            jsonResponse("""{"distanceMeters":420.0,"durationSeconds":360,"observedAt":"2030-01-01T00:00:00Z"}""")
        }, endpoint())

        val result = assertIs<TransitLoadResult.Data<*>>(
            client.walkingEstimate(
                CityId("demo"),
                GeoPoint(41.7151, 44.8271),
                GeoPoint(41.7180, 44.8330),
                TransitLocale.Georgian,
            ),
        )
        assertEquals(420.0, (result.value as com.denis.georgiatransit.shared.domain.model.WalkingEstimate).distanceMeters)
        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("/v1/cities/demo/walking-estimate", request.url.encodedPath)
        assertTrue(request.url.parameters.isEmpty())
        assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
        assertTimeouts(request, 8_000L)

        var attempts = 0
        val noRetry = TransitBffClient(mockHttpClient {
            attempts++
            jsonResponse(errorJson("RATE_LIMITED"), HttpStatusCode.TooManyRequests)
        }, endpoint())
        assertIs<TransitLoadResult.Failure>(
            noRetry.walkingEstimate(CityId("demo"), GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), TransitLocale.English),
        )
        assertEquals(1, attempts)
    }

    @Test
    fun mapsEveryPublishedErrorCodeAndRejectsMalformedOrUndocumentedBodies() = runTest {
        val expected = mapOf(
            "INVALID_ARGUMENT" to TransitFailure.InvalidArgument::class,
            "CITY_NOT_FOUND" to TransitFailure.CityNotFound::class,
            "ROUTE_NOT_FOUND" to TransitFailure.RouteNotFound::class,
            "STOP_NOT_FOUND" to TransitFailure.StopNotFound::class,
            "PROVIDER_ID_CHANGED" to TransitFailure.ProviderIdChanged::class,
            "RATE_LIMITED" to TransitFailure.RateLimited::class,
            "INTERNAL_ERROR" to TransitFailure.Internal::class,
            "CAPABILITY_NOT_AVAILABLE" to TransitFailure.CapabilityUnavailable::class,
            "UPSTREAM_BAD_RESPONSE" to TransitFailure.UpstreamBadResponse::class,
            "UPSTREAM_UNAVAILABLE" to TransitFailure.UpstreamUnavailable::class,
            "UPSTREAM_TIMEOUT" to TransitFailure.UpstreamTimeout::class,
        )

        expected.forEach { (code, expectedClass) ->
            val client = TransitBffClient(mockHttpClient {
                jsonResponse(errorJson(code), HttpStatusCode(418, "Contract error"), retryAfter = "99999")
            }, endpoint())
            val result = assertIs<TransitLoadResult.Failure>(client.cities())
            assertEquals(expectedClass, result.error::class, code)
            assertEquals("req-123", result.error.requestId)
        }

        val malformedSuccess = TransitBffClient(mockHttpClient { jsonResponse("{") }, endpoint()).cities()
        assertIs<TransitFailure.Serialization>(assertIs<TransitLoadResult.Failure>(malformedSuccess).error)
        val undocumented = TransitBffClient(mockHttpClient { jsonResponse("not-json", HttpStatusCode(418, "Unknown")) }, endpoint()).cities()
        assertIs<TransitFailure.InvalidResponse>(assertIs<TransitLoadResult.Failure>(undocumented).error)
        val invalidRetryAfter = TransitBffClient(mockHttpClient {
            jsonResponse(
                """{"error":{"code":"RATE_LIMITED","message":"contract message","retryAfterSeconds":0,"requestId":"req-123"}}""",
                HttpStatusCode(418, "Contract error"),
                retryAfter = "0",
            )
        }, endpoint()).cities()
        assertNull((assertIs<TransitLoadResult.Failure>(invalidRetryAfter).error as TransitFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun cancellationPropagatesAndInvalidArgumentsNeverReachTheNetwork() = runTest {
        val cancellation = TransitBffClient(mockHttpClient { throw CancellationException("caller cancelled") }, endpoint())
        assertFailsWith<CancellationException> { cancellation.cities() }

        var calls = 0
        val client = TransitBffClient(mockHttpClient {
            calls += 1
            jsonResponse(citiesJson())
        }, endpoint())
        val invalid = client.nearbyStops(CityId("demo"), GeoPoint(91.0, 0.0), 0, 101, TransitLocale.English)
        assertIs<TransitFailure.InvalidArgument>(assertIs<TransitLoadResult.Failure>(invalid).error)
        assertEquals(0, calls)
    }

    @Test
    fun missingInvalidAndInsecureEndpointsFailClosed() {
        assertIs<TransitFailure.Configuration>(TransitBffClient(mockHttpClient { error("not reached") }, null).configurationFailure())
        assertIs<TransitFailure.Configuration>(TransitBffClient(mockHttpClient { error("not reached") }, BffEndpointConfiguration("http://example.com")).configurationFailure())
        assertIs<TransitFailure.Configuration>(TransitBffClient(mockHttpClient { error("not reached") }, BffEndpointConfiguration("https://example.com/v1")).configurationFailure())
        assertNull(TransitBffClient(mockHttpClient { error("not reached") }, BffEndpointConfiguration.debugAndroidEmulator).configurationFailure())
        assertNull(TransitBffClient(mockHttpClient { error("not reached") }, BffEndpointConfiguration.debugAndroidPhysicalDevice).configurationFailure())
        assertNull(TransitBffClient(mockHttpClient { error("not reached") }, BffEndpointConfiguration.debugIosSimulator).configurationFailure())
        assertNull(BffEndpointConfiguration.debugAndroidEmulator.mapStyleUrlOrNull())
        assertEquals("http://10.0.2.2:8080/v1/map/style.json", BffEndpointConfiguration.debugAndroidEmulatorWithMapAssets.mapStyleUrlOrNull())
        assertEquals("http://127.0.0.1:8080/v1/map/style.json", BffEndpointConfiguration.debugAndroidPhysicalDeviceWithMapAssets.mapStyleUrlOrNull())
    }

    private fun assertTimeouts(request: HttpRequestData, expectedRequestTimeout: Long) {
        val timeout = requireNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
        assertEquals(5_000L, timeout.connectTimeoutMillis)
        assertEquals(expectedRequestTimeout, timeout.requestTimeoutMillis)
        assertEquals(expectedRequestTimeout, timeout.socketTimeoutMillis)
    }

    private fun endpoint() = BffEndpointConfiguration("https://bff.example")

    private fun mockHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        retryAfter: String? = null,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(
            *buildList {
                add(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                add(HttpHeaders.XRequestId to listOf("req-123"))
                retryAfter?.let { add(HttpHeaders.RetryAfter to listOf(it)) }
            }.toTypedArray(),
        ),
    )

    private fun errorJson(code: String) =
        """{"error":{"code":"$code","message":"contract message","retryAfterSeconds":5,"requestId":"req-123"}}"""

    private fun citiesJson() =
        """[{"id":"demo","name":{"ru":"Демо","en":"Demo","ka":"დემო"},"center":{"latitude":41.7,"longitude":44.8},"defaultZoom":13.0,"capabilities":{"routes":true,"stops":true,"routeGeometry":true,"vehiclePositions":true,"officialArrivals":true,"tripPlanning":true},"availability":{"readiness":"DEVELOPMENT_FIXTURE","source":"FIXTURE"}}]"""

    private fun routeJson() =
        """{"id":"demo:fixture:route:blue","providerId":"blue","shortName":"D1","longName":{"ru":"Р","en":"Route","ka":"მ"},"color":"#0057B8","textColor":"#FFFFFF","mode":"bus","directions":[]}"""

    private fun routesJson() = "[${routeJson()}]"

    private fun shapeJson() =
        """{"format":"encoded_polyline","precision":5,"value":"_p~iF~ps|U","updatedAt":"2030-01-01T00:00:00Z"}"""

    private fun vehiclePageJson() =
        """{"items":[],"observedAt":"2030-01-01T00:00:00Z","maxAgeSeconds":0,"stale":false}"""

    private fun arrivalPageJson() =
        """{"items":[],"source":"SCHEDULE","observedAt":"2030-01-01T00:00:00Z","stale":false}"""

    private fun journeyPageJson() = """{"items":[],"observedAt":"2030-01-01T00:00:00Z"}"""
}
