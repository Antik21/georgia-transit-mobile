package com.denis.georgiatransit.shared.data.config

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

/**
 * A non-secret mobile-to-BFF endpoint. Production values must use HTTPS. Plain HTTP is accepted
 * only when a debug host explicitly opts into one of the local simulator/emulator loopbacks.
 */
data class BffEndpointConfiguration(
    val baseUrl: String,
    val allowInsecureDebugLoopback: Boolean = false,
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
        val debugLoopback = url.protocol.name == "http" &&
            allowInsecureDebugLoopback &&
            url.host in DebugLoopbackHosts
        return if (debugLoopback) null else ValidationFailure.InsecureOrNonLoopback
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

        private val DebugLoopbackHosts = setOf("10.0.2.2", "127.0.0.1", "::1", "localhost")
    }
}
