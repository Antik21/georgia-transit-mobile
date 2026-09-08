package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.RequestRateLimited
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.TransitousActivationConfig
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransitousTransitProviderAdapterTest {
    @AfterTest
    fun clearGlobalBudgetBetweenTests() {
        // The production budget is deliberately process-wide. Clear only completed test attempts so
        // these hermetic mock-engine tests do not make each other look rate-limited.
        val singleton = Class.forName("com.denis.georgiatransit.bff.provider.TransitousUpstreamBudget")
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
        singleton.javaClass.getDeclaredField("starts").apply { isAccessible = true }
            .get(singleton)
            .let { starts -> (starts as java.util.ArrayDeque<*>).clear() }
    }

    @Test
    fun `stoptimes uses departure first page URL bounded n locale and redacted UA`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val engine = RecordingEngine { request ->
            requests += request
            jsonResponse(stopTimesJson(rawStopId = "central:one"))
        }
        val adapter = adapter(engine, approvedStopIds = setOf("central:one"))
        val publicStop = publicId("stop", "central:one")
        try {
            val page = adapter.arrivals(publicStop, limit = 100, locale = "KA")

            assertEquals(1, page.items.size)
            assertEquals("2030-01-01T00:02:00Z", page.items.single().scheduledAt)
            assertEquals("2030-01-01T00:03:00Z", page.items.single().expectedAt)
            assertTrue(page.items.single().realtime)
            assertTrue(page.items.single().cancelled)
            assertEquals(ArrivalSource.AGGREGATOR_REALTIME, page.source)
            assertEquals(publicId("route", "route/7"), page.items.single().routeId)

            val request = requests.single()
            assertEquals("/api/v6/stoptimes", request.url.encodedPath)
            assertEquals("central:one", request.url.parameters["stopId"])
            assertEquals("false", request.url.parameters["arriveBy"])
            assertEquals("LATER", request.url.parameters["direction"])
            assertEquals("20", request.url.parameters["n"])
            assertEquals("ka", request.url.parameters["language"])
            assertEquals("TRANSIT", request.url.parameters["mode"])
            assertEquals("true", request.url.parameters["exactRadius"])
            assertFalse(request.url.parameters.contains("page"))
            assertFalse(request.url.parameters.contains("searchWindow"))
            assertFalse(request.url.encodedPath.contains("map/trips"))
            assertEquals("GeorgiaTransitBff/test-1 (contact: transit-ops@example.com)", request.headers[HttpHeaders.UserAgent])
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `stoptimes preserves scheduled non-realtime and cancellation semantics`() = runTest {
        val adapter = adapter(
            RecordingEngine { jsonResponse(stopTimesJson("central:one", realTime = false, cancelled = false)) },
            approvedStopIds = setOf("central:one"),
        )
        try {
            val page = adapter.arrivals(publicId("stop", "central:one"), limit = 1, locale = "en")

            assertEquals(ArrivalSource.SCHEDULE, page.source)
            assertFalse(page.items.single().realtime)
            assertFalse(page.items.single().cancelled)
            assertEquals(ArrivalSource.SCHEDULE, page.items.single().source)
            assertEquals("2030-01-01T00:02:00Z", page.items.single().scheduledAt)
            assertEquals("2030-01-01T00:03:00Z", page.items.single().expectedAt)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `stoptimes rejects every blank or mismatched echoed stop ID as 502 without relabeling or LKG`() = runTest {
        val requestedRawStopId = "central:one"
        val requestedPublicStopId = publicId("stop", requestedRawStopId)
        val scenarios = listOf(
            StopIdScenario("response place blank", responseStopId = "", stopTimeStopIds = listOf(requestedRawStopId)),
            StopIdScenario("response place mismatch", responseStopId = "central:two", stopTimeStopIds = listOf(requestedRawStopId)),
            StopIdScenario(
                "a stop time place blank",
                responseStopId = requestedRawStopId,
                stopTimeStopIds = listOf(requestedRawStopId, ""),
            ),
            StopIdScenario(
                "a stop time place mismatch",
                responseStopId = requestedRawStopId,
                stopTimeStopIds = listOf(requestedRawStopId, "central:two"),
            ),
        )

        scenarios.forEach { scenario ->
            assertInvalidStopIdentityDoesNotUseExistingLkg(scenario, requestedRawStopId, requestedPublicStopId)
            assertInvalidStopIdentityDoesNotWriteLkg(scenario, requestedRawStopId, requestedPublicStopId)
        }
    }

    @Test
    fun `arrival stop IDs are reversible allowlisted and exact LKG keys alone become stale`() = runTest {
        val attempts = AtomicInteger()
        val engine = RecordingEngine {
            when (attempts.incrementAndGet()) {
                1 -> jsonResponse(stopTimesJson(rawStopId = "central:one"))
                else -> jsonResponse("provider-payload-secret", HttpStatusCode.TooManyRequests, retryAfter = "17")
            }
        }
        val adapter = adapter(engine, approvedStopIds = setOf("central:one", "central:two"))
        val central = publicId("stop", "central:one")
        try {
            val live = adapter.arrivals(central, limit = 2, locale = "en")
            val stale = adapter.arrivals(central, limit = 2, locale = "en")

            assertFalse(live.stale)
            assertTrue(stale.stale)
            assertEquals(live.items, stale.items)
            assertFailsWith<ProviderRateLimited> { adapter.arrivals(central, limit = 3, locale = "en") }
            assertFailsWith<ProviderRateLimited> { adapter.arrivals(central, limit = 2, locale = "ru") }
            assertFailsWith<ProviderStopNotFound> {
                adapter.arrivals(publicId("stop", "arbitrary:unapproved"), limit = 2, locale = "en")
            }
            assertFailsWith<ProviderStopNotFound> {
                adapter.arrivals("tbilisi:transitous:stop:not-base64", limit = 2, locale = "en")
            }
            assertEquals(4, attempts.get(), "arbitrary/rejected stop IDs must not reach upstream")
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `plan bounds approval and full itinerary normalization protect the BFF boundary`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val engine = RecordingEngine { request ->
            requests += request
            jsonResponse(planJson())
        }
        val approved = adapter(engine, routingApproved = true)
        val query = journeyQuery(maxTransfers = 6)
        try {
            val page = approved.journeyPage(query)

            assertEquals(ArrivalSource.AGGREGATOR_REALTIME, page.source)
            assertTrue(page.realtime)
            assertFalse(page.stale)
            assertEquals(
                listOf(JourneySegmentMode.WALK, JourneySegmentMode.OTHER, JourneySegmentMode.OTHER, JourneySegmentMode.TRANSIT),
                page.items.single().segments.map { it.mode },
            )
            assertEquals(1, page.items.single().legs.size)
            assertEquals(publicId("route", "route/7"), page.items.single().legs.single().routeId)

            val request = requests.single()
            assertEquals("/api/v6/plan", request.url.encodedPath)
            assertEquals("41.7,44.8", request.url.parameters["fromPlace"])
            assertEquals("41.8,44.9", request.url.parameters["toPlace"])
            assertEquals("500", request.url.parameters["radius"])
            assertEquals("false", request.url.parameters["arriveBy"])
            assertEquals("3", request.url.parameters["maxTransfers"])
            assertEquals("4", request.url.parameters["numItineraries"])
            assertEquals("ka", request.url.parameters["language"])
            assertFalse(request.url.encodedPath.contains("map/trips"))
            assertFalse(approved.city.capabilities.vehiclePositions)
            assertFailsWith<ProviderCapabilityUnavailable> {
                approved.vehicles(publicId("route", "route/7"), null)
            }
            assertEquals(1, requests.size, "vehicle paths must not call Transitous map/trips or any upstream endpoint")
        } finally {
            approved.close()
        }

        val disabledRequests = AtomicInteger()
        val disabled = adapter(RecordingEngine { disabledRequests.incrementAndGet(); jsonResponse(planJson()) })
        try {
            assertFalse(disabled.city.capabilities.tripPlanning)
            assertFailsWith<ProviderCapabilityUnavailable> { disabled.journeyPage(journeyQuery()) }
            assertEquals(0, disabledRequests.get())
        } finally {
            disabled.close()
        }
    }

    @Test
    fun `plan rejects out of bounds times unknown modes overlapping and incomplete provider legs`() = runTest {
        val calls = AtomicInteger()
        val adapter = adapter(RecordingEngine { calls.incrementAndGet(); jsonResponse(planJson()) }, routingApproved = true)
        try {
            assertFailsWith<ProviderInvalidArgument> {
                adapter.journeyPage(journeyQuery(from = GeoPoint(41.0, 44.8)))
            }
            assertFailsWith<ProviderInvalidArgument> {
                adapter.journeyPage(journeyQuery(departureAt = NOW.minusSeconds(301)))
            }
            assertFailsWith<ProviderInvalidArgument> {
                adapter.journeyPage(journeyQuery(departureAt = NOW.plusSeconds(86_401)))
            }
            assertEquals(0, calls.get())
        } finally {
            adapter.close()
        }

        listOf(
            planJson(legs = """[{"mode":"TELEPORT","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","realTime":false}]"""),
            planJson(legs = """[$walkLeg,{"mode":"WALK","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:01:00Z","endTime":"2030-01-01T00:04:00Z","realTime":false}]"""),
            planJson(legs = """[{"mode":"BUS","from":$fromStopPlace,"to":$toStopPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","realTime":false,"directionId":"out"}]"""),
            planJson(legs = "[]"),
        ).forEach { malformed ->
            val adapter = adapter(RecordingEngine { jsonResponse(malformed) }, routingApproved = true)
            try {
                assertFailsWith<ProviderBadGateway> { adapter.journeyPage(journeyQuery()) }
            } finally {
                adapter.close()
            }
        }
    }

    @Test
    fun `journey coordinates are request scoped and never use last known good`() = runTest {
        val attempts = AtomicInteger()
        val adapter = adapter(
            RecordingEngine {
                if (attempts.incrementAndGet() == 1) jsonResponse(planJson())
                else jsonResponse("secret", HttpStatusCode.TooManyRequests, retryAfter = "17")
            },
            routingApproved = true,
        )
        try {
            assertFalse(adapter.journeyPage(journeyQuery()).stale)
            assertFailsWith<ProviderRateLimited> { adapter.journeyPage(journeyQuery()) }
            assertEquals(2, attempts.get())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `walking plan is direct WALK only and picks fastest valid candidate`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val response = """{"direct":[
            {"duration":360,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:06:00Z","transfers":0,"id":"walk/slow","legs":[{"mode":"WALK","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:06:00Z","realTime":false,"duration":360,"distance":430.5}]},
            {"duration":300,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:05:00Z","transfers":0,"id":"walk/fast","legs":[{"mode":"WALK","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:05:00Z","realTime":false,"duration":300,"distance":410.0}]},
            {"duration":120,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:02:00Z","transfers":0,"id":"bus","legs":[{"mode":"BUS","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:02:00Z","realTime":false,"duration":120,"distance":500.0}]}
        ]}"""
        val adapter = adapter(RecordingEngine { request -> requests += request; jsonResponse(response) }, routingApproved = true)
        try {
            val estimate = adapter.walkingEstimate(WalkingQuery(GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), "RU"))

            assertEquals(410.0, estimate.distanceMeters)
            assertEquals(300L, estimate.durationSeconds)
            assertEquals(NOW.toString(), estimate.observedAt)
            val request = requests.single()
            assertEquals("/api/v6/plan", request.url.encodedPath)
            assertEquals("", request.url.parameters["transitModes"])
            assertEquals("WALK", request.url.parameters["directModes"])
            assertEquals("1800", request.url.parameters["maxDirectTime"])
            assertEquals("0", request.url.parameters["numItineraries"])
            assertEquals("ru", request.url.parameters["language"])
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `walking plan rejects non-walk invalid and absent direct candidates`() = runTest {
        val bodies = listOf(
            """{"direct":[]}""",
            """{"direct":[{"duration":0,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:00:00Z","transfers":0,"id":"bad","legs":[{"mode":"WALK","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:00:00Z","realTime":false,"distance":0}]}]}""",
            """{"direct":[{"duration":100,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:02:00Z","transfers":0,"id":"bike","legs":[{"mode":"BIKE","from":$fromPlace,"to":$toPlace,"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:02:00Z","realTime":false,"distance":500}]}]}""",
        )
        bodies.forEach { body ->
            val adapter = adapter(RecordingEngine { jsonResponse(body) }, routingApproved = true)
            try {
                assertFailsWith<ProviderCapabilityUnavailable> {
                    adapter.walkingEstimate(WalkingQuery(GeoPoint(41.7, 44.8), GeoPoint(41.8, 44.9), "en"))
                }
            } finally {
                adapter.close()
            }
        }
    }

    @Test
    fun `retry headers failure classes response cap and cancellation never expose provider payloads`() = runTest {
        assertRetryAfter("17", 17)
        assertRetryAfter("Fri, 01 Jan 2100 00:00:00 GMT", 86_400)

        assertIs<ProviderTimeout>(failureFrom(HttpStatusCode.RequestTimeout))
        assertIs<ProviderUnavailable>(failureFrom(HttpStatusCode.BadGateway))
        val timeoutEngine = RecordingEngine { request -> throw HttpRequestTimeoutException(request) }
        val timeoutClient = transitousClient(timeoutEngine)
        try {
            assertFailsWith<ProviderTimeout> { timeoutClient.stopTimes("central:one", 1, "en", NOW) }
        } finally {
            timeoutClient.close()
        }

        val cappedClient = transitousClient(RecordingEngine {
            jsonResponse("x".repeat(256 * 1024 + 1), contentLength = 256 * 1024 + 1)
        })
        try {
            assertFailsWith<ProviderBadGateway> { cappedClient.stopTimes("central:one", 1, "en", NOW) }
        } finally {
            cappedClient.close()
        }

        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cancellingClient = transitousClient(RecordingEngine {
            started.complete(Unit)
            never.await()
            jsonResponse(stopTimesJson("central:one"))
        })
        try {
            val request = async { cancellingClient.stopTimes("central:one", 1, "en", NOW) }
            started.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        } finally {
            cancellingClient.close()
        }
    }

    @Test
    fun `global budget admits only two concurrent upstream attempts`() = runTest {
        val started = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = transitousClient(RecordingEngine {
            if (started.incrementAndGet() == 2) twoStarted.complete(Unit)
            release.await()
            jsonResponse(stopTimesJson("central:one"))
        })
        try {
            val calls = List(3) { async { runCatching { client.stopTimes("central:one", 1, "en", NOW) } } }
            twoStarted.await()
            assertIs<ProviderRateLimited>(calls[2].await().exceptionOrNull())
            release.complete(Unit)
            assertTrue(calls[0].await().isSuccess)
            assertTrue(calls[1].await().isSuccess)
            assertEquals(2, started.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `rate budget rejection from recording engine emits bounded Transitous telemetry`() = runTest {
        val started = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observability = BffObservability(
            config = BffConfig.fromEnvironment(emptyMap()),
            allowedProviders = setOf("tbilisi" to TelemetryProvider.TRANSITOUS),
        )
        val client = TransitousClient(
            activation(),
            HttpClient(RecordingEngine {
                if (started.incrementAndGet() == 2) twoStarted.complete(Unit)
                release.await()
                jsonResponse(stopTimesJson("central:one"))
            }) { expectSuccess = false },
            observability,
        )
        try {
            val calls = List(3) { async { runCatching { client.stopTimes("central:one", 1, "en", NOW) } } }
            twoStarted.await()
            assertIs<ProviderRateLimited>(calls[2].await().exceptionOrNull())
            release.complete(Unit)
            assertTrue(calls[0].await().isSuccess)
            assertTrue(calls[1].await().isSuccess)
            assertEquals(2, started.get())
            assertTrue(observability.render().contains("event=\"rate_budget_rejected\""))
            assertFalse(observability.render().contains("central:one"))
        } finally {
            client.close()
        }
    }

    private suspend fun assertRetryAfter(header: String, expectedSeconds: Int) {
        val client = transitousClient(RecordingEngine {
            jsonResponse("provider-payload-secret", HttpStatusCode.TooManyRequests, retryAfter = header)
        })
        try {
            val failure = assertFailsWith<ProviderRateLimited> {
                client.stopTimes("central:one", 1, "en", NOW)
            }
            assertEquals(expectedSeconds, failure.retryAfterSeconds)
            assertFalse(failure.message?.contains("provider-payload-secret") == true)
        } finally {
            client.close()
        }
    }

    private suspend fun failureFrom(status: HttpStatusCode): ProviderFailure {
        val client = transitousClient(RecordingEngine { jsonResponse("provider-payload-secret", status) })
        return try {
            assertFailsWith<ProviderFailure> { client.stopTimes("central:one", 1, "en", NOW) }
        } finally {
            client.close()
        }
    }

    private fun adapter(
        engine: RecordingEngine,
        routingApproved: Boolean = false,
        approvedStopIds: Set<String> = emptySet(),
    ): TransitousTransitProviderAdapter {
        val config = activation(routingApproved, approvedStopIds)
        return TransitousTransitProviderAdapter(config, TransitousClient(config, HttpClient(engine) { expectSuccess = false }), CLOCK)
    }

    private suspend fun assertInvalidStopIdentityDoesNotUseExistingLkg(
        scenario: StopIdScenario,
        requestedRawStopId: String,
        requestedPublicStopId: String,
    ) {
        val attempts = AtomicInteger()
        val adapter = adapter(
            RecordingEngine {
                when (attempts.incrementAndGet()) {
                    1 -> jsonResponse(stopTimesJson(requestedRawStopId))
                    else -> jsonResponse(
                        stopTimesJson(
                            rawStopId = requestedRawStopId,
                            responseStopId = scenario.responseStopId,
                            stopTimeStopIds = scenario.stopTimeStopIds,
                        ),
                    )
                }
            },
            approvedStopIds = setOf(requestedRawStopId),
        )
        val service = transitService(adapter)
        try {
            val live = service.arrivals("tbilisi", requestedPublicStopId, limit = 2, locale = "en")
            assertFalse(live.stale, scenario.name)
            assertEquals(requestedPublicStopId, live.items.single().stopId, scenario.name)

            val failure = assertFailsWith<UpstreamBadGateway>(scenario.name) {
                service.arrivals("tbilisi", requestedPublicStopId, limit = 2, locale = "en")
            }
            assertUpstreamBadResponse(failure, scenario)
            assertEquals(2, attempts.get(), scenario.name)
        } finally {
            service.close()
            adapter.close()
        }
    }

    private suspend fun assertInvalidStopIdentityDoesNotWriteLkg(
        scenario: StopIdScenario,
        requestedRawStopId: String,
        requestedPublicStopId: String,
    ) {
        val attempts = AtomicInteger()
        val adapter = adapter(
            RecordingEngine {
                when (attempts.incrementAndGet()) {
                    1 -> jsonResponse(
                        stopTimesJson(
                            rawStopId = requestedRawStopId,
                            responseStopId = scenario.responseStopId,
                            stopTimeStopIds = scenario.stopTimeStopIds,
                        ),
                    )
                    else -> jsonResponse("provider-payload-secret", HttpStatusCode.TooManyRequests, retryAfter = "17")
                }
            },
            approvedStopIds = setOf(requestedRawStopId),
        )
        val service = transitService(adapter)
        try {
            val badResponse = assertFailsWith<UpstreamBadGateway>(scenario.name) {
                service.arrivals("tbilisi", requestedPublicStopId, limit = 2, locale = "en")
            }
            assertUpstreamBadResponse(badResponse, scenario)

            val rateLimited = assertFailsWith<RequestRateLimited>(scenario.name) {
                service.arrivals("tbilisi", requestedPublicStopId, limit = 2, locale = "en")
            }
            assertEquals(429, rateLimited.status.value, scenario.name)
            assertEquals("RATE_LIMITED", rateLimited.errorCode, scenario.name)
            assertEquals(2, attempts.get(), scenario.name)
        } finally {
            service.close()
            adapter.close()
        }
    }

    private fun assertUpstreamBadResponse(failure: UpstreamBadGateway, scenario: StopIdScenario) {
        assertEquals(502, failure.status.value, scenario.name)
        assertEquals("UPSTREAM_BAD_RESPONSE", failure.errorCode, scenario.name)
        assertEquals("The provider returned an invalid response", failure.message, scenario.name)
        assertFalse(failure.message.contains("central:two"), scenario.name)
    }

    private fun transitService(adapter: TransitousTransitProviderAdapter) = TransitService(
        registry = ProviderRegistry(listOf(adapter)),
        directoryCacheTtlSeconds = 3_600,
        shapeCacheTtlSeconds = 3_600,
        realtimeSingleFlightSeconds = 15,
    )

    private fun transitousClient(engine: RecordingEngine): TransitousClient {
        val config = activation()
        return TransitousClient(config, HttpClient(engine) { expectSuccess = false })
    }

    private fun activation(routingApproved: Boolean = false, approvedStopIds: Set<String> = emptySet()) =
        TransitousActivationConfig(
            enabled = true,
            baseUrl = URI("https://api.transitous.org"),
            contact = "transit-ops@example.com",
            appVersion = "test-1",
            eligibilityAcknowledged = true,
            eligibilityReference = "policy/DEN-56",
            contactAcknowledged = true,
            routingApprovalAcknowledged = routingApproved,
            routingApprovalReference = if (routingApproved) "approval/DEN-56" else null,
            approvedStopIds = approvedStopIds,
        )

    private fun journeyQuery(
        from: GeoPoint = GeoPoint(41.7, 44.8),
        to: GeoPoint = GeoPoint(41.8, 44.9),
        departureAt: Instant = NOW.plusSeconds(60),
        maxTransfers: Int = 1,
    ) = JourneyQuery(from, to, departureAt, locale = "KA", maxTransfers = maxTransfers)

    private fun publicId(entity: String, raw: String): String =
        "tbilisi:transitous:$entity:${Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())}"

    @OptIn(InternalAPI::class)
    private class RecordingEngine(
        private val handler: suspend (HttpRequestData) -> HttpResponseData,
    ) : HttpClientEngineBase("TransitousTestEngine") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData =
            withContext(dispatcher + callContext()) { handler(data) }
    }

    @OptIn(InternalAPI::class)
    private suspend fun jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        retryAfter: String? = null,
        contentLength: Int? = null,
    ): HttpResponseData = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(),
        headers = headersOf(
            *buildList {
                retryAfter?.let { add(HttpHeaders.RetryAfter to listOf(it)) }
                contentLength?.let { add(HttpHeaders.ContentLength to listOf(it.toString())) }
            }.toTypedArray(),
        ),
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(body.encodeToByteArray()),
        callContext = callContext(),
    )

    private fun stopTimesJson(
        rawStopId: String,
        realTime: Boolean = true,
        cancelled: Boolean = true,
        responseStopId: String = rawStopId,
        stopTimeStopIds: List<String> = listOf(rawStopId),
    ) =
        """{"stopTimes":[${stopTimeStopIds.joinToString { stopTimeStopId ->
            "{\"place\":{\"name\":\"Central\",\"lat\":41.7,\"lon\":44.8,\"stopId\":\"$stopTimeStopId\",\"departure\":\"2030-01-01T00:03:00Z\",\"scheduledDeparture\":\"2030-01-01T00:02:00Z\",\"cancelled\":$cancelled},\"realTime\":$realTime,\"headsign\":\"Airport\",\"tripId\":\"trip/1\",\"routeId\":\"route/7\",\"cancelled\":false,\"tripCancelled\":false}"
        }}],"place":{"name":"Central","lat":41.7,"lon":44.8,"stopId":"$responseStopId"}}"""

    private data class StopIdScenario(
        val name: String,
        val responseStopId: String,
        val stopTimeStopIds: List<String>,
    )

    private fun planJson(legs: String = "[$walkLeg,$odmLeg,$rideSharingLeg,$transitLeg]") =
        """{"itineraries":[{"startTime":"2030-01-01T00:00:00Z","endTime":"2030-01-01T00:10:00Z","transfers":0,"id":"journey/1","legs":$legs}]}"""

    private companion object {
        val NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        const val fromPlace = "{\"name\":\"Start\",\"lat\":41.7,\"lon\":44.8}"
        const val toPlace = "{\"name\":\"End\",\"lat\":41.8,\"lon\":44.9}"
        const val fromStopPlace = "{\"name\":\"Start\",\"lat\":41.7,\"lon\":44.8,\"stopId\":\"central:one\"}"
        const val toStopPlace = "{\"name\":\"End\",\"lat\":41.8,\"lon\":44.9,\"stopId\":\"central:two\"}"
        const val walkLeg = "{\"mode\":\"WALK\",\"from\":$fromPlace,\"to\":$toPlace,\"startTime\":\"2030-01-01T00:00:00Z\",\"endTime\":\"2030-01-01T00:02:00Z\",\"realTime\":false}"
        const val odmLeg = "{\"mode\":\"ODM\",\"from\":$fromPlace,\"to\":$toPlace,\"startTime\":\"2030-01-01T00:02:00Z\",\"endTime\":\"2030-01-01T00:04:00Z\",\"realTime\":false}"
        const val rideSharingLeg = "{\"mode\":\"RIDE_SHARING\",\"from\":$fromPlace,\"to\":$toPlace,\"startTime\":\"2030-01-01T00:04:00Z\",\"endTime\":\"2030-01-01T00:06:00Z\",\"realTime\":false}"
        const val transitLeg = "{\"mode\":\"BUS\",\"from\":$fromStopPlace,\"to\":$toStopPlace,\"startTime\":\"2030-01-01T00:06:00Z\",\"endTime\":\"2030-01-01T00:10:00Z\",\"realTime\":true,\"routeId\":\"route/7\",\"directionId\":\"outbound\"}"
    }
}
