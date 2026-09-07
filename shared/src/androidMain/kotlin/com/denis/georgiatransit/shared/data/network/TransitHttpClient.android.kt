package com.denis.georgiatransit.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

private val transitOkHttpInterceptor = AtomicReference<Interceptor?>(null)

/**
 * Installs the one optional Android-only interceptor before [createTransitHttpClient] is used.
 *
 * The reference is immutable after installation so all clients created by the sole factory get
 * the same configuration. Hosts must call this during process startup, before dependency
 * bootstrap can create a client.
 */
fun installTransitOkHttpInterceptor(interceptor: Interceptor) {
    check(transitOkHttpInterceptor.compareAndSet(null, interceptor)) {
        "The Transit OkHttp interceptor is already configured for this process."
    }
}

actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    config(this)
    engine {
        config {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
            transitOkHttpInterceptor.get()?.let(::addInterceptor)
        }
    }
}
