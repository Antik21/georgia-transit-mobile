package com.denis.georgiatransit.bff.config

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path

private const val DefaultDirectoryCacheTtlSeconds = 14_400L
private const val DefaultShapeCacheTtlSeconds = 86_400L
private const val DefaultRealtimeSingleFlightSeconds = 15L
private const val DefaultCapabilityControlPollSeconds = 30L
private const val DefaultCapabilityControlHistoryLimit = 20
private const val DefaultProbeIntervalSeconds = 60L
private const val DefaultCircuitFailureThreshold = 3
private const val DefaultCircuitWindowSeconds = 60L
private const val DefaultCircuitOpenSeconds = 30L
private const val DefaultSchemaDriftThreshold = 3
private const val DefaultSchemaDriftWindowSeconds = 300L

enum class RuntimeMode {
    DEVELOPMENT,
    PRODUCTION,
}

class BffConfigurationException(message: String) : IllegalArgumentException(message)

data class BffConfig(
    val mode: RuntimeMode,
    val host: String,
    val port: Int,
    val directoryCacheTtlSeconds: Long,
    val shapeCacheTtlSeconds: Long,
    val realtimeSingleFlightSeconds: Long,
    val fixturesEnabled: Boolean,
    val capabilityControlPath: Path?,
    val capabilityControlStateDirectory: Path?,
    val capabilityControlPollSeconds: Long,
    val capabilityControlHistoryLimit: Int,
    val transitous: TransitousActivationConfig = TransitousActivationConfig.disabled(),
    val ttc: TtcActivationConfig = TtcActivationConfig.disabled(),
    val metricsEnabled: Boolean = true,
    val probesEnabled: Boolean = false,
    val probeIntervalSeconds: Long = DefaultProbeIntervalSeconds,
    val circuitFailureThreshold: Int = DefaultCircuitFailureThreshold,
    val circuitWindowSeconds: Long = DefaultCircuitWindowSeconds,
    val circuitOpenSeconds: Long = DefaultCircuitOpenSeconds,
    val schemaInterlockEnabled: Boolean = false,
    val schemaDriftThreshold: Int = DefaultSchemaDriftThreshold,
    val schemaDriftWindowSeconds: Long = DefaultSchemaDriftWindowSeconds,
) {
    init {
        require(host.isNotBlank()) { "BFF_HOST must not be blank" }
        require(port in 1..65_535) { "BFF_PORT must be between 1 and 65535" }
        require(directoryCacheTtlSeconds in 3_600L..21_600L) {
            "BFF_DIRECTORY_CACHE_TTL_SECONDS must be between 3600 and 21600"
        }
        require(shapeCacheTtlSeconds in 3_600L..86_400L) {
            "BFF_SHAPE_CACHE_TTL_SECONDS must be between 3600 and 86400"
        }
        require(realtimeSingleFlightSeconds in 1L..60L) {
            "BFF_REALTIME_SINGLE_FLIGHT_SECONDS must be between 1 and 60"
        }
        require(mode != RuntimeMode.PRODUCTION || !fixturesEnabled) {
            "BFF_FIXTURES_ENABLED must be false in production"
        }
        require((capabilityControlPath == null) == (capabilityControlStateDirectory == null)) {
            "BFF_CAPABILITY_CONTROL_PATH and BFF_CAPABILITY_CONTROL_STATE_DIR must be set together"
        }
        require(capabilityControlPollSeconds in 5L..300L) {
            "BFF_CAPABILITY_CONTROL_POLL_SECONDS must be between 5 and 300"
        }
        require(capabilityControlHistoryLimit in 2..50) {
            "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT must be between 2 and 50"
        }
        require(probeIntervalSeconds in 60L..300L) {
            "BFF_PROBE_INTERVAL_SECONDS must be between 60 and 300"
        }
        require(circuitFailureThreshold in 2..10) {
            "BFF_CIRCUIT_FAILURE_THRESHOLD must be between 2 and 10"
        }
        require(circuitWindowSeconds in 10L..3_600L) {
            "BFF_CIRCUIT_WINDOW_SECONDS must be between 10 and 3600"
        }
        require(circuitOpenSeconds in 5L..600L) {
            "BFF_CIRCUIT_OPEN_SECONDS must be between 5 and 600"
        }
        require(schemaDriftThreshold in 2..10) {
            "BFF_SCHEMA_DRIFT_THRESHOLD must be between 2 and 10"
        }
        require(schemaDriftWindowSeconds in 60L..3_600L) {
            "BFF_SCHEMA_DRIFT_WINDOW_SECONDS must be between 60 and 3600"
        }
        require(!schemaInterlockEnabled || capabilityControlStateDirectory != null || mode == RuntimeMode.DEVELOPMENT) {
            "BFF_SCHEMA_INTERLOCK_ENABLED requires BFF_CAPABILITY_CONTROL_STATE_DIR outside development"
        }
        require(mode != RuntimeMode.PRODUCTION || !transitous.isActivated || schemaInterlockEnabled) {
            "BFF_SCHEMA_INTERLOCK_ENABLED must be true for a production Transitous activation"
        }
        require(mode != RuntimeMode.PRODUCTION || !ttc.isActivated || schemaInterlockEnabled) {
            "BFF_SCHEMA_INTERLOCK_ENABLED must be true for a production TTC activation"
        }
        require(!(transitous.isActivated && ttc.isActivated)) {
            "Only one Tbilisi production provider adapter may be activated"
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BffConfig =
            try {
                BffConfig(
                    mode = parseMode(environment["BFF_MODE"] ?: "development"),
                    host = environment["BFF_HOST"] ?: "127.0.0.1",
                    port = parseInt(environment, "BFF_PORT", 8080, 1, 65_535),
                    directoryCacheTtlSeconds = parseLong(
                        environment,
                        "BFF_DIRECTORY_CACHE_TTL_SECONDS",
                        DefaultDirectoryCacheTtlSeconds,
                    ),
                    shapeCacheTtlSeconds = parseLong(
                        environment,
                        "BFF_SHAPE_CACHE_TTL_SECONDS",
                        DefaultShapeCacheTtlSeconds,
                    ),
                    realtimeSingleFlightSeconds = parseLong(
                        environment,
                        "BFF_REALTIME_SINGLE_FLIGHT_SECONDS",
                        DefaultRealtimeSingleFlightSeconds,
                    ),
                    fixturesEnabled = parseBoolean(environment, "BFF_FIXTURES_ENABLED", false),
                    capabilityControlPath = parseOptionalPath(environment, "BFF_CAPABILITY_CONTROL_PATH"),
                    capabilityControlStateDirectory = parseOptionalPath(
                        environment,
                        "BFF_CAPABILITY_CONTROL_STATE_DIR",
                    ),
                    capabilityControlPollSeconds = parseLong(
                        environment,
                        "BFF_CAPABILITY_CONTROL_POLL_SECONDS",
                        DefaultCapabilityControlPollSeconds,
                    ),
                    capabilityControlHistoryLimit = parseInt(
                        environment,
                        "BFF_CAPABILITY_CONTROL_HISTORY_LIMIT",
                        DefaultCapabilityControlHistoryLimit,
                        2,
                        50,
                    ),
                    transitous = TransitousActivationConfig.fromEnvironment(environment),
                    ttc = TtcActivationConfig.fromEnvironment(environment),
                    metricsEnabled = parseBoolean(environment, "BFF_METRICS_ENABLED", true),
                    probesEnabled = parseBoolean(environment, "BFF_PROBES_ENABLED", false),
                    probeIntervalSeconds = parseLong(
                        environment,
                        "BFF_PROBE_INTERVAL_SECONDS",
                        DefaultProbeIntervalSeconds,
                    ),
                    circuitFailureThreshold = parseInt(
                        environment,
                        "BFF_CIRCUIT_FAILURE_THRESHOLD",
                        DefaultCircuitFailureThreshold,
                        2,
                        10,
                    ),
                    circuitWindowSeconds = parseLong(
                        environment,
                        "BFF_CIRCUIT_WINDOW_SECONDS",
                        DefaultCircuitWindowSeconds,
                    ),
                    circuitOpenSeconds = parseLong(
                        environment,
                        "BFF_CIRCUIT_OPEN_SECONDS",
                        DefaultCircuitOpenSeconds,
                    ),
                    schemaInterlockEnabled = parseBoolean(environment, "BFF_SCHEMA_INTERLOCK_ENABLED", false),
                    schemaDriftThreshold = parseInt(
                        environment,
                        "BFF_SCHEMA_DRIFT_THRESHOLD",
                        DefaultSchemaDriftThreshold,
                        2,
                        10,
                    ),
                    schemaDriftWindowSeconds = parseLong(
                        environment,
                        "BFF_SCHEMA_DRIFT_WINDOW_SECONDS",
                        DefaultSchemaDriftWindowSeconds,
                    ),
                )
            } catch (exception: IllegalArgumentException) {
                throw BffConfigurationException(exception.message ?: "Invalid BFF configuration")
            }

        private fun parseMode(value: String): RuntimeMode =
            when (value.lowercase()) {
                "development" -> RuntimeMode.DEVELOPMENT
                "production" -> RuntimeMode.PRODUCTION
                else -> throw BffConfigurationException("BFF_MODE must be development or production")
            }

        private fun parseInt(
            environment: Map<String, String>,
            name: String,
            default: Int,
            minimum: Int,
            maximum: Int,
        ): Int {
            val value = environment[name] ?: return default
            return value.toIntOrNull()?.takeIf { it in minimum..maximum }
                ?: throw BffConfigurationException("$name must be between $minimum and $maximum")
        }

        private fun parseLong(
            environment: Map<String, String>,
            name: String,
            default: Long,
        ): Long {
            val value = environment[name] ?: return default
            return value.toLongOrNull()
                ?: throw BffConfigurationException("$name must be a whole number")
        }

        private fun parseBoolean(
            environment: Map<String, String>,
            name: String,
            default: Boolean,
        ): Boolean {
            val value = environment[name] ?: return default
            return when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw BffConfigurationException("$name must be true or false")
            }
        }

        private fun parseOptionalPath(environment: Map<String, String>, name: String): Path? {
            val rawValue = environment[name] ?: return null
            if (rawValue.isBlank()) throw BffConfigurationException("$name must not be blank")
            return try {
                Path.of(rawValue).toAbsolutePath().normalize()
            } catch (_: IllegalArgumentException) {
                throw BffConfigurationException("$name is invalid")
            }
        }
    }
}

