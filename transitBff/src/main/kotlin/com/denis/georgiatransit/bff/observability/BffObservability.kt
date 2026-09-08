package com.denis.georgiatransit.bff.observability

import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Fixed values used in all telemetry labels. Provider adapters select one of these values; neither
 * request paths, public IDs, query values, coordinates, headers, nor exception text can become a
 * label value.
 */
enum class TelemetryProvider(val wireValue: String) {
    FIXTURE("fixture"),
    TRANSITOUS("transitous"),
}

enum class TelemetryCapability(val wireValue: String) {
    ROUTES("routes"),
    STOPS("stops"),
    ROUTE_GEOMETRY("route_geometry"),
    VEHICLE_POSITIONS("vehicle_positions"),
    ARRIVALS("arrivals"),
    TRIP_PLANNING("trip_planning"),
}

enum class TelemetryOperation(val wireValue: String, val capability: TelemetryCapability) {
    LIST_ROUTES("list_routes", TelemetryCapability.ROUTES),
    ROUTE("route", TelemetryCapability.ROUTES),
    DIRECTION_STOPS("direction_stops", TelemetryCapability.STOPS),
    SHAPE("shape", TelemetryCapability.ROUTE_GEOMETRY),
    NEARBY_STOPS("nearby_stops", TelemetryCapability.STOPS),
    VEHICLES("vehicles", TelemetryCapability.VEHICLE_POSITIONS),
    ARRIVALS("arrivals", TelemetryCapability.ARRIVALS),
    JOURNEYS("journeys", TelemetryCapability.TRIP_PLANNING),
    WALKING_ESTIMATE("walking_estimate", TelemetryCapability.TRIP_PLANNING),
    PROBE_ARRIVALS("probe_arrivals", TelemetryCapability.ARRIVALS),
    PROBE_JOURNEYS("probe_journeys", TelemetryCapability.TRIP_PLANNING),
}

enum class HttpOperation(val wireValue: String) {
    HEALTH("health"),
    CITIES("cities"),
    LIST_ROUTES("list_routes"),
    ROUTE("route"),
    DIRECTION_STOPS("direction_stops"),
    SHAPE("shape"),
    VEHICLES("vehicles"),
    NEARBY_STOPS("nearby_stops"),
    ARRIVALS("arrivals"),
    JOURNEYS("journeys"),
    WALKING_ESTIMATE("walking_estimate"),
}

enum class CacheOutcome(val wireValue: String) {
    HIT("hit"),
    MISS_OWNER("miss_owner"),
    COALESCED("coalesced"),
}

enum class ProviderOutcome(val wireValue: String) {
    SUCCESS("success"),
    UNAVAILABLE("unavailable"),
    TIMEOUT("timeout"),
    BAD_GATEWAY("bad_gateway"),
    RATE_LIMITED("rate_limited"),
    INVALID_ARGUMENT("invalid_argument"),
    CAPABILITY_UNAVAILABLE("capability_unavailable"),
    EMPTY("empty"),
    NONEMPTY("nonempty"),
    FRESH("fresh"),
    STALE("stale"),
    JSON_DECODE("json_decode"),
    NORMALIZED_SCHEMA("normalized_schema"),
}

enum class ProviderEvent(val wireValue: String) {
    RETRY("retry"),
    RATE_BUDGET_REJECTED("rate_budget_rejected"),
    CIRCUIT_REJECTED("circuit_rejected"),
    CIRCUIT_OPENED("circuit_opened"),
    CIRCUIT_CLOSED("circuit_closed"),
    SCHEMA_INTERLOCK_LATCHED("schema_interlock_latched"),
    STALE_RESPONSE("stale_response"),
}

data class ProviderTelemetryLabels(
    val city: String,
    val provider: TelemetryProvider,
    val capability: TelemetryCapability,
    val operation: TelemetryOperation,
) {
    init {
        require(operation.capability == capability) { "Telemetry operation must match capability" }
    }
}

/** Immutable, finite capability state emitted by capability control after an atomic transition. */
data class CapabilityTelemetryState(
    val city: String,
    val provider: TelemetryProvider,
    val capability: TelemetryCapability,
    val enabled: Boolean,
    val schemaInterlocked: Boolean,
)

