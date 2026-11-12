package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyLabel
import com.denis.georgiatransit.shared.domain.model.JourneyPlanQuery
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.JourneyTimingQuality
import com.denis.georgiatransit.shared.domain.model.JourneyWaitBasis
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyPlanClientTest {
    @Test
    fun planIsOnePostWithTheRoundedPointsAndNoOriginKind() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = TransitBffClient(mockHttpClient { request ->
            requests += request
            jsonResponse(OPENAPI_EXAMPLE)
        }, endpoint())

        val result = client.planJourney(CityId("demo"), query())

        assertIs<TransitLoadResult.Data<*>>(result)
        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("/v1/cities/demo/journeys/plan", request.url.encodedPath)
        assertTrue(request.url.parameters.isEmpty(), "Coordinates must never travel in the URL")
        val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
        assertEquals(setOf("from", "to", "maxTransfers", "transferBufferSeconds", "locale"), body.keys, "mode is no longer sent")
        assertEquals("41.7151", body.getValue("from").jsonObject.getValue("latitude").jsonPrimitive.content)
        assertEquals("ka", body.getValue("locale").jsonPrimitive.content)
        assertEquals("120", body.getValue("transferBufferSeconds").jsonPrimitive.content)
    }

    @Test
    fun openApiExampleMapsEveryAdditiveField() = runTest {
        val client = TransitBffClient(mockHttpClient { jsonResponse(OPENAPI_EXAMPLE) }, endpoint())

        val page = (client.planJourney(CityId("demo"), query()) as TransitLoadResult.Data).value

        assertEquals(ArrivalSource.ClientEstimate, page.source)
        val journey = page.items.single()
        assertEquals(1260, journey.durationSeconds)
        assertEquals(520.0, journey.walkingDistanceMeters)
        assertEquals(JourneyTimingQuality.Live, journey.timingQuality)
        assertEquals(listOf(JourneyLabel.Optimal, JourneyLabel.Fastest), journey.labels)
        assertEquals(listOf(JourneySegmentMode.Walk, JourneySegmentMode.Transit, JourneySegmentMode.Walk), journey.segments.map { it.mode })
        val ride = journey.segments[1]
        assertEquals(5, ride.intermediateStopCount)
        assertEquals(120, ride.waitSeconds)
        assertEquals(JourneyWaitBasis.LiveVehicle, ride.waitBasis)
        assertEquals(VehicleId("demo:fixture:vehicle:blue-01"), ride.vehicleId)
        assertEquals(2100.0, ride.distanceMeters)
        assertEquals(false, ride.geometryApproximate)
        assertEquals("o~a~Fg`toG}EiL", journey.segments[0].geometry)
        assertEquals(true, journey.segments[0].geometryApproximate)
    }

    @Test
    fun unknownAdditiveEnumsDegradeToUnknownAndOneMalformedOptionIsDropped() = runTest {
        val body = OPENAPI_EXAMPLE
            .replace("\"timingQuality\":\"LIVE\"", "\"timingQuality\":\"PREDICTED\"")
            .replace("\"waitBasis\":\"LIVE_VEHICLE\"", "\"waitBasis\":\"TIMETABLE\"")
            .replace("\"labels\":[\"OPTIMAL\",\"FASTEST\"]", "\"labels\":[\"CHEAPEST\",\"FASTEST\",\"FASTEST\"]")
            .replace("\"items\":[{", "\"items\":[{\"id\":\"bad\",\"departureAt\":\"x\",\"arrivalAt\":\"y\",\"transfers\":0,\"legs\":[]},{")
        val client = TransitBffClient(mockHttpClient { jsonResponse(body) }, endpoint())

        val page = (client.planJourney(CityId("demo"), query()) as TransitLoadResult.Data).value

        val journey = page.items.single()
        assertNull(journey.timingQuality)
        assertNull(journey.segments[1].waitBasis, "An unknown wait basis must never be presented as live")
        assertEquals(listOf(JourneyLabel.Fastest), journey.labels, "Unknown labels are ignored, not a failure")
    }

    @Test
    fun documentedErrorsMapToTypedFailuresWithoutRetryingThePost() = runTest {
        var calls = 0
        val statuses = mapOf(
            HttpStatusCode.NotImplemented to "CAPABILITY_NOT_AVAILABLE",
            HttpStatusCode.BadRequest to "INVALID_ARGUMENT",
            HttpStatusCode.ServiceUnavailable to "UPSTREAM_UNAVAILABLE",
            HttpStatusCode.TooManyRequests to "RATE_LIMITED",
        )
        val failures = statuses.map { (status, code) ->
            val client = TransitBffClient(mockHttpClient {
                calls++
                jsonResponse(errorJson(code), status)
            }, endpoint())
            (client.planJourney(CityId("demo"), query()) as TransitLoadResult.Failure).error
        }

        assertEquals(statuses.size, calls, "A plan POST is never replayed")
        assertIs<TransitFailure.CapabilityUnavailable>(failures[0])
        assertIs<TransitFailure.InvalidArgument>(failures[1])
        assertIs<TransitFailure.UpstreamUnavailable>(failures[2])
        assertIs<TransitFailure.RateLimited>(failures[3])
    }

    @Test
    fun emptyItemsMeanNothingFoundAndInvalidArgumentsNeverReachTheNetwork() = runTest {
        var calls = 0
        val client = TransitBffClient(mockHttpClient {
            calls++
            jsonResponse("""{"items":[],"observedAt":"2030-01-01T00:00:00Z","source":"CLIENT_ESTIMATE","realtime":false,"stale":false}""")
        }, endpoint())

        val empty = client.planJourney(CityId("demo"), query())
        val invalid = client.planJourney(CityId("demo"), query().copy(maxTransfers = 3))
        val unconfigured = TransitBffClient(mockHttpClient { error("No request expected") }, null)
            .planJourney(CityId("demo"), query())

        assertTrue((empty as TransitLoadResult.Data).value.items.isEmpty())
        assertIs<TransitFailure.InvalidArgument>((invalid as TransitLoadResult.Failure).error)
        assertIs<TransitFailure.Configuration>((unconfigured as TransitLoadResult.Failure).error)
        assertEquals(1, calls)
    }

    private fun query() = JourneyPlanQuery(
        from = GeoPoint(41.7151, 44.8271),
        to = GeoPoint(41.7295, 44.8421),
        maxTransfers = 2,
        transferBufferSeconds = 120,
        locale = TransitLocale.Georgian,
    )

    private fun endpoint() = BffEndpointConfiguration("https://bff.example")

    private fun mockHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())),
    )

    private fun errorJson(code: String) =
        """{"error":{"code":"$code","message":"contract message","requestId":"req-123"}}"""

    private companion object {
        // The documented 200 example of planJourneyOptions in docs/openapi/transit-bff-v1.yaml.
        const val OPENAPI_EXAMPLE = """{"items":[{"id":"demo:fixture:journey:blue-direct","departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:21:00Z","transfers":0,"durationSeconds":1260,"walkingDistanceMeters":520,"timingQuality":"LIVE","labels":["OPTIMAL","FASTEST"],"legs":[{"routeId":"demo:fixture:route:blue","directionId":"demo:fixture:direction:blue-outbound","fromStopId":"demo:fixture:stop:center","toStopId":"demo:fixture:stop:park","departureAt":"2030-01-01T00:06:00Z","arrivalAt":"2030-01-01T00:17:00Z"}],"segments":[{"departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:04:00Z","mode":"WALK","toStopId":"demo:fixture:stop:center","fromPosition":{"latitude":41.7151,"longitude":44.8271},"toPosition":{"latitude":41.7162,"longitude":44.8292},"distanceMeters":300,"geometry":"o~a~Fg`toG}EiL","geometryApproximate":true},{"departureAt":"2030-01-01T00:06:00Z","arrivalAt":"2030-01-01T00:17:00Z","mode":"TRANSIT","routeId":"demo:fixture:route:blue","directionId":"demo:fixture:direction:blue-outbound","fromStopId":"demo:fixture:stop:center","toStopId":"demo:fixture:stop:park","fromPosition":{"latitude":41.7162,"longitude":44.8292},"toPosition":{"latitude":41.7281,"longitude":44.8402},"distanceMeters":2100,"geometry":"mebs~Fu|uoGwiAke@","geometryApproximate":false,"intermediateStopCount":5,"waitSeconds":120,"waitBasis":"LIVE_VEHICLE","vehicleId":"demo:fixture:vehicle:blue-01"},{"departureAt":"2030-01-01T00:17:00Z","arrivalAt":"2030-01-01T00:21:00Z","mode":"WALK","fromStopId":"demo:fixture:stop:park","fromPosition":{"latitude":41.7281,"longitude":44.8402},"toPosition":{"latitude":41.7295,"longitude":44.8421},"distanceMeters":220,"geometry":"eocs~Fsavo`AsGyJ","geometryApproximate":true}]}],"observedAt":"2030-01-01T00:00:00Z","source":"CLIENT_ESTIMATE","realtime":true,"stale":false}"""
    }
}
