package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderConflict
import com.denis.georgiatransit.bff.provider.ProviderFailure
import com.denis.georgiatransit.bff.provider.ProviderInvalidArgument
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.slf4j.event.SubstituteLoggingEvent
import org.slf4j.helpers.SubstituteLogger

class ProviderFailureContractTest {
    @Test
    fun `every provider failure is redacted with bounded retry advice over HTTP`() {
        val cases = providerCases()
        var selectedFailure: ProviderFailure? = null
        val adapter = FakeAdapter().apply {
            routesResult = { throw requireNotNull(selectedFailure) }
        }
        val service = TransitService(ProviderRegistry(listOf(adapter)), 3_600, 3_600, 15)
        val events = LinkedBlockingQueue<SubstituteLoggingEvent>()
        val logger = SubstituteLogger("provider-redaction-test", events, false)
        try {
            testApplication {
                environment { log = logger }
                application {
                    transitBffModule(BffConfig.fromEnvironment(mapOf("BFF_FIXTURES_ENABLED" to "true")))
                    routing {
                        get("/_test/provider/{case}") {
                            call.respond(service.routes("test", requireNotNull(call.parameters["case"]), null))
                        }
                    }
                }

                cases.forEach { case ->
                    selectedFailure = case.failure
                    val requestId = "provider-${case.name}"
                    val response = client.get("/_test/provider/${case.name}") {
                        header(HttpHeaders.XRequestId, requestId)
                    }
                    assertEquals(case.status, response.status, case.name)
                    assertEquals(case.retryAfter?.toString(), response.headers[HttpHeaders.RetryAfter], case.name)
                    val error = providerJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                    val expectedKeys = if (case.retryAfter == null) {
                        setOf("code", "message", "requestId")
                    } else {
                        setOf("code", "message", "retryAfterSeconds", "requestId")
                    }
                    assertEquals(expectedKeys, error.keys, case.name)
                    assertEquals(case.code, error.getValue("code").jsonPrimitive.content, case.name)
                    assertEquals(case.safeMessage, error.getValue("message").jsonPrimitive.content, case.name)
                    assertEquals(requestId, error.getValue("requestId").jsonPrimitive.content, case.name)
                    case.retryAfter?.let {
                        assertEquals(it, error.getValue("retryAfterSeconds").jsonPrimitive.content.toInt(), case.name)
                    }
                    assertFalse(response.bodyAsText().contains("provider-secret"), case.name)
                }
            }
        } finally {
            service.close()
        }

        val capturedLogs = events.joinToString("\n") { event ->
            listOfNotNull(event.message, event.arguments?.joinToString(), event.throwable?.stackTraceToString()).joinToString()
        }
        assertFalse(capturedLogs.contains("provider-secret"))
    }
}

private val providerJson = Json { ignoreUnknownKeys = false }

private data class ProviderCase(
    val name: String,
    val failure: ProviderFailure,
    val status: HttpStatusCode,
    val code: String,
    val safeMessage: String,
    val retryAfter: Int? = null,
)

private fun providerCases(): List<ProviderCase> = listOf(
    ProviderCase(
        "invalid",
        ProviderInvalidArgument("provider-secret-invalid"),
        HttpStatusCode.BadRequest,
        "INVALID_ARGUMENT",
        "The transit provider rejected the request",
    ),
    ProviderCase(
        "route-missing",
        ProviderRouteNotFound("provider-secret-route"),
        HttpStatusCode.NotFound,
        "ROUTE_NOT_FOUND",
        "The requested route was not found",
    ),
    ProviderCase(
        "stop-missing",
        ProviderStopNotFound("provider-secret-stop"),
        HttpStatusCode.NotFound,
        "STOP_NOT_FOUND",
        "The requested stop was not found",
    ),
    ProviderCase(
        "conflict",
        ProviderConflict("provider-secret-conflict"),
        HttpStatusCode.Conflict,
        "PROVIDER_ID_CHANGED",
        "The provider ID changed",
    ),
    ProviderCase(
        "rate-min",
        ProviderRateLimited("provider-secret-rate-min", 1),
        HttpStatusCode.TooManyRequests,
        "RATE_LIMITED",
        "The transit provider is rate limited",
        1,
    ),
    ProviderCase(
        "rate-max",
        ProviderRateLimited("provider-secret-rate-max", 86_400),
        HttpStatusCode.TooManyRequests,
        "RATE_LIMITED",
        "The transit provider is rate limited",
        86_400,
    ),
    ProviderCase(
        "rate-zero",
        ProviderRateLimited("provider-secret-rate-zero", 0),
        HttpStatusCode.TooManyRequests,
        "RATE_LIMITED",
        "The transit provider is rate limited",
    ),
    ProviderCase(
        "rate-negative",
        ProviderRateLimited("provider-secret-rate-negative", -1),
        HttpStatusCode.TooManyRequests,
        "RATE_LIMITED",
        "The transit provider is rate limited",
    ),
    ProviderCase(
        "rate-over",
        ProviderRateLimited("provider-secret-rate-over", 86_401),
        HttpStatusCode.TooManyRequests,
        "RATE_LIMITED",
        "The transit provider is rate limited",
    ),
    ProviderCase(
        "capability",
        ProviderCapabilityUnavailable("provider-secret-capability"),
        HttpStatusCode.NotImplemented,
        "CAPABILITY_NOT_AVAILABLE",
        "The requested capability is not available",
    ),
    ProviderCase(
        "bad-gateway",
        ProviderBadGateway("provider-secret-bad-gateway"),
        HttpStatusCode.BadGateway,
        "UPSTREAM_BAD_RESPONSE",
        "The provider returned an invalid response",
    ),
    ProviderCase(
        "unavailable-none",
        ProviderUnavailable("provider-secret-unavailable-none"),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
    ),
    ProviderCase(
        "unavailable-min",
        ProviderUnavailable("provider-secret-unavailable-min", 1),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
        1,
    ),
    ProviderCase(
        "unavailable-max",
        ProviderUnavailable("provider-secret-unavailable-max", 86_400),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
        86_400,
    ),
    ProviderCase(
        "unavailable-zero",
        ProviderUnavailable("provider-secret-unavailable-zero", 0),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
    ),
    ProviderCase(
        "unavailable-negative",
        ProviderUnavailable("provider-secret-unavailable-negative", -1),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
    ),
    ProviderCase(
        "unavailable-over",
        ProviderUnavailable("provider-secret-unavailable-over", 86_401),
        HttpStatusCode.ServiceUnavailable,
        "UPSTREAM_UNAVAILABLE",
        "The transit provider is unavailable",
    ),
    ProviderCase(
        "timeout",
        ProviderTimeout("provider-secret-timeout"),
        HttpStatusCode.GatewayTimeout,
        "UPSTREAM_TIMEOUT",
        "The transit provider did not respond in time",
    ),
)
