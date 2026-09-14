package com.denis.georgiatransit.shared.data.config

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

/**
 * A non-secret mobile-to-BFF endpoint. Production values must use HTTPS. Plain HTTP is accepted
 * only when a debug host explicitly opts into a loopback or private local-network address.
 */
data class BffEndpointConfiguration(
    val baseUrl: String,
    val allowInsecureDebugLoopback: Boolean = false,
    private val allowInsecureDebugPrivateNetwork: Boolean = false,
    /** Explicit BFF map-assets opt-in; false preserves the local native base style. */
    val mapAssetsEnabled: Boolean = false,
) {
    fun validationFailure(): ValidationFailure? {
        val url = try {
            Url(baseUrl)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return ValidationFailure.MalformedUrl
        }
        if (url.host.isNullOrBlank() || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return ValidationFailure.MalformedUrl
        if (url.encodedPath.orEmpty() !in setOf("", "/") || url.parameters.names().isNotEmpty() || !url.fragment.isNullOrEmpty()) {
            return ValidationFailure.MalformedUrl
        }
        if (url.protocol.name == "https") return null
        val debugHttp = url.protocol.name == "http" && (
            allowInsecureDebugLoopback && url.host in DebugLoopbackHosts ||
                allowInsecureDebugPrivateNetwork && url.host.isPrivateDebugHost()
            )
        return if (debugHttp) null else ValidationFailure.InsecureOrNonLoopback
    }

    enum class ValidationFailure { MalformedUrl, InsecureOrNonLoopback }

    /** A same-origin, BFF-owned map style; mobile never receives an upstream tile URL. */
    fun mapStyleUrlOrNull(): String? = if (mapAssetsEnabled && validationFailure() == null) {
        baseUrl.trimEnd('/') + "/v1/map/style.json"
    } else {
        null
    }

    companion object {
        /** Android emulator host mapping; it is deliberately unavailable to release composition. */
        val debugAndroidEmulator = BffEndpointConfiguration(
            baseUrl = "http://10.0.2.2:8080",
            allowInsecureDebugLoopback = true,
        )

        /** Android device loopback reached through `adb reverse tcp:8080 tcp:8080`. */
        val debugAndroidPhysicalDevice = BffEndpointConfiguration(
            baseUrl = "http://127.0.0.1:8080",
            allowInsecureDebugLoopback = true,
        )

        /** iOS Simulator host mapping; it is deliberately unavailable to release composition. */
        val debugIosSimulator = BffEndpointConfiguration(
            baseUrl = "http://127.0.0.1:8080",
            allowInsecureDebugLoopback = true,
        )

        val debugAndroidEmulatorWithMapAssets = debugAndroidEmulator.copy(mapAssetsEnabled = true)
        val debugAndroidPhysicalDeviceWithMapAssets = debugAndroidPhysicalDevice.copy(mapAssetsEnabled = true)
        val debugIosSimulatorWithMapAssets = debugIosSimulator.copy(mapAssetsEnabled = true)

        /**
         * Creates an endpoint for a Sandbox host composition. HTTP remains limited to loopback,
         * RFC 1918, IPv4 link-local, IPv6 unique-local/link-local, and mDNS `.local` hosts.
         */
        fun debugSandbox(
            baseUrl: String,
            mapAssetsEnabled: Boolean = true,
        ) = BffEndpointConfiguration(
            baseUrl = baseUrl,
            allowInsecureDebugLoopback = true,
            allowInsecureDebugPrivateNetwork = true,
            mapAssetsEnabled = mapAssetsEnabled,
        )

        private val DebugLoopbackHosts = setOf("10.0.2.2", "127.0.0.1", "::1", "localhost")

        private fun String.isPrivateDebugHost(): Boolean {
            val normalized = lowercase().removePrefix("[").removeSuffix("]")
            if (normalized in DebugLoopbackHosts || normalized.endsWith(".local")) return true
            if (':' in normalized && (normalized.startsWith("fc") || normalized.startsWith("fd"))) return true
            if (':' in normalized && normalized.take(3) in setOf("fe8", "fe9", "fea", "feb")) return true

            val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                octets[0] == 127 ||
                octets[0] == 169 && octets[1] == 254 ||
                octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 192 && octets[1] == 168
        }
    }
}
