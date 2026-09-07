package com.denis.georgiatransit.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

fun createTransitHttpClient(): HttpClient = platformHttpClient {
    // Individual BFF calls further narrow request/socket bounds by endpoint class.
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
}
