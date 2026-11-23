package com.denis.georgiatransit.bff.config

import java.net.URI

/** The vector basemap is disabled until an operator supplies its public BFF origin. */
data class MapProxyActivationConfig(
    val enabled: Boolean,
    val publicBaseUrl: URI?,
) {
    val isActivated get() = enabled && publicBaseUrl != null

    init {
        require(!enabled || publicBaseUrl != null) { "BFF_MAP_PUBLIC_BASE_URL is required when BFF_MAP_ENABLED=true" }
    }

    companion object {
        fun disabled() = fromEnvironment(emptyMap())

        fun fromEnvironment(
            environment: Map<String, String>,
            mode: RuntimeMode = RuntimeMode.DEVELOPMENT,
        ): MapProxyActivationConfig {
            val enabled = when ((environment["BFF_MAP_ENABLED"] ?: "false").lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw BffConfigurationException("BFF_MAP_ENABLED must be true or false")
            }
            val publicBase = environment["BFF_MAP_PUBLIC_BASE_URL"]?.takeIf(String::isNotBlank)
                ?.let { parsePublicBase(it, mode) }
            return MapProxyActivationConfig(enabled, publicBase)
        }

        private fun parsePublicBase(value: String, mode: RuntimeMode): URI = try {
            URI(value).also { uri ->
                val localHttp = uri.scheme.equals("http", true) &&
                    uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")
                val validScheme = uri.scheme.equals("https", true) || (mode == RuntimeMode.DEVELOPMENT && localHttp)
                require(validScheme && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null &&
                    uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/"))
            }
        } catch (_: Exception) {
            throw BffConfigurationException("BFF_MAP_PUBLIC_BASE_URL must be HTTPS, or a development loopback HTTP origin")
        }
    }
}
