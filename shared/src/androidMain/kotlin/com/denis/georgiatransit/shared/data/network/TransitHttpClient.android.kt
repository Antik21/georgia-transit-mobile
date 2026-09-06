package com.denis.georgiatransit.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    config(this)
    engine {
        config {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
        }
    }
}