/**
 * A generation-tagged state transfer, not a public DTO. The receiver verifies every city/provider
 * tuple against its startup allow-list before creating a metric series.
 */
data class CapabilityTelemetrySnapshot(
    val generation: Long,
    val states: List<CapabilityTelemetryState>,
)

private data class CapabilityTelemetryKey(
    val city: String,
    val provider: TelemetryProvider,
    val capability: TelemetryCapability,
)

interface ProviderCallObservability {
    suspend fun <T> protectProviderCall(labels: ProviderTelemetryLabels, block: suspend () -> T): T

    fun recordEvent(labels: ProviderTelemetryLabels, event: ProviderEvent)
}

object NoopProviderCallObservability : ProviderCallObservability {
    override suspend fun <T> protectProviderCall(labels: ProviderTelemetryLabels, block: suspend () -> T): T = block()

    override fun recordEvent(labels: ProviderTelemetryLabels, event: ProviderEvent) = Unit
}

/** A bounded, synchronized Prometheus text registry with only code-defined label dimensions. */
class BoundedPrometheusRegistry {
    private val lock = Any()
    private val counters = mutableMapOf<SeriesKey, Long>()
    private val gauges = mutableMapOf<SeriesKey, Double>()
    private val histograms = mutableMapOf<SeriesKey, Histogram>()

    fun increment(name: String, labels: List<Pair<String, String>>, amount: Long = 1) {
        require(amount >= 0)
        synchronized(lock) {
            val key = SeriesKey(name, labels)
            counters[key] = (counters[key] ?: 0L) + amount
        }
    }

    fun setGauge(name: String, labels: List<Pair<String, String>>, value: Double) {
        require(value.isFinite())
        synchronized(lock) { gauges[SeriesKey(name, labels)] = value }
    }

    fun setGauges(values: List<GaugeValue>) = synchronized(lock) {
        values.forEach { value ->
            require(value.value.isFinite())
            gauges[SeriesKey(value.name, value.labels)] = value.value
        }
    }

    fun observeSeconds(name: String, labels: List<Pair<String, String>>, seconds: Double) {
        require(seconds.isFinite() && seconds >= 0.0)
        synchronized(lock) { histograms.getOrPut(SeriesKey(name, labels), ::Histogram).observe(seconds) }
    }

    fun render(): String = synchronized(lock) {
        buildString {
            append("# HELP bff_http_requests_total Completed BFF HTTP requests by stable operation and status class.\n")
            append("# TYPE bff_http_requests_total counter\n")
            append("# HELP bff_http_request_duration_seconds BFF HTTP request duration in seconds.\n")
            append("# TYPE bff_http_request_duration_seconds histogram\n")
            append("# HELP bff_provider_requests_total Provider outcomes using finite internal dimensions.\n")
            append("# TYPE bff_provider_requests_total counter\n")
            append("# HELP bff_provider_request_duration_seconds Provider call duration in seconds.\n")
            append("# TYPE bff_provider_request_duration_seconds histogram\n")
            append("# HELP bff_provider_cache_lookups_total Cache lookup outcome by bounded provider operation.\n")
            append("# TYPE bff_provider_cache_lookups_total counter\n")
            append("# HELP bff_provider_events_total Provider protection and safety events.\n")
            append("# TYPE bff_provider_events_total counter\n")
            append("# HELP bff_provider_data_age_seconds Age of the original provider observation.\n")
            append("# TYPE bff_provider_data_age_seconds gauge\n")
            append("# HELP bff_provider_circuit_open Whether the bounded provider circuit is open.\n")
            append("# TYPE bff_provider_circuit_open gauge\n")
            append("# HELP bff_provider_capability_enabled Whether the effective provider capability is enabled.\n")
            append("# TYPE bff_provider_capability_enabled gauge\n")
            append("# HELP bff_provider_schema_interlock_latched Whether schema safety interlock disabled the capability.\n")
            append("# TYPE bff_provider_schema_interlock_latched gauge\n")
            append("# HELP bff_probe_target_enabled Whether the configured probe target is currently callable.\n")
            append("# TYPE bff_probe_target_enabled gauge\n")
            append("# HELP bff_probe_target_enabled_since_timestamp_seconds Timestamp when the target became callable.\n")
            append("# TYPE bff_probe_target_enabled_since_timestamp_seconds gauge\n")
            append("# HELP bff_probe_realtime_expected Whether a fresh realtime probe result is expected.\n")
            append("# TYPE bff_probe_realtime_expected gauge\n")
            append("# HELP bff_probe_last_success_timestamp_seconds Last fresh successful synthetic probe timestamp.\n")
            append("# TYPE bff_probe_last_success_timestamp_seconds gauge\n")
            append("# HELP bff_probe_last_failure_timestamp_seconds Last failed synthetic probe timestamp.\n")
            append("# TYPE bff_probe_last_failure_timestamp_seconds gauge\n")
            append("# HELP bff_probe_last_fresh_realtime_timestamp_seconds Last fresh realtime synthetic probe timestamp.\n")
            append("# TYPE bff_probe_last_fresh_realtime_timestamp_seconds gauge\n")
            counters.toSortedMap().forEach { (key, value) -> appendPrometheusSeries(this, key, value.toString()) }
            gauges.toSortedMap().forEach { (key, value) -> appendPrometheusSeries(this, key, value.prometheusNumber()) }
            histograms.toSortedMap().forEach { (key, histogram) ->
                histogram.render(key, this)
            }
        }
    }

