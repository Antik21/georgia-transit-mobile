package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.ServiceFailure
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.TtcActivationConfig
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.ProviderCallObservability
import com.denis.georgiatransit.bff.observability.ProviderEvent
import com.denis.georgiatransit.bff.observability.ProviderTelemetryLabels
import com.denis.georgiatransit.bff.observability.TelemetryOperation
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.service.TransitService
import com.denis.georgiatransit.bff.transitBffModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtcTransitProviderAdapterTest {
    @Test
    fun `routes map localized directions colors and send only bounded authenticated GET`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                jsonResponse("[$routeJson]")
            },
        )
        try {
            val routes = adapter.routes(locale = "ka", mode = "bus")

            assertEquals(1, routes.size)
            val route = routes.single()
            assertEquals(publicId("route", "1:route:7"), route.id)
            assertEquals(providerSuffix("1:route:7"), route.providerId)
            assertEquals("7", route.shortName)
            assertEquals("#FFCC00", route.color)
            assertEquals("#000000", route.textColor)
            assertEquals("bus", route.mode)
            assertEquals(listOf("Rustaveli", "Station Square"), route.directions.map { it.headsign.ka })

            val request = requests.single()
            assertEquals("GET", request.method.value)
            assertEquals("/api/v2/routes", request.url.encodedPath)
            assertEquals("BUS", request.url.parameters["modes"])
            assertEquals("ka", request.url.parameters["locale"])
            assertEquals(TEST_KEY, request.headers["X-Api-Key"])
            assertEquals(listOf("X-Api-Key"), request.headers.names().filter { it.equals("X-Api-Key", true) })
            assertFalse(request.url.toString().contains(TEST_KEY))
            assertFalse(adapter.city.attribution.any())

            assertTrue(adapter.routes("en", "subway").isEmpty())
            assertEquals(1, requests.size, "unsupported modes must not reach TTC")
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `bulk stop directory is one call allows empty memberships and filters non bus modes`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                jsonResponse(
                    """[
                        ${stopJson("1:stop:10", code = "10", mode = "BUS", lat = "41.70001", lon = 44.8)},
                        ${stopJson("gondola", code = "G", mode = "GONDOLA")},
                        ${stopJson("metro", code = "M", mode = "SUBWAY")},
                        ${stopJson("number-coordinates", code = null, mode = "BUS", lat = 41.71, lon = "44.81")}
                    ]""".trimIndent(),
                )
            },
        )
        try {
            val stops = adapter.stopDirectory("ru")

            assertEquals(2, stops.size)
            assertTrue(stops.all { it.routeIds.isEmpty() && it.mode == "bus" })
            assertEquals("10", stops.first().code)
            assertEquals(providerSuffix("number-coordinates"), stops.last().code)
            assertEquals("en", requests.single().url.parameters["locale"])
            assertEquals("/api/v2/stops", requests.single().url.encodedPath)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `empty and night service datasets stay empty while invalid provider colors use safe contrast`() = runTest {
        val responses = java.util.ArrayDeque<String>()
        val adapter = adapter(RecordingEngine { jsonResponse(responses.removeFirst()) })
        try {
            responses += "[]"
            assertTrue(adapter.routes("en", "bus").isEmpty())
            responses += "[]"
            assertTrue(adapter.stopDirectory("en").isEmpty())
            responses += "[]"
            assertTrue(
                adapter.vehicles(
                    publicId("route", "night:service"),
                    publicDirectionId("night:service", true),
                ).items.isEmpty(),
            )

            responses += "[${routeJson.replace("ffcc00", "not-a-color")}]"
            val fallback = adapter.route(publicId("route", "1:route:7"), "ru")
            assertEquals("#0057B8", fallback.color)
            assertEquals("#FFFFFF", fallback.textColor)
            assertEquals("Airport Line", fallback.longName.ru)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `route stop and shape calls preserve 1 prefix direction and precision five`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                when {
                    request.url.encodedPath.endsWith("/stops") -> jsonResponse("[${stopJson("1:stop:10")},${stopJson("1:stop:11") }]")
                    request.url.encodedPath.endsWith("/polyline") -> jsonResponse("""{"encodedValue":"_p~iF~ps|U"}""")
                    else -> error("unexpected request ${request.url}")
                }
            },
        )
        val routeId = publicId("route", "1:route:7")
        val directionId = publicDirectionId("1:route:7", forward = true)
        try {
            val stops = adapter.directionStops(routeId, directionId, "en")
            val shape = adapter.shape(routeId, directionId)

            assertEquals(2, stops.size)
            assertTrue(stops.all { it.routeIds == listOf(routeId) })
            assertEquals(5, shape.precision)
            assertEquals("_p~iF~ps|U", shape.value)
            requests.forEach { request ->
                assertTrue(request.url.encodedPath.contains("/routes/1:route:7/"), request.url.encodedPath)
                assertEquals("true", request.url.parameters["forward"])
            }
            assertEquals("en", requests.first().url.parameters["locale"])

            assertFailsWith<ProviderInvalidArgument> {
                adapter.shape(routeId, publicDirectionId("other", true))
            }
            assertFailsWith<ProviderInvalidArgument> {
                adapter.shape(routeId, publicId("direction", "1:route:7\u0000UNKNOWN"))
            }
            assertEquals(2, requests.size, "mismatched direction IDs must not reach TTC")
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `public IDs round trip literal 1 prefix and reject slash injection before HTTP`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                jsonResponse("[${stopJson("1:stop:10") }]")
            },
        )
        try {
            adapter.directionStops(
                publicId("route", "1:route:7"),
                publicDirectionId("1:route:7", true),
                "en",
            )
            assertEquals("/api/v2/routes/1:route:7/stops", requests.single().url.encodedPath)
            assertFalse(requests.single().url.encodedPath.contains("1:1:"))

            assertFailsWith<ProviderRouteNotFound> {
                adapter.directionStops(publicId("route", "route/escape"), publicDirectionId("route/escape", true), "en")
            }
            assertEquals(1, requests.size)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `arrivals resolve routes and preserve realtime and schedule semantics`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                when {
                    request.url.encodedPath.endsWith("/arrival-times") -> jsonResponse(
                        """[
                            {"shortName":"7","headsign":"Airport","realtime":true,"realtimeArrivalMinutes":"3","scheduledArrivalMinutes":4},
                            {"shortName":"7","headsign":"Depot","realtime":false,"realtimeArrivalMinutes":9,"scheduledArrivalMinutes":"8"}
                        ]""".trimIndent(),
                    )
                    request.url.encodedPath.endsWith("/routes") -> jsonResponse("[$routeJson]")
                    else -> jsonResponse(stopJson("1:stop:10"))
                }
            },
        )
        val stopId = publicId("stop", "1:stop:10")
        try {
            val page = adapter.arrivals(stopId, 40, "ka")

            assertEquals(2, page.items.size)
            assertEquals(ArrivalSource.OFFICIAL_REALTIME, page.source)
            assertEquals("2030-01-01T00:03:00Z", page.items.first().expectedAt)
            assertEquals("2030-01-01T00:04:00Z", page.items.first().scheduledAt)
            assertEquals(3, page.items.first().expectedInMinutes)
            assertTrue(page.items.first().realtime)
            assertNull(page.items.last().expectedAt)
            assertNull(page.items.last().expectedInMinutes)
            assertFalse(page.items.last().realtime)
            assertEquals(ArrivalSource.SCHEDULE, page.items.last().source)
            assertTrue(page.items.all { it.routeId == publicId("route", "1:route:7") })
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.url.encodedPath.contains("/stops/1:stop:10") })
            val arrivalRequest = requests.single { it.url.encodedPath.endsWith("/arrival-times") }
            assertEquals("false", arrivalRequest.url.parameters["ignoreScheduledArrivalTimes"])
            assertEquals("ka", arrivalRequest.url.parameters["locale"])
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `arrivals reject negative malformed IDs and ambiguous short name mapping`() = runTest {
        suspend fun fails(arrivals: String, routes: String = "[$routeJson]") {
            val adapter = adapter(
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/arrival-times") -> jsonResponse(arrivals)
                        request.url.encodedPath.endsWith("/routes") -> jsonResponse(routes)
                        else -> jsonResponse(stopJson("1:stop:10"))
                    }
                },
            )
            try {
                assertFailsWith<ProviderBadGateway> { adapter.arrivals(publicId("stop", "1:stop:10"), 1, "en") }
            } finally {
                adapter.close()
            }
        }

        fails("""[{"shortName":"7","headsign":"A","realtime":true,"realtimeArrivalMinutes":-1,"scheduledArrivalMinutes":2}]""")
        fails("""[{"shortName":"7","headsign":"A","realtime":"true","realtimeArrivalMinutes":1,"scheduledArrivalMinutes":2}]""")
        fails("""[{"shortName":7,"headsign":"A","realtime":true,"realtimeArrivalMinutes":1,"scheduledArrivalMinutes":2}]""")
        fails(
            """[{"shortName":"7","headsign":"A","realtime":true,"realtimeArrivalMinutes":1,"scheduledArrivalMinutes":2}]""",
            "[$routeJson,$routeJson]",
        )
    }

    @Test
    fun `vehicle tracker keeps plausible identity and rejects collisions heading and speed matches`() = runTest {
        val clock = MutableClock(NOW)
        val response = java.util.ArrayDeque<String>()
        val adapter = adapter(
            RecordingEngine { jsonResponse(response.removeFirst()) },
            clock = clock,
        )
        val routeId = publicId("route", "1:route:7")
        val direction = publicDirectionId("1:route:7", true)
        try {
            response += positionsJson(positionJson(41.70000, 44.80000, 10, "1:stop:10"))
            val first = adapter.vehicles(routeId, direction).items.single()
            clock.advanceSeconds(10)
            response += positionsJson(positionJson(41.70010, 44.80010, 12, "1:stop:10"))
            val plausible = adapter.vehicles(routeId, direction).items.single()
            assertEquals(first.id, plausible.id)
            assertEquals(0, plausible.ageSeconds)
            assertEquals(publicId("stop", "1:stop:10"), plausible.nextStopId)

            clock.advanceSeconds(10)
            val duplicate = positionJson(41.701, 44.801, 20, "1:stop:11")
            response += positionsJson(duplicate, duplicate)
            assertTrue(adapter.vehicles(routeId, direction).items.isEmpty())

            clock.advanceSeconds(1)
            response += positionsJson(positionJson(41.70011, 44.80011, 200, "1:stop:10"))
            val headingMismatch = adapter.vehicles(routeId, direction).items.single()
            assertNotEquals(first.id, headingMismatch.id)

            clock.advanceSeconds(1)
            response += positionsJson(positionJson(41.72, 44.82, 200, "1:stop:10"))
            val speedMismatch = adapter.vehicles(routeId, direction).items.single()
            assertNotEquals(headingMismatch.id, speedMismatch.id)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `journeys require complete endpoints and uniquely proven forward or backward order`() = runTest {
        val queue = java.util.ArrayDeque<String>()
        val requests = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            RecordingEngine { request ->
                requests += request
                jsonResponse(queue.removeFirst())
            },
        )
        try {
            queue += planJson()
            queue += "[${stopJson("1:stop:a")},${stopJson("1:stop:b")}]"
            queue += "[${stopJson("1:stop:b")},${stopJson("1:stop:a")}]"
            val page = adapter.journeyPage(journeyQuery())

            assertEquals(1, page.items.size)
            assertEquals(ArrivalSource.AGGREGATOR_REALTIME, page.source)
            assertTrue(page.realtime)
            val journey = page.items.single()
            assertEquals(listOf(JourneySegmentMode.WALK, JourneySegmentMode.TRANSIT), journey.segments.map { it.mode })
            assertEquals(publicDirectionId("1:route:7", true), journey.legs.single().directionId)
            assertEquals(publicId("stop", "1:stop:a"), journey.legs.single().fromStopId)
            assertEquals("leaveNow", requests.first().url.parameters["departMode"])
            assertEquals("WALK,BUS", requests.first().url.parameters["modes"])
            assertEquals("quick", requests.first().url.parameters["optimize"])

            queue += planJson()
            queue += "[${stopJson("1:stop:a")},${stopJson("1:stop:b")}]"
            queue += "[${stopJson("1:stop:a")},${stopJson("1:stop:b")}]"
            assertTrue(adapter.journeyPage(journeyQuery()).items.isEmpty(), "ambiguous F/B evidence must be dropped")

            queue += planJson(intermediateStops = "null")
            assertTrue(adapter.journeyPage(journeyQuery()).items.isEmpty(), "incomplete transit endpoints must be dropped")
            assertFailsWith<ProviderInvalidArgument> {
                adapter.journeyPage(journeyQuery(departureAt = NOW.plusSeconds(301)))
            }
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `journey direction stop lookups are deduplicated per route per request only`() = runTest {
        fun transitLeg(start: String, end: String): String =
            """{"from":$fromTransitPlace,"to":$toTransitPlace,"startTime":"$start","endTime":"$end","mode":"BUS","route":$routeJson,"realTime":true,"distance":1000,"intermediateStops":[$fromPlanStop,$toPlanStop]}"""
        val repeatedPlan =
            """{"itineraries":[
                {"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","duration":600,"legs":[
                    ${transitLeg("2030-01-01T00:00:00Z", "2030-01-01T00:05:00Z")},
                    ${transitLeg("2030-01-01T00:05:00Z", "2030-01-01T00:10:00Z")}
                ]},
                {"startTime":"2030-01-01T00:01:00Z","endTime":"2030-01-01T00:07:00Z","duration":360,"legs":[
                    ${transitLeg("2030-01-01T00:01:00Z", "2030-01-01T00:07:00Z")}
                ]}
            ]}""".trimIndent()
        val routeStopDirections = mutableListOf<String>()
        val adapter = adapter(
            RecordingEngine { request ->
                if (request.url.encodedPath.endsWith("/plan")) {
                    jsonResponse(repeatedPlan)
                } else {
                    routeStopDirections += requireNotNull(request.url.parameters["forward"])
                    val stops = if (request.url.parameters["forward"] == "true") {
                        "[${stopJson("1:stop:a")},${stopJson("1:stop:b")}]"
                    } else {
                        "[${stopJson("1:stop:b")},${stopJson("1:stop:a")}]"
                    }
                    jsonResponse(stops)
                }
            },
        )
        try {
            assertEquals(2, adapter.journeyPage(journeyQuery()).items.size)
            assertEquals(listOf("true", "false"), routeStopDirections)

            assertEquals(2, adapter.journeyPage(journeyQuery()).items.size)
            assertEquals(
                listOf("true", "false", "true", "false"),
                routeStopDirections,
                "direction stop evidence must be reused within one request but fetched again for the next request",
            )
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `walking estimate accepts only internally consistent all walk itineraries`() = runTest {
        val responses = java.util.ArrayDeque<String>()
        val adapter = adapter(RecordingEngine { jsonResponse(responses.removeFirst()) })
        try {
            responses += walkingPlanJson(duration = 600, legEnd = "2030-01-01T00:10:00Z")
            val estimate = adapter.walkingEstimate(WalkingQuery(GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), "en"))
            assertEquals(1250.5, estimate.distanceMeters)
            assertEquals(600, estimate.durationSeconds)
            assertEquals(NOW.toString(), estimate.observedAt)

            responses += walkingPlanJson(duration = 599, legEnd = "2030-01-01T00:10:00Z")
            assertFailsWith<ProviderCapabilityUnavailable> {
                adapter.walkingEstimate(WalkingQuery(GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), "en"))
            }
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `client bounds retries response bodies rate starts and never exposes credential`() = runTest {
        val attempts = AtomicInteger()
        val jitterBounds = mutableListOf<Long>()
        val events = mutableListOf<ProviderEvent>()
        val client = client(
            RecordingEngine { request ->
                assertEquals(TEST_KEY, request.headers["X-Api-Key"])
                when (attempts.incrementAndGet()) {
                    1 -> jsonResponse("provider-body-$TEST_KEY", HttpStatusCode.InternalServerError)
                    2 -> jsonResponse("provider-body-$TEST_KEY", HttpStatusCode.TooManyRequests, retryAfter = "1")
                    else -> jsonResponse("[$routeJson]")
                }
            },
            maximumRetries = 2,
            observability = RecordingObservability(events),
            jitter = TtcRetryJitter { upper -> jitterBounds += upper; upper },
        )
        try {
            assertEquals(1, client.routes("en", TelemetryOperation.LIST_ROUTES).size)
            assertEquals(3, attempts.get())
            assertEquals(listOf(62L, 125L), jitterBounds)
            assertEquals(listOf(ProviderEvent.RETRY, ProviderEvent.RETRY), events)
        } finally {
            client.close()
        }

        val overLimit = client(
            RecordingEngine { jsonResponse("x".repeat(256 * 1024 + 1), contentLength = 256 * 1024 + 1) },
        )
        try {
            val failure = assertFailsWith<ProviderNormalizedSchemaFailure> {
                overLimit.routes("en", TelemetryOperation.LIST_ROUTES)
            }
            assertFalse(failure.message.orEmpty().contains(TEST_KEY))
        } finally {
            overLimit.close()
        }

        val starts = client(RecordingEngine { jsonResponse("[$routeJson]") }, maximumStartsPerMinute = 1)
        try {
            starts.routes("en", TelemetryOperation.LIST_ROUTES)
            val failure = assertFailsWith<ProviderRateLimited> {
                starts.routes("en", TelemetryOperation.LIST_ROUTES)
            }
            assertTrue(failure.retryAfterSeconds in 1..60)
        } finally {
            starts.close()
        }
    }

    @Test
    fun `client maps timeout not found validation and terminal rate limit without provider details`() = runTest {
        val timeout = client(RecordingEngine { request -> throw HttpRequestTimeoutException(request) })
        try {
            assertFailsWith<ProviderTimeout> { timeout.routes("en", TelemetryOperation.LIST_ROUTES) }
        } finally {
            timeout.close()
        }

        suspend fun failure(status: HttpStatusCode, operation: TelemetryOperation): ProviderFailure {
            val client = client(
                RecordingEngine {
                    jsonResponse("provider-body-$TEST_KEY", status, retryAfter = "17")
                },
            )
            return try {
                assertFailsWith<ProviderFailure> { client.routes("en", operation) }
            } finally {
                client.close()
            }
        }
        assertIs<ProviderRouteNotFound>(failure(HttpStatusCode.NotFound, TelemetryOperation.LIST_ROUTES))
        assertIs<ProviderInvalidArgument>(failure(HttpStatusCode.BadRequest, TelemetryOperation.LIST_ROUTES))
        assertIs<ProviderUnavailable>(failure(HttpStatusCode.ServiceUnavailable, TelemetryOperation.LIST_ROUTES))
        val rateLimited = assertIs<ProviderRateLimited>(
            failure(HttpStatusCode.TooManyRequests, TelemetryOperation.LIST_ROUTES),
        )
        assertEquals(17, rateLimited.retryAfterSeconds)
        assertFalse(rateLimited.message.orEmpty().contains(TEST_KEY))
    }

    @Test
    fun `TTC participates in the shared service circuit exactly once`() = runTest {
        val attempts = AtomicInteger()
        val adapter = adapter(
            RecordingEngine {
                attempts.incrementAndGet()
                jsonResponse("untrusted", HttpStatusCode.ServiceUnavailable)
            },
        )
        val observability = BffObservability(
            config = BffConfig.fromEnvironment(
                mapOf(
                    "BFF_CIRCUIT_FAILURE_THRESHOLD" to "2",
                    "BFF_CIRCUIT_WINDOW_SECONDS" to "10",
                    "BFF_CIRCUIT_OPEN_SECONDS" to "5",
                ),
            ),
            allowedProviders = setOf("tbilisi" to TelemetryProvider.TTC),
            clock = CLOCK,
        )
        val service = TransitService(
            capabilitySnapshots = IntrinsicCapabilitySnapshotSource(ProviderRegistry(listOf(adapter))),
            directoryCacheTtlSeconds = 3_600,
            shapeCacheTtlSeconds = 3_600,
            realtimeSingleFlightSeconds = 15,
            observability = observability,
        )
        try {
            repeat(3) {
                assertFailsWith<ServiceFailure> { service.routes("tbilisi", "en", "bus") }
            }
            assertEquals(2, attempts.get(), "the third call must be rejected by the open shared circuit")
            assertTrue(observability.render().contains("provider=\"ttc\""))
            assertTrue(observability.render().contains("event=\"circuit_opened\""))
        } finally {
            service.close()
            adapter.close()
        }
    }

    @Test
    fun `client cancellation releases concurrency permit and redirects do not carry key onward`() = runTest {
        val bodyReadStarted = CompletableDeferred<Unit>()
        val blockedBody = ByteChannel(autoFlush = true)
        val calls = AtomicInteger()
        val engine = RecordingEngine {
            if (calls.incrementAndGet() == 1) {
                responseWithBody(ReadStartedChannel(blockedBody, bodyReadStarted))
            } else {
                jsonResponse("[$routeJson]")
            }
        }
        val client = client(engine, maximumConcurrentRequests = 1)
        try {
            val active = async { client.routes("en", TelemetryOperation.LIST_ROUTES) }
            // This barrier is completed from ByteReadChannel.awaitContent itself, after the engine
            // returned headers and TtcClient actually started consuming the still-open body.
            bodyReadStarted.await()
            assertFailsWith<ProviderRateLimited> { client.routes("en", TelemetryOperation.LIST_ROUTES) }
            active.cancelAndJoin()
            assertEquals(1, client.routes("en", TelemetryOperation.LIST_ROUTES).size)
            assertEquals(2, calls.get())
        } finally {
            client.close()
        }

        val requests = mutableListOf<HttpRequestData>()
        val redirectClient = client(
            RecordingEngine { request ->
                requests += request
                jsonResponse("redirect", HttpStatusCode.Found, location = "https://evil.example/collect")
            },
        )
        try {
            assertFailsWith<ProviderBadGateway> { redirectClient.routes("en", TelemetryOperation.LIST_ROUTES) }
            assertEquals(1, requests.size)
            assertEquals("ttc.example", requests.single().url.host)
        } finally {
            redirectClient.close()
        }
    }

    @Test
    fun `malformed null and oversized provider DTOs fail closed`() = runTest {
        val malformedBodies = listOf(
            "not-json",
            "{}",
            "[null]",
            """[{"id":7,"shortName":"7","longName":"Route","color":"fff000","mode":"BUS"}]""",
            """[{"id":"r","shortName":"7","longName":"Route","color":"fff000","mode":null}]""",
            "[${routeJson.replace("\"mode\":\"BUS\"", "\"mode\":\"SUBWAY\"")}]",
        )
        malformedBodies.forEach { body ->
            val client = client(RecordingEngine { jsonResponse(body) })
            try {
                assertFailsWith<ProviderBadGateway>(body.take(20)) {
                    client.routes("en", TelemetryOperation.LIST_ROUTES)
                }
            } finally {
                client.close()
            }
        }

        val tooMany = "[" + List(513) { routeJson }.joinToString(",") + "]"
        val client = client(RecordingEngine { jsonResponse(tooMany) })
        try {
            assertFailsWith<ProviderNormalizedSchemaFailure> {
                client.routes("en", TelemetryOperation.LIST_ROUTES)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `activated application registers TTC without making a startup provider call`() = testApplication {
        application { transitBffModule(BffConfig.fromEnvironment(ttcEnvironment())) }

        val health = client.get("/healthz")
        assertEquals(HttpStatusCode.ServiceUnavailable, health.status)
        val cities = client.get("/v1/cities")
        assertEquals(HttpStatusCode.OK, cities.status)
        val body = cities.bodyAsText()
        assertEquals("[]", body)
        assertFalse(body.contains(TEST_KEY))
    }

    private fun adapter(
        engine: RecordingEngine,
        clock: Clock = CLOCK,
    ): TtcTransitProviderAdapter {
        val activation = activation()
        return TtcTransitProviderAdapter(activation, client(engine, activation = activation), clock)
    }

    private fun client(
        engine: RecordingEngine,
        activation: TtcActivationConfig = activation(),
        maximumRetries: Int = 0,
        maximumConcurrentRequests: Int = 2,
        maximumStartsPerMinute: Int = 120,
        observability: ProviderCallObservability = RecordingObservability(mutableListOf()),
        jitter: TtcRetryJitter = TtcRetryJitter { 0 },
    ): TtcClient {
        val effective = if (
            maximumRetries == activation.maximumRetries &&
            maximumConcurrentRequests == activation.maximumConcurrentRequests &&
            maximumStartsPerMinute == activation.maximumStartsPerMinute
        ) {
            activation
        } else {
            activation(
                maximumRetries = maximumRetries,
                maximumConcurrentRequests = maximumConcurrentRequests,
                maximumStartsPerMinute = maximumStartsPerMinute,
            )
        }
        return TtcClient(
            activation = effective,
            httpClient = HttpClient(engine) {
                expectSuccess = false
                followRedirects = false
            },
            observability = observability,
            retryJitter = jitter,
        )
    }

    private fun activation(
        maximumRetries: Int = 0,
        maximumConcurrentRequests: Int = 2,
        maximumStartsPerMinute: Int = 120,
    ): TtcActivationConfig = TtcActivationConfig.fromEnvironment(
        ttcEnvironment() + mapOf(
            "TTC_MAXIMUM_RETRIES" to maximumRetries.toString(),
            "TTC_MAXIMUM_CONCURRENT_REQUESTS" to maximumConcurrentRequests.toString(),
            "TTC_MAXIMUM_STARTS_PER_MINUTE" to maximumStartsPerMinute.toString(),
        ),
    )

    private fun ttcEnvironment(): Map<String, String> = mapOf(
        "TTC_ENABLED" to "true",
        "TTC_BASE_URL" to "https://ttc.example/api/v2",
        "TTC_API_KEY" to TEST_KEY,
    )

    private fun publicId(entity: String, raw: String): String =
        "tbilisi:ttc:$entity:${providerSuffix(raw)}"

    private fun publicDirectionId(rawRoute: String, forward: Boolean): String =
        publicId("direction", "$rawRoute\u0000${if (forward) "F" else "B"}")

    private fun providerSuffix(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    private fun stopJson(
        id: String,
        code: String? = "10",
        mode: String = "BUS",
        lat: Any = 41.7,
        lon: Any = 44.8,
    ): String = """{"id":"$id","code":${code?.let { "\"$it\"" } ?: "null"},"name":"Central","lat":$lat,"lon":$lon,"vehicleMode":"$mode"}"""

    private fun positionJson(lat: Double, lon: Double, heading: Int, nextStopId: String?): String =
        """{"lat":$lat,"lon":$lon,"heading":$heading,"nextStopId":${nextStopId?.let { "\"$it\"" } ?: "null"}}"""

    private fun positionsJson(vararg positions: String): String = positions.joinToString(prefix = "[", postfix = "]")

    private fun journeyQuery(departureAt: Instant = NOW) = JourneyQuery(
        from = GeoPoint(41.7, 44.8),
        to = GeoPoint(41.8, 44.9),
        departureAt = departureAt,
        locale = "ka",
        maxTransfers = 2,
    )

    private fun planJson(intermediateStops: String = "[$fromPlanStop,$toPlanStop]"): String =
        """{"itineraries":[{"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","duration":600,"legs":[
            {"from":$fromPlace,"to":$middlePlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:02:00Z","mode":"WALK","route":null,"realTime":false,"distance":100,"intermediateStops":null},
            {"from":$fromTransitPlace,"to":$toTransitPlace,"startTime":"2030-01-01T00:02:00Z","endTime":"2030-01-01T00:10:00Z","mode":"BUS","route":$routeJson,"realTime":true,"distance":3000,"intermediateStops":$intermediateStops}
        ]}]}""".trimIndent()

    private fun walkingPlanJson(duration: Int, legEnd: String): String =
        """{"itineraries":[{"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","duration":$duration,"legs":[
            {"from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"$legEnd","mode":"WALK","route":null,"realTime":false,"distance":1250.5,"intermediateStops":null}
        ]}]}""".trimIndent()

    private class RecordingObservability(private val events: MutableList<ProviderEvent>) : ProviderCallObservability {
        override suspend fun <T> protectProviderCall(labels: ProviderTelemetryLabels, block: suspend () -> T): T = block()

        override fun recordEvent(labels: ProviderTelemetryLabels, event: ProviderEvent) {
            events += event
        }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    @OptIn(InternalAPI::class)
    private class ReadStartedChannel(
        private val delegate: ByteReadChannel,
        private val readStarted: CompletableDeferred<Unit>,
    ) : ByteReadChannel {
        override val closedCause: Throwable? get() = delegate.closedCause
        override val isClosedForRead: Boolean get() = delegate.isClosedForRead
        override val readBuffer: Source get() = delegate.readBuffer

        override suspend fun awaitContent(min: Int): Boolean {
            readStarted.complete(Unit)
            return delegate.awaitContent(min)
        }

        override fun cancel(cause: Throwable?) = delegate.cancel(cause)
    }

    @OptIn(InternalAPI::class)
    private class RecordingEngine(
        private val handler: suspend (HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) : HttpClientEngineBase("TtcTestEngine") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): io.ktor.client.request.HttpResponseData =
            withContext(dispatcher + callContext()) { handler(data) }
    }

    @OptIn(InternalAPI::class)
    private suspend fun jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        retryAfter: String? = null,
        contentLength: Int? = null,
        location: String? = null,
    ): io.ktor.client.request.HttpResponseData = io.ktor.client.request.HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(),
        headers = headersOf(
            *buildList {
                retryAfter?.let { add(HttpHeaders.RetryAfter to listOf(it)) }
                contentLength?.let { add(HttpHeaders.ContentLength to listOf(it.toString())) }
                location?.let { add(HttpHeaders.Location to listOf(it)) }
            }.toTypedArray(),
        ),
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(body.encodeToByteArray()),
        callContext = callContext(),
    )

    @OptIn(InternalAPI::class)
    private suspend fun responseWithBody(body: ByteReadChannel): io.ktor.client.request.HttpResponseData =
        io.ktor.client.request.HttpResponseData(
            statusCode = HttpStatusCode.OK,
            requestTime = GMTDate(),
            headers = headersOf(),
            version = HttpProtocolVersion.HTTP_1_1,
            body = body,
            callContext = callContext(),
        )

    private companion object {
        const val TEST_KEY = "test-only-ttc-key"
        val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        const val routeJson = """{"id":"1:route:7","shortName":"7","longName":"Airport Line","color":"ffcc00","mode":"BUS","longNames":{"forwardLongName":"Rustaveli","backwardLongName":"Station Square"}}"""
        const val fromPlace = """{"name":"Start","lat":41.7,"lon":44.8}"""
        const val middlePlace = """{"name":"Middle","lat":41.71,"lon":44.81}"""
        const val toPlace = """{"name":"End","lat":41.8,"lon":44.9}"""
        const val fromTransitPlace = """{"name":"Stop A","lat":41.71,"lon":44.81}"""
        const val toTransitPlace = """{"name":"Stop B","lat":41.72,"lon":44.82}"""
        const val fromPlanStop = """{"id":"1:stop:a","code":"A","name":"Stop A","lat":41.71,"lon":44.81,"vehicleMode":"BUS"}"""
        const val toPlanStop = """{"id":"1:stop:b","code":"B","name":"Stop B","lat":41.72,"lon":44.82,"vehicleMode":"BUS"}"""
    }
}