/**
 * Server-only TTC activation. The secret is deliberately private and this class has a redacted
 * string representation so an accidental configuration log cannot disclose it.
 */
class TtcActivationConfig private constructor(
    val enabled: Boolean,
    val baseUrl: URI?,
    private val apiKey: String?,
    val requestTimeoutMillis: Long,
    val connectTimeoutMillis: Long,
    val socketTimeoutMillis: Long,
    val maximumRetries: Int,
    val maximumConcurrentRequests: Int,
    val maximumStartsPerMinute: Int,
    /** Explicit server-only probe target; it is never serialized, logged, or labelled. */
    val probeStopId: String?,
    val probeRealtimeExpected: Boolean,
) {
    val isActivated: Boolean get() = enabled && baseUrl != null && apiKey != null

    init {
        require(requestTimeoutMillis in MinimumRequestTimeoutMillis..MaximumRequestTimeoutMillis) {
            "TTC_REQUEST_TIMEOUT_MILLIS must be between $MinimumRequestTimeoutMillis and $MaximumRequestTimeoutMillis"
        }
        require(connectTimeoutMillis in MinimumConnectTimeoutMillis..MaximumConnectTimeoutMillis) {
            "TTC_CONNECT_TIMEOUT_MILLIS must be between $MinimumConnectTimeoutMillis and $MaximumConnectTimeoutMillis"
        }
        require(socketTimeoutMillis in MinimumSocketTimeoutMillis..MaximumSocketTimeoutMillis) {
            "TTC_SOCKET_TIMEOUT_MILLIS must be between $MinimumSocketTimeoutMillis and $MaximumSocketTimeoutMillis"
        }
        require(connectTimeoutMillis <= requestTimeoutMillis && socketTimeoutMillis <= requestTimeoutMillis) {
            "TTC connect and socket timeouts must not exceed TTC_REQUEST_TIMEOUT_MILLIS"
        }
        require(maximumRetries in 0..MaximumRetries) {
            "TTC_MAXIMUM_RETRIES must be between 0 and $MaximumRetries"
        }
        require(maximumConcurrentRequests in 1..MaximumConcurrentRequests) {
            "TTC_MAXIMUM_CONCURRENT_REQUESTS must be between 1 and $MaximumConcurrentRequests"
        }
        require(maximumStartsPerMinute in 1..MaximumStartsPerMinute) {
            "TTC_MAXIMUM_STARTS_PER_MINUTE must be between 1 and $MaximumStartsPerMinute"
        }
        require(probeStopId == null || isSafeOpaqueIdentifier(probeStopId)) {
            "TTC_PROBE_STOP_ID must be a bounded opaque identifier"
        }
        require(apiKey == null || isSafeSecret(apiKey)) {
            "TTC_API_KEY must be a bounded non-control secret value"
        }
        if (enabled) {
            requireTtcBaseUrl(baseUrl)
            require(!apiKey.isNullOrBlank()) { "TTC_API_KEY is required when TTC_ENABLED=true" }
        }
    }

    /** Only the dedicated TTC client may obtain the credential for its one request header. */
    internal fun apiKeyForRequest(): String = requireNotNull(apiKey)

    override fun toString(): String =
        "TtcActivationConfig(enabled=$enabled, activated=$isActivated, timeoutsConfigured=true, retries=$maximumRetries)"

    companion object {
        private const val DefaultRequestTimeoutMillis = 3_500L
        private const val DefaultConnectTimeoutMillis = 1_500L
        private const val DefaultSocketTimeoutMillis = 3_500L
        private const val DefaultMaximumRetries = 1
        private const val DefaultMaximumConcurrentRequests = 2
        private const val DefaultMaximumStartsPerMinute = 60
        private const val MinimumRequestTimeoutMillis = 500L
        private const val MaximumRequestTimeoutMillis = 10_000L
        private const val MinimumConnectTimeoutMillis = 250L
        private const val MaximumConnectTimeoutMillis = 5_000L
        private const val MinimumSocketTimeoutMillis = 500L
        private const val MaximumSocketTimeoutMillis = 10_000L
        private const val MaximumRetries = 2
        private const val MaximumConcurrentRequests = 4
        private const val MaximumStartsPerMinute = 120
        fun fromEnvironment(environment: Map<String, String>): TtcActivationConfig {
            val enabled = parseBoolean(environment, "TTC_ENABLED", false)
            val rawBaseUrl = environment["TTC_BASE_URL"]
            return TtcActivationConfig(
                enabled = enabled,
                baseUrl = rawBaseUrl?.takeIf(String::isNotBlank)?.let(::parseTtcBaseUrl),
                apiKey = environment["TTC_API_KEY"]?.takeIf(String::isNotBlank),
                requestTimeoutMillis = parseLong(
                    environment,
                    "TTC_REQUEST_TIMEOUT_MILLIS",
                    DefaultRequestTimeoutMillis,
                ),
                connectTimeoutMillis = parseLong(
                    environment,
                    "TTC_CONNECT_TIMEOUT_MILLIS",
                    DefaultConnectTimeoutMillis,
                ),
                socketTimeoutMillis = parseLong(
                    environment,
                    "TTC_SOCKET_TIMEOUT_MILLIS",
                    DefaultSocketTimeoutMillis,
                ),
                maximumRetries = parseInt(environment, "TTC_MAXIMUM_RETRIES", DefaultMaximumRetries),
                maximumConcurrentRequests = parseInt(
                    environment,
                    "TTC_MAXIMUM_CONCURRENT_REQUESTS",
                    DefaultMaximumConcurrentRequests,
                ),
                maximumStartsPerMinute = parseInt(
                    environment,
                    "TTC_MAXIMUM_STARTS_PER_MINUTE",
                    DefaultMaximumStartsPerMinute,
                ),
                probeStopId = environment["TTC_PROBE_STOP_ID"]?.takeIf(String::isNotBlank),
                probeRealtimeExpected = parseBoolean(environment, "TTC_PROBE_REALTIME_EXPECTED", false),
            )
        }

        fun disabled(): TtcActivationConfig = fromEnvironment(emptyMap())

        private fun parseTtcBaseUrl(value: String): URI = try {
            URI(value).also(::requireTtcBaseUrl)
        } catch (_: Exception) {
            throw BffConfigurationException("TTC_BASE_URL must be an explicit HTTPS origin or path prefix")
        }

        private fun requireTtcBaseUrl(uri: URI?) {
            require(
                uri != null && uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null,
            ) {
                "TTC_BASE_URL must be an explicit HTTPS origin or path prefix"
            }
        }

        private fun parseBoolean(environment: Map<String, String>, name: String, default: Boolean): Boolean =
            when ((environment[name] ?: return default).lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw BffConfigurationException("$name must be true or false")
            }

        private fun parseLong(environment: Map<String, String>, name: String, default: Long): Long =
            (environment[name] ?: return default).toLongOrNull()
                ?: throw BffConfigurationException("$name must be a whole number")

        private fun parseInt(environment: Map<String, String>, name: String, default: Int): Int =
            (environment[name] ?: return default).toIntOrNull()
                ?: throw BffConfigurationException("$name must be a whole number")

        private fun isSafeOpaqueIdentifier(value: String): Boolean =
            value.toByteArray(StandardCharsets.UTF_8).size <= 150 && value.none(Char::isWhitespace)

        private fun isSafeSecret(value: String): Boolean = value.length <= 512 && value.none(Char::isISOControl)
    }
}