    internal data class SeriesKey(
        val name: String,
        val labels: List<Pair<String, String>>,
    ) : Comparable<SeriesKey> {

        override fun compareTo(other: SeriesKey): Int =
            compareValuesBy(this, other, SeriesKey::name, { it.labels.joinToString("\\u0000") })
    }

    data class GaugeValue(
        val name: String,
        val labels: List<Pair<String, String>>,
        val value: Double,
    )

    private class Histogram {
        private var count = 0L
        private var sum = 0.0
        private val buckets = HistogramBuckets.associateWith { 0L }.toMutableMap()

        fun observe(value: Double) {
            count += 1
            sum += value
            HistogramBuckets.filter { value <= it }.forEach { bucket -> buckets[bucket] = buckets.getValue(bucket) + 1 }
        }

        fun render(key: SeriesKey, output: StringBuilder) {
            HistogramBuckets.forEach { bucket ->
                appendPrometheusSeries(
                    output,
                    SeriesKey("${key.name}_bucket", key.labels + ("le" to bucket.prometheusNumber())),
                    buckets.getValue(bucket).toString(),
                )
            }
            appendPrometheusSeries(output, SeriesKey("${key.name}_bucket", key.labels + ("le" to "+Inf")), count.toString())
            appendPrometheusSeries(output, SeriesKey("${key.name}_sum", key.labels), sum.prometheusNumber())
            appendPrometheusSeries(output, SeriesKey("${key.name}_count", key.labels), count.toString())
        }
    }

