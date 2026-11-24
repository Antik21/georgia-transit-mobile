package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.config.KutaisiAzryConfig
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderJsonDecodeFailure
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal interface AzryFeed : AutoCloseable {
    suspend fun routes(locale: String): List<AzryRoute>
    suspend fun route(id: String, locale: String): AzryRoute
    suspend fun stops(locale: String): List<AzryStop>
    suspend fun directionStops(id: String, pattern: String, locale: String): List<AzryStop>
    suspend fun polylines(id: String, patterns: List<String>): Map<String, AzryPolyline>
    suspend fun positions(id: String, patterns: List<String>): Map<String, List<AzryPosition>>
    suspend fun arrivals(stopId: String, locale: String, limit: Int): List<AzryArrival>
    suspend fun stopRoutes(stopId: String, locale: String): List<AzryRoute>
    suspend fun schedule(id: String, pattern: String, locale: String): List<AzrySchedule>
}

internal class AzryClient(private val apiKey: String) : AzryFeed {
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 4_000
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 4_000
        }
    }
    private val concurrency = Semaphore(4)
    private val recentStarts = ArrayDeque<Long>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun routes(locale: String): List<AzryRoute> =
        get("/api/v3/routes", "locale" to locale)

    override suspend fun route(id: String, locale: String): AzryRoute =
        get("/api/v3/routes/$id", "locale" to locale)

    override suspend fun stops(locale: String): List<AzryStop> =
        get("/api/v2/stops", "locale" to locale)

    override suspend fun directionStops(id: String, pattern: String, locale: String): List<AzryStop> =
        get("/api/v3/routes/$id/stops", "patternSuffix" to pattern, "locale" to locale)

    override suspend fun polylines(id: String, patterns: List<String>): Map<String, AzryPolyline> =
        get("/api/v3/routes/$id/polylines", "patternSuffixes" to patterns.joinToString(","))

    override suspend fun positions(id: String, patterns: List<String>): Map<String, List<AzryPosition>> =
        get("/api/v3/routes/$id/positions", "patternSuffixes" to patterns.joinToString(","))

    override suspend fun arrivals(stopId: String, locale: String, limit: Int): List<AzryArrival> =
        get(
            "/api/v2/stops/$stopId/arrival-times",
            "locale" to locale,
            "maxNumberOfArrivalTimes" to limit.toString(),
            "ignoreScheduledArrivalTimes" to "false",
        )

    override suspend fun stopRoutes(stopId: String, locale: String): List<AzryRoute> =
        get("/api/v2/stops/$stopId/routes", "locale" to locale)

    override suspend fun schedule(id: String, pattern: String, locale: String): List<AzrySchedule> =
        get("/api/v3/routes/$id/schedule", "patternSuffix" to pattern, "locale" to locale)

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T =
        concurrency.withPermit {
            reserveRequest()
            try {
                http.prepareGet(KutaisiAzryConfig.BaseUrl + path) {
                    header("X-API-Key", apiKey)
                    header(HttpHeaders.Accept, "application/json")
                    query.forEach { (name, value) -> parameter(name, value) }
                }.execute { response ->
                    when (response.status.value) {
                        200 -> json.decodeFromString<T>(response.readJson())
                        429 -> throw ProviderRateLimited(
                            "AZRY request budget was exceeded",
                            response.headers[HttpHeaders.RetryAfter]?.toIntOrNull()?.coerceIn(1, 60) ?: 15,
                        )
                        408, 504 -> throw ProviderTimeout("AZRY did not respond in time")
                        503 -> throw ProviderUnavailable("AZRY is unavailable")
                        else -> throw ProviderBadGateway("AZRY rejected the request")
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: HttpRequestTimeoutException) {
                throw ProviderTimeout("AZRY did not respond in time")
            } catch (failure: SerializationException) {
                throw ProviderJsonDecodeFailure("AZRY returned invalid data")
            } catch (failure: java.io.IOException) {
                throw ProviderUnavailable("AZRY is unavailable")
            }
        }

    private fun reserveRequest() = synchronized(recentStarts) {
        val now = System.nanoTime()
        while (recentStarts.firstOrNull()?.let { now - it >= 60_000_000_000L } == true) {
            recentStarts.removeFirst()
        }
        if (recentStarts.size >= 120) throw ProviderRateLimited("AZRY request budget was exceeded", 15)
        recentStarts.addLast(now)
    }

    override fun close() = http.close()
}

private suspend fun io.ktor.client.statement.HttpResponse.readJson(): String {
    val channel = bodyAsChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (bytes.size() + count > 2_000_000) throw ProviderJsonDecodeFailure("AZRY response exceeds its size limit")
        bytes.write(buffer, 0, count)
    }
    return bytes.toString(Charsets.UTF_8)
}
