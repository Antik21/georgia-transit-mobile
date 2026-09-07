package com.denis.georgiatransit.bff.config

private const val DefaultDirectoryCacheTtlSeconds = 14_400L
private const val DefaultShapeCacheTtlSeconds = 86_400L
private const val DefaultRealtimeSingleFlightSeconds = 15L

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
    }
}
