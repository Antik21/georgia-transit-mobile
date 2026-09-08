package com.denis.georgiatransit.shared.domain.repository

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Consumers can distinguish network data, valid cache, and an offline last-known-good value. */
enum class TransitFreshness { Network, CacheValid, NetworkValidated, StaleOffline }

sealed interface TransitFailure {
    val requestId: String?

    data class Configuration(val detail: String) : TransitFailure { override val requestId: String? = null }
    data class Transport(val detail: String) : TransitFailure { override val requestId: String? = null }
    data class Timeout(val detail: String = "Transit BFF request timed out") : TransitFailure { override val requestId: String? = null }
    data class InvalidResponse(val detail: String, override val requestId: String? = null) : TransitFailure
    data class Serialization(val detail: String, override val requestId: String? = null) : TransitFailure
    data class InvalidArgument(val message: String, override val requestId: String?) : TransitFailure
    data class CityNotFound(val message: String, override val requestId: String?) : TransitFailure
    data class RouteNotFound(val message: String, override val requestId: String?) : TransitFailure
    data class StopNotFound(val message: String, override val requestId: String?) : TransitFailure
    data class ProviderIdChanged(val message: String, override val requestId: String?) : TransitFailure
    data class RateLimited(val message: String, val retryAfterSeconds: Int?, override val requestId: String?) : TransitFailure
    data class Internal(val message: String, override val requestId: String?) : TransitFailure
    data class CapabilityUnavailable(val message: String, override val requestId: String?) : TransitFailure
    data class UpstreamBadResponse(val message: String, override val requestId: String?) : TransitFailure
    data class UpstreamUnavailable(val message: String, val retryAfterSeconds: Int?, override val requestId: String?) : TransitFailure
    data class UpstreamTimeout(val message: String, override val requestId: String?) : TransitFailure
}

val TransitFailure.canUseLastKnownGood: Boolean
    get() = this is TransitFailure.Transport ||
        this is TransitFailure.Timeout ||
        this is TransitFailure.RateLimited ||
        this is TransitFailure.UpstreamBadResponse ||
        this is TransitFailure.UpstreamUnavailable ||
        this is TransitFailure.UpstreamTimeout

sealed interface TransitLoadResult<out T> {
    data class Data<T>(
        val value: T,
        val freshness: TransitFreshness,
        /** Present only for [TransitFreshness.StaleOffline]. */
        val revalidationFailure: TransitFailure? = null,
        val validatedAtEpochMillis: Long? = null,
    ) : TransitLoadResult<T>

    data class Empty(
        val freshness: TransitFreshness,
        val revalidationFailure: TransitFailure? = null,
        val validatedAtEpochMillis: Long? = null,
    ) : TransitLoadResult<Nothing>

    data class Failure(val error: TransitFailure) : TransitLoadResult<Nothing>
}

/** Used only by the existing list-based bootstrap adapter; new code should consume results. */
class TransitRepositoryException(val error: TransitFailure) : IllegalStateException(error.toString())

@Serializable
data class RouteListRequest(
    val cityId: CityId,
    val locale: TransitLocale = TransitLocale.English,
    val mode: TransitMode? = null,
)

interface TransitRepository {
    /** Latest in-memory snapshot for current UI. This does not initiate I/O. */
    fun cities(): List<TransitCity>

    suspend fun loadCityCapabilitySnapshot(): List<TransitCity> = cities()

    suspend fun refreshCityCapabilities(): TransitLoadResult<List<TransitCity>> =
        TransitLoadResult.Data(cities(), TransitFreshness.CacheValid)

    /** Bypasses a valid local catalog cache when an entity response proves it may be obsolete. */
    suspend fun revalidateCityCapabilities(): TransitLoadResult<List<TransitCity>> = refreshCityCapabilities()

    /** Latest in-memory snapshot for current UI. This does not initiate I/O. */
    fun routes(cityId: CityId): List<TransitRoute>

    suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> =
        TransitLoadResult.Data(routes(request.cityId), TransitFreshness.CacheValid)

    suspend fun route(cityId: CityId, routeId: RouteId, locale: TransitLocale = TransitLocale.English): TransitLoadResult<TransitRoute> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Route detail is not configured"))

    suspend fun directionStops(cityId: CityId, routeId: RouteId, directionId: DirectionId, locale: TransitLocale = TransitLocale.English): TransitLoadResult<List<TransitStop>> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Stops are not configured"))

    suspend fun directionShape(cityId: CityId, routeId: RouteId, directionId: DirectionId): TransitLoadResult<TransitShape> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Route geometry is not configured"))

    suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId? = null): TransitLoadResult<VehiclePage> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Vehicles are not configured"))

    suspend fun nearbyStops(cityId: CityId, center: GeoPoint, radiusMeters: Int, limit: Int, locale: TransitLocale = TransitLocale.English): TransitLoadResult<List<TransitStop>> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Nearby stops are not configured"))

    suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale = TransitLocale.English): TransitLoadResult<ArrivalPage> =
        TransitLoadResult.Failure(TransitFailure.Configuration("Arrivals are not configured"))

    suspend fun journeys(
        cityId: CityId,
        from: GeoPoint,
        to: GeoPoint,
        departureAt: Instant,
        locale: TransitLocale = TransitLocale.English,
        maxTransfers: Int,
    ): TransitLoadResult<JourneyPage> = TransitLoadResult.Failure(TransitFailure.Configuration("Journey planning is not configured"))
}

interface TransitSession {
    val selectedCity: StateFlow<TransitCity?>
    val selectedRouteIds: StateFlow<Set<RouteId>>

    fun selectCity(city: TransitCity)
    fun selectRoutes(routeIds: Set<RouteId>)
    /** Clears all city-dependent session state when bootstrap validation fails. */
    fun clearSelectedCity()
}
