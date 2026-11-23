package com.denis.georgiatransit.bff.config

class KutaisiAzryConfig private constructor(
    val enabled: Boolean,
    internal val apiKey: String?,
) {
    companion object {
        val Disabled = KutaisiAzryConfig(false, null)
        internal const val BaseUrl = "https://kutaisi-bus.msplus.ge/pis-gateway"

        fun fromEnvironment(environment: Map<String, String>): KutaisiAzryConfig {
            val enabled = when (environment["BFF_KUTAISI_AZRY_ENABLED"]?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> throw BffConfigurationException("BFF_KUTAISI_AZRY_ENABLED must be true or false")
            }
            if (!enabled) return Disabled
            val key = environment["PIS_GATEWAY_KEY"]
            require(key != null && key.length in 1..256 && key.all { it.code in 33..126 }) {
                "A valid PIS_GATEWAY_KEY server secret is required to enable Kutaisi AZRY"
            }
            return KutaisiAzryConfig(true, key)
        }
    }
}
