package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Instant

/** A bounded, injectable jitter policy used only for the two permitted GET retries. */
fun interface RetryJitter { fun offsetMillis(baseDelayMillis: Long): Long }

class ExponentialRetryDelay(
    private val jitter: RetryJitter = RetryJitter { base -> Random.nextLong(0, base.coerceAtLeast(1) + 1) },
) {
    fun delayMillis(retryNumber: Int, retryAfterSeconds: Int?): Long {
        val exponential = (BaseDelayMillis * (1L shl (retryNumber - 1))).coerceAtMost(MaxDelayMillis)
        val randomized = (exponential + jitter.offsetMillis(exponential).coerceAtLeast(0)).coerceAtMost(MaxDelayMillis)
        val retryAfter = retryAfterSeconds
            ?.takeIf { it in 1..86_400 }
            ?.times(1_000L)
            ?.coerceAtMost(MaxDelayMillis)
            ?: 0L
        return max(randomized, retryAfter)
    }

    private companion object {
        const val BaseDelayMillis = 250L
        const val MaxDelayMillis = 30_000L
    }
}

internal sealed interface BffResponse<out T> {
    data class Data<T>(val value: T, val eTag: String?) : BffResponse<T>
    data class NotModified(val eTag: String?) : BffResponse<Nothing>
    data class Failure(val error: TransitFailure) : BffResponse<Nothing>
}

/**
 * Shared client for every stable published `/v1` endpoint. It is deliberately BFF-only: no
 * provider host, header, secret, or adapter type crosses this boundary.
 */
