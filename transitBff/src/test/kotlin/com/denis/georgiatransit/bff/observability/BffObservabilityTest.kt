package com.denis.georgiatransit.bff.observability

import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BffObservabilityTest {
    @Test
    fun `registry renders cumulative histogram buckets finite values and one sample per series`() {
        val registry = BoundedPrometheusRegistry()
        val labels = listOf("operation" to "health", "status_class" to "2xx")
        registry.increment("bff_http_requests_total", labels, amount = 2)
        registry.observeSeconds("bff_http_request_duration_seconds", labels, 0.01)
        registry.observeSeconds("bff_http_request_duration_seconds", labels, 0.2)
        registry.observeSeconds("bff_http_request_duration_seconds", labels, 8.0)

        val exposition = registry.render()
        assertTrue(exposition.endsWith("\n"))
        assertFalse(exposition.contains('\r'))
        assertTrue(exposition.contains("bff_http_requests_total{operation=\"health\",status_class=\"2xx\"} 2"))
        assertTrue(exposition.contains("bff_http_request_duration_seconds_bucket{operation=\"health\",status_class=\"2xx\",le=\"0.01\"} 1"))
        assertTrue(exposition.contains("bff_http_request_duration_seconds_bucket{operation=\"health\",status_class=\"2xx\",le=\"0.25\"} 2"))
        assertTrue(exposition.contains("bff_http_request_duration_seconds_bucket{operation=\"health\",status_class=\"2xx\",le=\"10\"} 3"))
        assertTrue(exposition.contains("bff_http_request_duration_seconds_bucket{operation=\"health\",status_class=\"2xx\",le=\"+Inf\"} 3"))
        assertTrue(exposition.contains("bff_http_request_duration_seconds_count{operation=\"health\",status_class=\"2xx\"} 3"))
        assertFalse(exposition.contains("NaN"))
        assertFalse(exposition.contains("Infinity"))
        val samples = exposition.lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        assertEquals(samples.size, samples.toSet().size)
    }

    @Test
    fun `HTTP telemetry classifies 304 and failures without admitting arbitrary provider series`() {
        val observability = observability()
        listOf(200, 304, 400, 500).forEach { status ->
            observability.recordHttp(HttpOperation.CITIES, status, durationNanos = 1)
        }
        observability.recordProviderResult(labels, ProviderOutcome.SUCCESS, durationNanos = 1)
        observability.recordProviderResult(
            labels.copy(city = "raw-route-stop-request-id-contact-reference-exception-payload"),
            ProviderOutcome.SUCCESS,
            durationNanos = 1,
        )

        val exposition = observability.render()
        listOf("2xx", "3xx", "4xx", "5xx").forEach { statusClass ->
            assertTrue(exposition.contains("bff_http_requests_total{operation=\"cities\",status_class=\"$statusClass\"} 1"))
        }
        assertTrue(exposition.contains("bff_provider_requests_total{city=\"test\",provider=\"fixture\",capability=\"arrivals\",operation=\"arrivals\",outcome=\"success\"} 1"))
        assertFalse(exposition.contains("raw-route-stop-request-id-contact-reference-exception-payload"))
    }

    @Test
    fun `operational empty data is measured without becoming a schema interlock event`() {
        val clock = MutableClock(Instant.parse("2030-01-01T00:05:01Z"))
        val observability = observability(clock)
        observability.recordDataResult(
            labels = labels,
            observedAt = Instant.parse("2030-01-01T00:00:00Z"),
            stale = false,
            itemCount = 0,
            realtime = false,
            probe = true,
        )

        val exposition = observability.render()
        assertTrue(exposition.contains("outcome=\"empty\""))
        assertTrue(exposition.contains("outcome=\"fresh\""))
        assertTrue(exposition.contains("bff_provider_data_age_seconds{city=\"test\",provider=\"fixture\",capability=\"arrivals\",operation=\"arrivals\"} 301"))
        assertFalse(exposition.contains("event=\"schema_interlock_latched\""))
    }

    @Test
    fun `circuit opens rejects without upstream work then half-open success closes it`() = runTest {
        val clock = MutableClock(Instant.EPOCH)
        val observability = observability(clock)
        val attempts = AtomicInteger()

        repeat(2) {
            assertFailsWith<ProviderUnavailable> {
                observability.protectProviderCall(labels) {
                    attempts.incrementAndGet()
                    throw ProviderUnavailable("upstream failure")
                }
            }
        }
        assertFailsWith<ProviderUnavailable> {
            observability.protectProviderCall(labels) { attempts.incrementAndGet() }
        }
        assertEquals(2, attempts.get(), "open circuit must prevent provider work")

        clock.advanceSeconds(5)
        assertEquals(7, observability.protectProviderCall(labels) { attempts.incrementAndGet(); 7 })
        assertEquals(3, attempts.get())
        assertTrue(observability.render().contains("bff_provider_circuit_open{city=\"test\",provider=\"fixture\",capability=\"arrivals\"} 0"))
        assertTrue(observability.render().contains("event=\"circuit_opened\""))
        assertTrue(observability.render().contains("event=\"circuit_closed\""))
    }

    @Test
    fun `cancelled half-open admission releases exactly one later recovery probe`() = runTest {
        val clock = MutableClock(Instant.EPOCH)
        val observability = observability(clock)
        repeat(2) {
            assertFailsWith<ProviderUnavailable> {
                observability.protectProviderCall(labels) { throw ProviderUnavailable("upstream failure") }
            }
        }
        clock.advanceSeconds(5)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val halfOpen = async {
            observability.protectProviderCall(labels) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        assertFailsWith<ProviderUnavailable> {
            observability.protectProviderCall(labels) { error("second half-open call reached provider") }
        }
        halfOpen.cancelAndJoin()

        assertEquals("recovered", observability.protectProviderCall(labels) { "recovered" })
        assertTrue(observability.render().contains("event=\"circuit_rejected\""))
    }

    private fun observability(clock: Clock = Clock.systemUTC()): BffObservability = BffObservability(
        config = BffConfig.fromEnvironment(
            mapOf(
                "BFF_FIXTURES_ENABLED" to "true",
                "BFF_CIRCUIT_FAILURE_THRESHOLD" to "2",
                "BFF_CIRCUIT_WINDOW_SECONDS" to "10",
                "BFF_CIRCUIT_OPEN_SECONDS" to "5",
            ),
        ),
        allowedProviders = setOf("test" to TelemetryProvider.FIXTURE),
        clock = clock,
    )

    private companion object {
        val labels = ProviderTelemetryLabels(
            city = "test",
            provider = TelemetryProvider.FIXTURE,
            capability = TelemetryCapability.ARRIVALS,
            operation = TelemetryOperation.ARRIVALS,
        )
    }
}

private class MutableClock(private var value: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = value

    fun advanceSeconds(seconds: Long) {
        value = value.plusSeconds(seconds)
    }
}
