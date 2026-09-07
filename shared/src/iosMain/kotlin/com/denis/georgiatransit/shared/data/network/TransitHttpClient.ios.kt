package com.denis.georgiatransit.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSLock

private val transitUrlProtocolLock = NSLock()
private var transitUrlProtocolClass: Any? = null

/**
 * Installs one optional Foundation URL protocol class before [createTransitHttpClient] is used.
 *
 * This adapter only passes the host-supplied class to Darwin's NSURLSession configuration. It
 * contains no inspector behaviour and is inert until a host installs a class during startup.
 */
fun installTransitUrlProtocolClass(protocolClass: Any) {
    transitUrlProtocolLock.lock()
    try {
        check(transitUrlProtocolClass == null) {
            "The Transit URL protocol class is already configured for this process."
        }
        transitUrlProtocolClass = protocolClass
    } finally {
        transitUrlProtocolLock.unlock()
    }
}

private fun configuredTransitUrlProtocolClass(): Any? {
    transitUrlProtocolLock.lock()
    return try {
        transitUrlProtocolClass
    } finally {
        transitUrlProtocolLock.unlock()
    }
}

actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    config(this)
    engine {
        configuredTransitUrlProtocolClass()?.let { protocolClass ->
            configureSession {
                val configuredClasses = protocolClasses.orEmpty()
                protocolClasses =
                    buildList {
                        add(protocolClass)
                        configuredClasses.forEach { configuredClass ->
                            if (configuredClass !in this) add(configuredClass)
                        }
                    }
            }
        }
    }
}