class TransitBffClient(
    private val httpClient: HttpClient,
    private val endpoint: BffEndpointConfiguration?,
    private val retryDelay: ExponentialRetryDelay = ExponentialRetryDelay(),
) {
    /** Checks configuration before cache use as well as before network I/O, so release fails closed. */
    fun configurationFailure(): TransitFailure.Configuration? = when {
        endpoint == null -> TransitFailure.Configuration("Transit BFF endpoint is missing")
        endpoint.validationFailure() != null -> TransitFailure.Configuration("Transit BFF endpoint is invalid: ${endpoint.validationFailure()}")
        else -> null
    }

    suspend fun cities(): TransitLoadResult<List<TransitCity>> =
        get<List<CityDto>>(path = listOf("v1", "cities")).map { it.map(CityDto::toDomain) }

    internal suspend fun routes(
        cityId: CityId,
        locale: TransitLocale,
        mode: TransitMode?,
        ifNoneMatch: String?,
    ): BffResponse<List<TransitRoute>> = get<List<RouteDto>>(
        path = listOf("v1", "cities", cityId.value, "routes"),
        query = listOfNotNull(
            "locale" to locale.toWire(),
            mode?.let { "mode" to it.toWire() },
        ),
        ifNoneMatch = ifNoneMatch,
    ).mapBff { routes -> routes.map { it.toDomain(cityId) } }

    suspend fun route(cityId: CityId, routeId: RouteId, locale: TransitLocale): TransitLoadResult<TransitRoute> =
        get<RouteDto>(
            path = listOf("v1", "cities", cityId.value, "routes", routeId.value),
            query = listOf("locale" to locale.toWire()),
        ).map { it.toDomain(cityId) }

    suspend fun directionStops(
        cityId: CityId,
        routeId: RouteId,
        directionId: DirectionId,
        locale: TransitLocale,
    ): TransitLoadResult<List<TransitStop>> = get<List<StopDto>>(
        path = listOf("v1", "cities", cityId.value, "routes", routeId.value, "directions", directionId.value, "stops"),
        query = listOf("locale" to locale.toWire()),
    ).map { it.map(StopDto::toDomain) }

    suspend fun directionShape(cityId: CityId, routeId: RouteId, directionId: DirectionId): TransitLoadResult<TransitShape> =
        get<ShapeDto>(
            path = listOf("v1", "cities", cityId.value, "routes", routeId.value, "directions", directionId.value, "shape"),
        ).map(ShapeDto::toDomain)

    suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> =
        get<VehiclePageDto>(
            path = listOf("v1", "cities", cityId.value, "vehicles"),
            query = listOfNotNull("routeId" to routeId.value, directionId?.let { "directionId" to it.value }),
            timeoutMillis = RealtimeTimeoutMillis,
        ).map(VehiclePageDto::toDomain)

    suspend fun nearbyStops(
        cityId: CityId,
        center: GeoPoint,
        radiusMeters: Int,
        limit: Int,
        locale: TransitLocale,
    ): TransitLoadResult<List<TransitStop>> {
        val invalid = invalidNearbyArguments(center, radiusMeters, limit)
        if (invalid != null) return TransitLoadResult.Failure(invalid)
        return get<List<StopDto>>(
            path = listOf("v1", "cities", cityId.value, "stops", "nearby"),
            query = listOf(
                "lat" to center.latitude.toString(),
                "lon" to center.longitude.toString(),
                "radiusMeters" to radiusMeters.toString(),
                "limit" to limit.toString(),
                "locale" to locale.toWire(),
            ),
        ).map { it.map(StopDto::toDomain) }
    }

    suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale): TransitLoadResult<ArrivalPage> {
        if (limit !in 1..100) return TransitLoadResult.Failure(TransitFailure.InvalidArgument("limit is invalid", null))
        return get<ArrivalPageDto>(
            path = listOf("v1", "cities", cityId.value, "stops", stopId.value, "arrivals"),
            query = listOf("limit" to limit.toString(), "locale" to locale.toWire()),
            timeoutMillis = RealtimeTimeoutMillis,
        ).map(ArrivalPageDto::toDomain)
    }

    suspend fun journeys(
        cityId: CityId,
        from: GeoPoint,
        to: GeoPoint,
        departureAt: Instant,
        locale: TransitLocale,
        maxTransfers: Int,
    ): TransitLoadResult<JourneyPage> {
        val invalid = invalidJourneyArguments(from, to, maxTransfers)
        if (invalid != null) return TransitLoadResult.Failure(invalid)
        return get<JourneyPageDto>(
            path = listOf("v1", "cities", cityId.value, "journeys"),
            query = listOf(
                "fromLat" to from.latitude.toString(),
                "fromLon" to from.longitude.toString(),
                "toLat" to to.latitude.toString(),
                "toLon" to to.longitude.toString(),
                "departureAt" to departureAt.toString(),
                "locale" to locale.toWire(),
                "maxTransfers" to maxTransfers.toString(),
            ),
        ).map(JourneyPageDto::toDomain)
    }

    private suspend inline fun <reified T> get(
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        ifNoneMatch: String? = null,
        timeoutMillis: Long = DirectoryPlannerTimeoutMillis,
    ): BffResponse<T> {
        configurationFailure()?.let { return BffResponse.Failure(it) }
        val configuredEndpoint = requireNotNull(endpoint)
        val response = executeGet(configuredEndpoint, path, query, ifNoneMatch, timeoutMillis)
        return when (response) {
            is RequestOutcome.Failure -> BffResponse.Failure(response.error)
            is RequestOutcome.Response -> decodeResponse(response.value)
        }
    }

    private suspend fun executeGet(
        configuredEndpoint: BffEndpointConfiguration,
        path: List<String>,
        query: List<Pair<String, String>>,
        ifNoneMatch: String?,
        timeoutMillis: Long,
    ): RequestOutcome {
        var retryNumber = 0
        while (true) {
            try {
                val response = httpClient.get {
                    url(configuredEndpoint.baseUrl)
                    url {
                        // Encode each opaque ID as one segment; an embedded slash must not alter routing.
                        appendPathSegments(*path.toTypedArray(), encodeSlash = true)
                        query.forEach { (name, value) -> parameters.append(name, value) }
                    }
                    ifNoneMatch?.let { header("If-None-Match", it) }
                    timeout {
                        connectTimeoutMillis = ConnectTimeoutMillis
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
                if (response.status.value in RetryableStatusCodes && retryNumber < MaxRetries) {
                    response.call.cancel()
                    retryNumber += 1
                    delay(retryDelay.delayMillis(retryNumber, response.retryAfterSeconds()))
                    continue
                }
                return RequestOutcome.Response(response)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val retryable = failure.isRetryableTransport()
                if (retryable && retryNumber < MaxRetries) {
                    retryNumber += 1
                    delay(retryDelay.delayMillis(retryNumber, null))
                    continue
                }
                return RequestOutcome.Failure(
                    if (failure.isTimeoutTransport()) TransitFailure.Timeout() else TransitFailure.Transport("BFF transport failed"),
                )
            }
        }
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): BffResponse<T> = when (response.status) {
        HttpStatusCode.OK -> try {
            BffResponse.Data(response.body<T>(), response.headers["ETag"])
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            BffResponse.Failure(TransitFailure.Serialization("BFF returned a malformed success body", response.headers["X-Request-ID"]))
        }
        HttpStatusCode.NotModified -> BffResponse.NotModified(response.headers["ETag"])
        else -> BffResponse.Failure(response.toFailure())
    }

    private suspend fun HttpResponse.toFailure(): TransitFailure {
        if (status.value == 408) return TransitFailure.Timeout()
        val requestId = headers["X-Request-ID"]
        val body = try {
            bodyAsText()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return TransitFailure.InvalidResponse("BFF returned an unreadable error body: ${status.value}", requestId)
        }
        val envelope = try {
            errorJson.decodeFromString<ErrorEnvelopeDto>(body)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            null
        }
            ?: return TransitFailure.InvalidResponse("BFF returned an undocumented status/body: ${status.value}", requestId)
        return envelope.error.toFailure()
    }

    private fun HttpResponse.retryAfterSeconds(): Int? =
        headers["Retry-After"]?.toIntOrNull()?.takeIf { it in 1..86_400 }

    private fun Throwable.isRetryableTransport(): Boolean =
        this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException || this is IOException

    private fun Throwable.isTimeoutTransport(): Boolean =
        this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

    private fun invalidNearbyArguments(center: GeoPoint, radiusMeters: Int, limit: Int): TransitFailure? = when {
        !center.latitude.isFinite() || center.latitude !in -90.0..90.0 -> TransitFailure.InvalidArgument("latitude is invalid", null)
        !center.longitude.isFinite() || center.longitude !in -180.0..180.0 -> TransitFailure.InvalidArgument("longitude is invalid", null)
        radiusMeters !in 1..50_000 -> TransitFailure.InvalidArgument("radiusMeters is invalid", null)
        limit !in 1..100 -> TransitFailure.InvalidArgument("limit is invalid", null)
        else -> null
    }

    private fun invalidJourneyArguments(from: GeoPoint, to: GeoPoint, maxTransfers: Int): TransitFailure? =
        invalidNearbyArguments(from, 1, 1) ?: invalidNearbyArguments(to, 1, 1) ?: maxTransfers.takeIf { it !in 0..6 }
            ?.let { TransitFailure.InvalidArgument("maxTransfers is invalid", null) }

    private fun TransitLocale.toWire(): String = when (this) {
        TransitLocale.Georgian -> "ka"
        TransitLocale.English -> "en"
        TransitLocale.Russian -> "ru"
    }

    private fun TransitMode.toWire(): String = when (this) {
        TransitMode.Bus -> "bus"
        TransitMode.Metro -> "metro"
        TransitMode.Tram -> "tram"
        TransitMode.Ferry -> "ferry"
    }

    private sealed interface RequestOutcome {
        data class Response(val value: HttpResponse) : RequestOutcome
        data class Failure(val error: TransitFailure) : RequestOutcome
    }

    private companion object {
        const val ConnectTimeoutMillis = 5_000L
        const val RealtimeTimeoutMillis = 8_000L
        const val DirectoryPlannerTimeoutMillis = 20_000L
        const val MaxRetries = 2
        val RetryableStatusCodes = setOf(408, 429, 502, 503, 504)
        val errorJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

private fun <T, R> BffResponse<T>.mapBff(transform: (T) -> R): BffResponse<R> = when (this) {
    is BffResponse.Data -> try {
        BffResponse.Data(transform(value), eTag)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        BffResponse.Failure(TransitFailure.Serialization("BFF returned invalid normalized data"))
    }
    is BffResponse.NotModified -> this
    is BffResponse.Failure -> this
}

private fun <T, R> BffResponse<T>.map(transform: (T) -> R): TransitLoadResult<R> = when (this) {
    is BffResponse.Data -> try {
        TransitLoadResult.Data(transform(value), TransitFreshness.Network)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        TransitLoadResult.Failure(TransitFailure.Serialization("BFF returned invalid normalized data"))
    }
    is BffResponse.NotModified -> TransitLoadResult.Failure(TransitFailure.InvalidResponse("Unexpected 304 response without cache"))
    is BffResponse.Failure -> TransitLoadResult.Failure(error)
}