/**
 * Hosted Transitous calls are opt-in. This stores only operator assertions/references, never
 * provider credentials; the references are deliberately not logged or exposed by the BFF.
 */
data class TransitousActivationConfig(
    val enabled: Boolean,
    val baseUrl: URI?,
    val contact: String?,
    val appVersion: String,
    val eligibilityAcknowledged: Boolean,
    val eligibilityReference: String?,
    val contactAcknowledged: Boolean,
    val routingApprovalAcknowledged: Boolean,
    val routingApprovalReference: String?,
    /** Operator-approved, raw upstream IDs used only to seed the server-side stop catalog. */
    val approvedStopIds: Set<String> = emptySet(),
    /** One explicitly provisioned, server-only probe stop; never exposed in metrics or logs. */
    val probeStopId: String? = null,
    val probeRealtimeExpected: Boolean = false,
) {
    val isActivated: Boolean
        get() = enabled && baseUrl != null && contact != null && eligibilityAcknowledged &&
            eligibilityReference != null && contactAcknowledged

    /** Routing remains unavailable unless a separate, recorded upstream approval is present. */
    val isRoutingApproved: Boolean
        get() = isActivated && routingApprovalAcknowledged && routingApprovalReference != null

    init {
        require(appVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
            "BFF_RELEASE_VERSION must be a safe version token"
        }
        eligibilityReference?.also(::requireSafeReference)
        routingApprovalReference?.also(::requireSafeReference)
        contact?.also(::requireMeaningfulContact)
        require(approvedStopIds.size <= MaximumApprovedStopIds) {
            "TRANSITOUS_TBILISI_STOP_IDS must contain at most $MaximumApprovedStopIds IDs"
        }
        approvedStopIds.forEach(::requireApprovedStopId)
        probeStopId?.also(::requireApprovedStopId)
        require(probeStopId == null || probeStopId in approvedStopIds) {
            "TRANSITOUS_PROBE_STOP_ID must be included in TRANSITOUS_TBILISI_STOP_IDS"
        }
        if (enabled) {
            requireHostedBaseUrl(baseUrl)
            require(appVersion != "development") {
                "BFF_RELEASE_VERSION is required when TRANSITOUS_ENABLED=true"
            }
            require(contact != null) { "TRANSITOUS_CONTACT is required when TRANSITOUS_ENABLED=true" }
            require(eligibilityAcknowledged) {
                "TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED must be true when TRANSITOUS_ENABLED=true"
            }
            require(eligibilityReference != null) {
                "TRANSITOUS_ELIGIBILITY_REFERENCE is required when TRANSITOUS_ENABLED=true"
            }
            require(contactAcknowledged) {
                "TRANSITOUS_CONTACT_ACKNOWLEDGED must be true when TRANSITOUS_ENABLED=true"
            }
        }
    }

    fun userAgent(): String = "GeorgiaTransitBff/$appVersion (contact: ${requireNotNull(contact)})"

    companion object {
        private const val DefaultBaseUrl = "https://api.transitous.org"
        private const val TransitousHostedApiHost = "api.transitous.org"
        private const val MaximumApprovedStopIds = 128
        private const val MaximumApprovedStopIdBytes = 150
        private val safeReference = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
        private val emailAddress = Regex(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@" +
                "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
                "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+",
        )

        fun fromEnvironment(environment: Map<String, String>): TransitousActivationConfig {
            val enabled = parseBoolean(environment, "TRANSITOUS_ENABLED", false)
            val baseUrl = if (enabled) parseHostedBaseUrl(environment["TRANSITOUS_BASE_URL"] ?: DefaultBaseUrl) else null
            val contact = environment["TRANSITOUS_CONTACT"]?.takeIf { it.isNotBlank() }
            return TransitousActivationConfig(
                enabled = enabled,
                baseUrl = baseUrl,
                contact = contact,
                appVersion = environment["BFF_RELEASE_VERSION"] ?: "development",
                eligibilityAcknowledged = parseBoolean(
                    environment,
                    "TRANSITOUS_ELIGIBILITY_ACKNOWLEDGED",
                    false,
                ),
                eligibilityReference = environment["TRANSITOUS_ELIGIBILITY_REFERENCE"]?.also(::requireSafeReference),
                contactAcknowledged = parseBoolean(environment, "TRANSITOUS_CONTACT_ACKNOWLEDGED", false),
                routingApprovalAcknowledged = parseBoolean(
                    environment,
                    "TRANSITOUS_ROUTING_APPROVAL_ACKNOWLEDGED",
                    false,
                ),
                routingApprovalReference = environment["TRANSITOUS_ROUTING_APPROVAL_REFERENCE"]?.also(::requireSafeReference),
                approvedStopIds = parseApprovedStopIds(environment["TRANSITOUS_TBILISI_STOP_IDS"]),
                probeStopId = environment["TRANSITOUS_PROBE_STOP_ID"]?.takeIf(String::isNotBlank),
                probeRealtimeExpected = parseBoolean(environment, "TRANSITOUS_PROBE_REALTIME_EXPECTED", false),
            )
        }

        fun disabled(): TransitousActivationConfig = fromEnvironment(emptyMap())

        private fun parseBoolean(environment: Map<String, String>, name: String, default: Boolean): Boolean =
            when ((environment[name] ?: return default).lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw BffConfigurationException("$name must be true or false")
            }

        private fun parseHostedBaseUrl(value: String): URI = try {
            URI(value).also(::requireHostedBaseUrl)
        } catch (exception: Exception) {
            throw BffConfigurationException(exception.message ?: "TRANSITOUS_BASE_URL is invalid")
        }

        private fun requireHostedBaseUrl(uri: URI?) {
            require(
                uri != null && uri.scheme == "https" && uri.host == TransitousHostedApiHost &&
                    (uri.port == -1 || uri.port == 443) && uri.userInfo == null &&
                    uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/"),
            ) {
                "TRANSITOUS_BASE_URL must be exactly the HTTPS api.transitous.org origin"
            }
        }

        private fun requireMeaningfulContact(value: String) {
            val contactUrl = runCatching { URI(value) }.getOrNull()
            val isHttpUrl = contactUrl != null && contactUrl.isAbsolute &&
                contactUrl.scheme in setOf("https", "http") && !contactUrl.host.isNullOrBlank() &&
                contactUrl.userInfo == null
            require(value.length <= 256 && value.none(Char::isWhitespace) && (isHttpUrl || emailAddress.matches(value))) {
                "TRANSITOUS_CONTACT must be an absolute http(s) URL or syntactically valid email address"
            }
        }

        private fun parseApprovedStopIds(value: String?): Set<String> {
            if (value == null || value.isBlank()) return emptySet()
            val stopIds = value.split(',')
            require(stopIds.none(String::isBlank) && stopIds.size == stopIds.toSet().size) {
                "TRANSITOUS_TBILISI_STOP_IDS must be a unique comma-separated list"
            }
            return stopIds.toSet()
        }

        private fun requireApprovedStopId(value: String) {
            require(
                value.isNotBlank() && value.none(Char::isWhitespace) &&
                    value.toByteArray(StandardCharsets.UTF_8).size <= MaximumApprovedStopIdBytes,
            ) {
                "TRANSITOUS_TBILISI_STOP_IDS contains an invalid stop ID"
            }
        }

        private fun requireSafeReference(value: String) {
            require(safeReference.matches(value)) {
                "Transitous policy and approval references must be safe, non-secret identifiers"
            }
        }
    }
}
