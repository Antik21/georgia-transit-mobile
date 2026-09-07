package com.denis.georgiatransit.bff.config

import java.nio.file.Path

private const val DefaultDirectoryCacheTtlSeconds = 14_400L
private const val DefaultShapeCacheTtlSeconds = 86_400L
private const val DefaultRealtimeSingleFlightSeconds = 15L
private const val DefaultCapabilityControlPollSeconds = 30L
private const val DefaultCapabilityControlHistoryLimit = 20

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