    private companion object {
        val HistogramBuckets = listOf(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    }
}

private fun appendPrometheusSeries(output: StringBuilder, key: BoundedPrometheusRegistry.SeriesKey, value: String) {
    output.append(key.name)
    if (key.labels.isNotEmpty()) {
        output.append('{')
        key.labels.joinTo(output, separator = ",") { (name, labelValue) ->
            "$name=\"${labelValue.prometheusLabelValue()}\""
        }
        output.append('}')
    }
    output.append(' ').append(value).append('\n')
}

private fun String.prometheusLabelValue(): String =
    replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")

private fun Double.prometheusNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else String.format(Locale.ROOT, "%.9f", this).trimEnd('0')

/**
 * The sole observability facade used by the BFF. Its accepted provider dimensions are fixed when
 * the runtime is assembled, preventing a caller from creating a series with a request value.
 */
class BffObservability(
    private val config: BffConfig,
    allowedProviders: Set<Pair<String, TelemetryProvider>>,
    private val clock: Clock = Clock.systemUTC(),
) : ProviderCallObservability {
    private val allowedProviders = allowedProviders.toSet()
    private val registry = BoundedPrometheusRegistry()
    private val probeEnabledSince = mutableMapOf<ProviderTelemetryLabels, Long>()
    private val capabilityTelemetryLock = Any()
    private var latestCapabilityTelemetryGeneration = -1L
    private val breakers = ProviderCircuitBreakers(
        config = config,
        allowedProviders = this.allowedProviders,
        onTransition = ::recordCircuitTransition,
        onRejected = { labels -> recordEvent(labels, ProviderEvent.CIRCUIT_REJECTED) },
        clock = clock,
    )

    val isEnabled: Boolean get() = config.metricsEnabled

    fun render(): String = registry.render()

    fun recordHttp(operation: HttpOperation, statusCode: Int, durationNanos: Long) {
        if (!isEnabled) return
        val labels = listOf("operation" to operation.wireValue, "status_class" to statusClass(statusCode))
        registry.increment("bff_http_requests_total", labels)
        registry.observeSeconds("bff_http_request_duration_seconds", labels, durationNanos.coerceAtLeast(0).toDouble() / NanosPerSecond)
    }

    fun providerCall(labels: ProviderTelemetryLabels, block: () -> Unit) {
        if (isEnabled) block()
    }

    override suspend fun <T> protectProviderCall(labels: ProviderTelemetryLabels, block: suspend () -> T): T =
        breakers.execute(labels, block)

    fun recordProviderResult(labels: ProviderTelemetryLabels, outcome: ProviderOutcome, durationNanos: Long) {
        if (!isEnabled || !isAllowed(labels)) return
        val metricLabels = labels.metricLabels() + ("outcome" to outcome.wireValue)
        registry.increment("bff_provider_requests_total", metricLabels)
        registry.observeSeconds(
            "bff_provider_request_duration_seconds",
            metricLabels,
            durationNanos.coerceAtLeast(0).toDouble() / NanosPerSecond,
        )
    }

    fun recordCache(labels: ProviderTelemetryLabels, outcome: CacheOutcome) {
        if (!isEnabled || !isAllowed(labels)) return
        registry.increment("bff_provider_cache_lookups_total", labels.metricLabels() + ("outcome" to outcome.wireValue))
    }

    override fun recordEvent(labels: ProviderTelemetryLabels, event: ProviderEvent) {
        if (!isEnabled || !isAllowed(labels)) return
        registry.increment("bff_provider_events_total", labels.metricLabels() + ("event" to event.wireValue))
    }

    fun recordDataResult(
        labels: ProviderTelemetryLabels,
        observedAt: Instant,
        stale: Boolean,
        itemCount: Int,
        realtime: Boolean,
        probe: Boolean,
    ) {
        if (!isEnabled || !isAllowed(labels)) return
        val dimensions = labels.metricLabels()
        val age = Duration.between(observedAt, clock.instant()).seconds.coerceAtLeast(0).toDouble()
        registry.setGauge("bff_provider_data_age_seconds", dimensions, age)
        registry.increment(
            "bff_provider_requests_total",
            dimensions + ("outcome" to if (stale) ProviderOutcome.STALE.wireValue else ProviderOutcome.FRESH.wireValue),
        )
        registry.increment(
            "bff_provider_requests_total",
            dimensions + ("outcome" to if (itemCount == 0) ProviderOutcome.EMPTY.wireValue else ProviderOutcome.NONEMPTY.wireValue),
        )
        if (stale) registry.increment(
            "bff_provider_events_total",
            dimensions + ("event" to ProviderEvent.STALE_RESPONSE.wireValue),
        )
        if (probe) {
            val nowSeconds = clock.instant().epochSecond.toDouble()
            if (!stale) registry.setGauge("bff_probe_last_success_timestamp_seconds", dimensions, nowSeconds)
            if (!stale && realtime) registry.setGauge("bff_probe_last_fresh_realtime_timestamp_seconds", dimensions, nowSeconds)
        }
    }

    fun recordProbeFailure(labels: ProviderTelemetryLabels) {
        if (!isEnabled || !isAllowed(labels)) return
        registry.setGauge("bff_probe_last_failure_timestamp_seconds", labels.metricLabels(), clock.instant().epochSecond.toDouble())
    }

    fun recordSchemaInterlock(labels: ProviderTelemetryLabels) = recordEvent(labels, ProviderEvent.SCHEMA_INTERLOCK_LATCHED)

    fun recordCapability(city: String, provider: TelemetryProvider, capability: TelemetryCapability, enabled: Boolean) {
        if (!isEnabled || (city to provider) !in allowedProviders) return
        registry.setGauge(
            "bff_provider_capability_enabled",
            providerCapabilityLabels(city, provider, capability),
            if (enabled) 1.0 else 0.0,
        )
    }

    fun recordSchemaInterlockLatch(
        city: String,
        provider: TelemetryProvider,
        capability: TelemetryCapability,
        latched: Boolean,
    ) {
        if (!isEnabled || (city to provider) !in allowedProviders) return
        registry.setGauge(
            "bff_provider_schema_interlock_latched",
            providerCapabilityLabels(city, provider, capability),
            if (latched) 1.0 else 0.0,
        )
    }

    /** Applies one coherent, generation-ordered capability-control transition without provider work. */
    fun recordCapabilitySnapshot(snapshot: CapabilityTelemetrySnapshot) {
        if (!isEnabled) return
        synchronized(capabilityTelemetryLock) {
            if (snapshot.generation <= latestCapabilityTelemetryGeneration) return
            val provided = snapshot.states.associateBy { state ->
                CapabilityTelemetryKey(state.city, state.provider, state.capability)
            }
            val gauges = allowedProviders.flatMap { (city, provider) ->
                TelemetryCapability.entries.flatMap { capability ->
                    val state = provided[CapabilityTelemetryKey(city, provider, capability)]
                    val labels = providerCapabilityLabels(city, provider, capability)
                    listOf(
                        BoundedPrometheusRegistry.GaugeValue(
                            name = "bff_provider_capability_enabled",
                            labels = labels,
                            value = if (state?.enabled == true) 1.0 else 0.0,
                        ),
                        BoundedPrometheusRegistry.GaugeValue(
                            name = "bff_provider_schema_interlock_latched",
                            labels = labels,
                            value = if (state?.schemaInterlocked == true) 1.0 else 0.0,
                        ),
                    )
                }
            }
            registry.setGauges(gauges)
            latestCapabilityTelemetryGeneration = snapshot.generation
        }
    }

    fun recordProbeTargetEnabled(labels: ProviderTelemetryLabels, enabled: Boolean) {
        if (!isEnabled || !isAllowed(labels)) return
        registry.setGauge("bff_probe_target_enabled", labels.metricLabels(), if (enabled) 1.0 else 0.0)
        val enabledSince = synchronized(probeEnabledSince) {
            if (enabled) probeEnabledSince.getOrPut(labels) { clock.instant().epochSecond } else {
                probeEnabledSince.remove(labels)
                0L
            }
        }
        registry.setGauge(
            "bff_probe_target_enabled_since_timestamp_seconds",
            labels.metricLabels(),
            enabledSince.toDouble(),
        )
    }

    fun recordProbeRealtimeExpected(labels: ProviderTelemetryLabels, expected: Boolean) {
        if (!isEnabled || !isAllowed(labels)) return
        registry.setGauge("bff_probe_realtime_expected", labels.metricLabels(), if (expected) 1.0 else 0.0)
    }

    private fun recordCircuitTransition(labels: ProviderTelemetryLabels, open: Boolean) {
        if (!isEnabled || !isAllowed(labels)) return
        val dimensions = labels.metricLabels().minusOperation()
        registry.setGauge("bff_provider_circuit_open", dimensions, if (open) 1.0 else 0.0)
        recordEvent(labels, if (open) ProviderEvent.CIRCUIT_OPENED else ProviderEvent.CIRCUIT_CLOSED)
    }

    private fun isAllowed(labels: ProviderTelemetryLabels): Boolean = (labels.city to labels.provider) in allowedProviders

    private fun ProviderTelemetryLabels.metricLabels(): List<Pair<String, String>> = listOf(
        "city" to city,
        "provider" to provider.wireValue,
        "capability" to capability.wireValue,
        "operation" to operation.wireValue,
    )

    private fun providerCapabilityLabels(
        city: String,
        provider: TelemetryProvider,
        capability: TelemetryCapability,
    ): List<Pair<String, String>> = listOf(
        "city" to city,
        "provider" to provider.wireValue,
        "capability" to capability.wireValue,
    )

    private fun List<Pair<String, String>>.minusOperation(): List<Pair<String, String>> = filterNot { it.first == "operation" }

    private fun statusClass(statusCode: Int): String = when (statusCode) {
        in 100..199 -> "1xx"
        in 200..299 -> "2xx"
        in 300..399 -> "3xx"
        in 400..499 -> "4xx"
        in 500..599 -> "5xx"
        else -> "other"
    }

    private companion object {
        const val NanosPerSecond = 1_000_000_000.0
    }
}

private class ProviderCircuitBreakers(
    private val config: BffConfig,
    allowedProviders: Set<Pair<String, TelemetryProvider>>,
    private val onTransition: (ProviderTelemetryLabels, Boolean) -> Unit,
    private val onRejected: (ProviderTelemetryLabels) -> Unit,
    private val clock: Clock,
) {
    private val breakers = allowedProviders.flatMap { (city, provider) ->
        TelemetryCapability.entries.map { capability ->
            BreakerKey(city, provider, capability) to CircuitBreaker(config, clock)
        }
    }.toMap()

    suspend fun <T> execute(labels: ProviderTelemetryLabels, block: suspend () -> T): T {
        val key = BreakerKey(labels.city, labels.provider, labels.capability)
        val breaker = checkNotNull(breakers[key]) { "Unknown bounded provider telemetry key" }
        val admission = breaker.admit()
        if (!admission.allowed) {
            onRejected(labels)
            throw ProviderUnavailable("The transit provider is temporarily unavailable", admission.retryAfterSeconds)
        }
        try {
            val value = block()
            if (breaker.onSuccess(admission.halfOpen)) onTransition(labels, false)
            return value
        } catch (failure: CancellationException) {
            breaker.onCancellation(admission.halfOpen)
            throw failure
        } catch (failure: Throwable) {
            if (breaker.onFailure(admission.halfOpen, failure is ProviderUnavailable || failure is ProviderTimeout)) {
                onTransition(labels, true)
            }
            throw failure
        }
    }

    private data class BreakerKey(
        val city: String,
        val provider: TelemetryProvider,
        val capability: TelemetryCapability,
    )
}

private class CircuitBreaker(
    private val config: BffConfig,
    private val clock: Clock,
) {
    private val lock = Any()
    private val failures = ArrayDeque<Instant>()
    private var openUntil: Instant? = null
    private var halfOpenInFlight = false

    fun admit(): CircuitAdmission = synchronized(lock) {
        val now = clock.instant()
        val open = openUntil
        if (open == null) return@synchronized CircuitAdmission(allowed = true, halfOpen = false, retryAfterSeconds = 0)
        if (now.isBefore(open)) return@synchronized CircuitAdmission(
            allowed = false,
            halfOpen = false,
            retryAfterSeconds = Duration.between(now, open).seconds.coerceAtLeast(1).coerceAtMost(config.circuitOpenSeconds).toInt(),
        )
        if (halfOpenInFlight) return@synchronized CircuitAdmission(
            allowed = false,
            halfOpen = false,
            retryAfterSeconds = 1,
        )
        halfOpenInFlight = true
        CircuitAdmission(allowed = true, halfOpen = true, retryAfterSeconds = 0)
    }

    fun onSuccess(halfOpen: Boolean): Boolean = synchronized(lock) {
        if (!halfOpen) return@synchronized false
        halfOpenInFlight = false
        val wasOpen = openUntil != null
        openUntil = null
        failures.clear()
        wasOpen
    }

    fun onFailure(halfOpen: Boolean, eligible: Boolean): Boolean = synchronized(lock) {
        val now = clock.instant()
        if (halfOpen) halfOpenInFlight = false
        if (!eligible) return@synchronized false
        if (halfOpen) return@synchronized open(now)
        while (failures.firstOrNull()?.let { Duration.between(it, now).seconds >= config.circuitWindowSeconds } == true) {
            failures.removeFirst()
        }
        failures.addLast(now)
        if (failures.size >= config.circuitFailureThreshold) open(now) else false
    }

    /**
     * A cancelled half-open coroutine has not established either recovery or a new failure.
     * Release its single-flight admission under the same lock so a later caller can probe again,
     * while retaining the open state until a real outcome closes or reopens it.
     */
    fun onCancellation(halfOpen: Boolean) = synchronized(lock) {
        if (halfOpen) halfOpenInFlight = false
    }

    private fun open(now: Instant): Boolean {
        val alreadyOpen = openUntil?.let(now::isBefore) == true
        openUntil = now.plusSeconds(config.circuitOpenSeconds)
        failures.clear()
        return !alreadyOpen
    }
}

    private data class CircuitAdmission(val allowed: Boolean, val halfOpen: Boolean, val retryAfterSeconds: Int)
