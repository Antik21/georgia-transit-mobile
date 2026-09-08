package com.denis.georgiatransit.bff.observability

import com.denis.georgiatransit.bff.FakeAdapter
import com.denis.georgiatransit.bff.capabilities
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.SyntheticProbeProvider
import com.denis.georgiatransit.bff.provider.SyntheticProbeResult
import com.denis.georgiatransit.bff.provider.SyntheticProbeTarget
import com.denis.georgiatransit.bff.service.TransitService
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProbeRunnerTest {
    @Test
    fun `disabled default runner produces no provider traffic`() = runTest {
        val adapter = ProbeAdapter()
        val observability = observability()
        val service = service(adapter, observability)
        val runner = ProbeRunner(BffConfig.fromEnvironment(emptyMap()), service, adapter.probeTargets, observability, {})
        try {
            runner.start()
            assertEquals(0, adapter.probeCalls.get())
        } finally {
            runner.close()
            service.close()
        }
    }

    @Test
    fun `enabled runner requires an explicit adapter target`() {
        val adapter = ProbeAdapter()
        val service = service(adapter, observability())
        try {
            assertFailsWith<IllegalArgumentException> {
                ProbeRunner(enabledConfig(), service, emptyList(), observability(), {})
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `probe records enabled schedule-only fresh success and close stops its lifecycle`() = runTest {
        val adapter = ProbeAdapter()
        val observability = observability()
        val service = service(adapter, observability)
        val runner = ProbeRunner(enabledConfig(), service, adapter.probeTargets, observability, {})
        try {
            runner.start()
            awaitProbe(adapter)
            awaitMetric(observability, "bff_probe_last_success_timestamp_seconds{city=\"test\"")
            runner.close()
            delay(25)

            assertEquals(1, adapter.probeCalls.get())
            val exposition = observability.render()
            assertTrue(exposition.contains("bff_probe_target_enabled{city=\"test\",provider=\"fixture\",capability=\"arrivals\",operation=\"probe_arrivals\"} 1"))
            assertTrue(exposition.contains("bff_probe_realtime_expected{city=\"test\",provider=\"fixture\",capability=\"arrivals\",operation=\"probe_arrivals\"} 0"))
            assertTrue(exposition.contains("bff_probe_last_success_timestamp_seconds"))
            assertFalse(exposition.contains("bff_probe_last_fresh_realtime_timestamp_seconds{city=\"test\""))
        } finally {
            runner.close()
            service.close()
        }
    }

    @Test
    fun `stale probe result is not counted as a fresh success`() = runTest {
        val adapter = ProbeAdapter(stale = true, expectedRealtime = true)
        val observability = observability()
        val service = service(adapter, observability)
        val runner = ProbeRunner(enabledConfig(), service, adapter.probeTargets, observability, {})
        try {
            runner.start()
            awaitProbe(adapter)
            awaitMetric(observability, "event=\"stale_response\"")
            runner.close()

            val exposition = observability.render()
            assertTrue(exposition.contains("event=\"stale_response\""))
            assertFalse(exposition.contains("bff_probe_last_success_timestamp_seconds{city=\"test\""))
            assertFalse(exposition.contains("bff_probe_last_fresh_realtime_timestamp_seconds{city=\"test\""))
        } finally {
            runner.close()
            service.close()
        }
    }

    private fun enabledConfig(): BffConfig = BffConfig.fromEnvironment(
        mapOf("BFF_PROBES_ENABLED" to "true", "BFF_PROBE_INTERVAL_SECONDS" to "60"),
    )

    private fun observability(): BffObservability = BffObservability(
        config = BffConfig.fromEnvironment(emptyMap()),
        allowedProviders = setOf("test" to TelemetryProvider.FIXTURE),
    )

    private fun service(adapter: ProbeAdapter, observability: BffObservability): TransitService = TransitService(
        capabilitySnapshots = IntrinsicCapabilitySnapshotSource(ProviderRegistry(listOf(adapter))),
        directoryCacheTtlSeconds = 3_600,
        shapeCacheTtlSeconds = 3_600,
        realtimeSingleFlightSeconds = 15,
        observability = observability,
    )

    private suspend fun awaitProbe(adapter: ProbeAdapter) = withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { adapter.probed.await() }
    }

    private suspend fun awaitMetric(observability: BffObservability, expected: String) = withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (!observability.render().contains(expected)) delay(5)
        }
    }
}

private class ProbeAdapter(
    private val stale: Boolean = false,
    expectedRealtime: Boolean = false,
) : FakeAdapter(city = FakeAdapter().city.copy(capabilities = capabilities(arrivals = true))), SyntheticProbeProvider {
    val probeCalls = AtomicInteger()
    val probed = CompletableDeferred<Unit>()

    override val telemetryProvider: TelemetryProvider = TelemetryProvider.FIXTURE

    override val probeTargets = listOf(
        SyntheticProbeTarget(
            cityId = "test",
            provider = telemetryProvider,
            capability = TelemetryCapability.ARRIVALS,
            operation = TelemetryOperation.PROBE_ARRIVALS,
            expectedRealtime = expectedRealtime,
            privateTarget = "server-only-probe-stop",
        ),
    )

    override suspend fun probe(target: SyntheticProbeTarget): SyntheticProbeResult {
        require(target in probeTargets)
        probeCalls.incrementAndGet()
        probed.complete(Unit)
        return SyntheticProbeResult(
            observedAt = Instant.now(),
            itemCount = 0,
            realtime = false,
            stale = stale,
        )
    }
}
