package com.denis.georgiatransit.bff.observability

import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.SyntheticProbeTarget
import com.denis.georgiatransit.bff.service.TransitService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle-bound internal synthetic probes. Targets are adapter-owned opaque values and this
 * runner records only fixed city/provider/capability labels. It has no HTTP route.
 */
internal class ProbeRunner(
    private val config: BffConfig,
    private val service: TransitService,
    private val targets: List<SyntheticProbeTarget>,
    private val observability: BffObservability,
    private val audit: (String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        require(config.probeIntervalSeconds in 60L..300L) { "Invalid probe interval" }
        if (config.probesEnabled) require(targets.isNotEmpty()) { "BFF_PROBES_ENABLED requires an adapter probe target" }
    }

    fun start() {
        if (!config.probesEnabled) return
        scope.launch {
            while (isActive) {
                for (target in targets) runTarget(target)
                delay(config.probeIntervalSeconds * 1_000)
            }
        }
    }

    override fun close() = scope.cancel()

    private suspend fun runTarget(target: SyntheticProbeTarget) {
        val labels = ProviderTelemetryLabels(target.cityId, target.provider, target.capability, target.operation)
        try {
            observability.recordProbeRealtimeExpected(labels, target.expectedRealtime)
            if (!service.isSyntheticProbeEnabled(target)) {
                observability.recordProbeTargetEnabled(labels, enabled = false)
                return
            }
            observability.recordProbeTargetEnabled(labels, enabled = true)
            service.runSyntheticProbe(target)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            observability.recordProbeFailure(labels)
            audit(
                "synthetic_probe event=failure city=${target.cityId} capability=${target.capability.wireValue} " +
                    "classification=normalized_failure",
            )
        }
    }
}
