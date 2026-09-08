package com.denis.georgiatransit.shared.data.repository

import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheEntry
import com.denis.georgiatransit.shared.data.cache.TransitClock
import com.denis.georgiatransit.shared.data.cache.transitCacheDispatcher
import com.denis.georgiatransit.shared.data.network.BffResponse
import com.denis.georgiatransit.shared.data.network.TransitBffClient
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
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitRepositoryException
import com.denis.georgiatransit.shared.domain.repository.canUseLastKnownGood
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

private const val CityCacheKey = "transit-bff-v1.cities"
private const val RouteCachePrefix = "transit-bff-v1.routes."
private const val NearbyCachePrefix = "transit-bff-v1.nearby."
private const val CityMaxChars = 64 * 1024
private const val RouteMaxChars = 128 * 1024
private const val NearbyMaxChars = 128 * 1024
private const val RouteCacheLimit = 12
private const val NearbyCacheLimit = 24
private const val DirectoryTtlMillis = 60L * 60L * 1_000L
private const val NearbyTtlMillis = 5L * 60L * 1_000L

private object SystemTransitClock : TransitClock {
    override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

@Serializable
private data class CachedRouteList(
    val request: RouteListRequest,
    val routes: List<TransitRoute>,
)

@Serializable
private data class NearbyStopRequest(
    val cityId: CityId,
    val center: GeoPoint,
    val radiusMeters: Int,
    val limit: Int,
    val locale: TransitLocale,
)

@Serializable
private data class CachedNearbyStops(
    val request: NearbyStopRequest,
    val stops: List<TransitStop>,
)

private data class TransitSnapshot(
    val cities: List<TransitCity> = emptyList(),
    val routes: Map<RouteListRequest, List<TransitRoute>> = emptyMap(),
)

/**
 * Production repository. Durable LKG covers bounded directory queries (cities, routes, nearby
 * stops); realtime, shapes, arrivals, and journey results stay in memory.
 */
class BffTransitRepository(
    private val client: TransitBffClient,
    private val cache: TransitCache,
    private val clock: TransitClock = SystemTransitClock,
    private val cacheDispatcher: CoroutineDispatcher = transitCacheDispatcher,
) : TransitRepository {
    private val refreshMutex = Mutex()
    private val hydrationMutex = Mutex()
    private val nearbyCacheMutex = Mutex()
    private var hydrated = false
    private val snapshots = MutableStateFlow(TransitSnapshot())

    override fun cities(): List<TransitCity> = snapshots.value.cities

    override suspend fun loadCityCapabilitySnapshot(): List<TransitCity> = when (val result = refreshCityCapabilities()) {
        is TransitLoadResult.Data -> result.value
        is TransitLoadResult.Empty -> emptyList()
        is TransitLoadResult.Failure -> throw TransitRepositoryException(result.error)
    }

    override suspend fun refreshCityCapabilities(): TransitLoadResult<List<TransitCity>> {
        client.configurationFailure()?.let { return TransitLoadResult.Failure(it) }
        ensureHydrated()
        return refreshMutex.withLock { refreshCityCapabilitiesLocked() }
    }

    override suspend fun revalidateCityCapabilities(): TransitLoadResult<List<TransitCity>> {
        client.configurationFailure()?.let { return TransitLoadResult.Failure(it) }
        ensureHydrated()
        return refreshMutex.withLock { refreshCityCapabilitiesLocked(forceNetwork = true) }
    }

    override fun routes(cityId: CityId): List<TransitRoute> =
        snapshots.value.routes[RouteListRequest(cityId)].orEmpty()

    override suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> {
        client.configurationFailure()?.let { return TransitLoadResult.Failure(it) }
        ensureHydrated()
        return refreshMutex.withLock { refreshRoutesLocked(request) }
    }

    override suspend fun route(cityId: CityId, routeId: RouteId, locale: TransitLocale): TransitLoadResult<TransitRoute> =
        client.route(cityId, routeId, locale)

    override suspend fun directionStops(cityId: CityId, routeId: RouteId, directionId: DirectionId, locale: TransitLocale): TransitLoadResult<List<TransitStop>> =
        client.directionStops(cityId, routeId, directionId, locale)

    override suspend fun directionShape(cityId: CityId, routeId: RouteId, directionId: DirectionId): TransitLoadResult<TransitShape> =
        client.directionShape(cityId, routeId, directionId)

    override suspend fun vehicles(cityId: CityId, routeId: RouteId, directionId: DirectionId?): TransitLoadResult<VehiclePage> =
        client.vehicles(cityId, routeId, directionId)

    override suspend fun nearbyStops(
        cityId: CityId,
        center: GeoPoint,
        radiusMeters: Int,
        limit: Int,
        locale: TransitLocale,
    ): TransitLoadResult<List<TransitStop>> {
        client.configurationFailure()?.let { return TransitLoadResult.Failure(it) }
        val request = NearbyStopRequest(cityId, center, radiusMeters, limit, locale)
        val now = clock.nowEpochMillis()
        val key = nearbyCacheKey(request)
        val cached = cachedNearby(key, request, now)
        if (cached?.isNearbyFresh(now) == true) {
            return cached.payload.stops.asStopResult(TransitFreshness.CacheValid, cached.validatedAtEpochMillis)
        }

        return when (val response = client.nearbyStops(cityId, center, radiusMeters, limit, locale)) {
            is TransitLoadResult.Data -> {
                storeNearby(key, request, response.value, now)
                response.value.asStopResult(TransitFreshness.Network, now)
            }
            is TransitLoadResult.Empty -> {
                storeNearby(key, request, emptyList(), now)
                TransitLoadResult.Empty(TransitFreshness.Network, validatedAtEpochMillis = now)
            }
            is TransitLoadResult.Failure -> {
                if (response.error is TransitFailure.ProviderIdChanged) {
                    nearbyCacheMutex.withLock { withCache { cache.remove(key) } }
                }
                cached.staleNearbyOrFailure(response.error)
            }
        }
    }

    override suspend fun arrivals(cityId: CityId, stopId: StopId, limit: Int, locale: TransitLocale): TransitLoadResult<ArrivalPage> =
        client.arrivals(cityId, stopId, limit, locale)

    override suspend fun journeys(
        cityId: CityId,
        from: GeoPoint,
        to: GeoPoint,
        departureAt: Instant,
        locale: TransitLocale,
        maxTransfers: Int,
    ): TransitLoadResult<JourneyPage> = client.journeys(cityId, from, to, departureAt, locale, maxTransfers)

    /** Intentionally bypasses every durable/in-memory repository cache. */
    override suspend fun walkingEstimate(
        cityId: CityId,
        from: GeoPoint,
        to: GeoPoint,
        locale: TransitLocale,
    ): TransitLoadResult<WalkingEstimate> = client.walkingEstimate(cityId, from, to, locale)

    private suspend fun ensureHydrated() {
        hydrationMutex.withLock {
            if (hydrated) return
            val cachedCities = withCache {
                cache.read<List<TransitCity>>(
                    key = CityCacheKey,
                    maxEncodedChars = CityMaxChars,
                    nowEpochMillis = clock.nowEpochMillis(),
                )?.payload.orEmpty()
            }
            snapshots.value = snapshots.value.copy(cities = cachedCities)
            hydrated = true
        }
    }

    private suspend fun refreshCityCapabilitiesLocked(
        forceNetwork: Boolean = false,
    ): TransitLoadResult<List<TransitCity>> {
        val now = clock.nowEpochMillis()
        val cached = cachedCities(now)
        if (!forceNetwork && cached?.isFresh(now) == true) {
            publishCities(cached.payload)
            return cached.payload.asCityResult(TransitFreshness.CacheValid, validatedAt = cached.validatedAtEpochMillis)
        }

        return when (val response = client.cities()) {
            is TransitLoadResult.Data -> {
                publishCities(response.value)
                withCache {
                    cache.write(
                        CityCacheKey,
                        TransitCacheEntry(fetchedAtEpochMillis = now, validatedAtEpochMillis = now, payload = response.value),
                        CityMaxChars,
                    )
                } // Write failure never invalidates fresh memory.
                invalidateRouteCachesFor(response.value, now)
                response.value.asCityResult(TransitFreshness.Network, validatedAt = now)
            }
            is TransitLoadResult.Empty -> {
                val cities = emptyList<TransitCity>()
                publishCities(cities)
                withCache {
                    cache.write(
                        CityCacheKey,
                        TransitCacheEntry(fetchedAtEpochMillis = now, validatedAtEpochMillis = now, payload = cities),
                        CityMaxChars,
                    )
                }
                invalidateRouteCachesFor(cities, now)
                TransitLoadResult.Empty(TransitFreshness.Network, validatedAtEpochMillis = now)
            }
            is TransitLoadResult.Failure -> cached.staleOrFailure(response.error)
        }
    }

    private suspend fun refreshRoutesLocked(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> {
        val now = clock.nowEpochMillis()
        val key = routeCacheKey(request)
        val cached = cachedRoutes(key, request, now)
        if (cached?.isFresh(now) == true) {
            publishRoutes(request, cached.payload.routes)
            return cached.payload.routes.asRouteResult(TransitFreshness.CacheValid, validatedAt = cached.validatedAtEpochMillis)
        }

        return when (val response = client.routes(request.cityId, request.locale, request.mode, cached?.eTag)) {
            is BffResponse.Data -> {
                publishRoutes(request, response.value)
                withCache {
                    cache.write(
                        key,
                        TransitCacheEntry(
                            fetchedAtEpochMillis = now,
                            validatedAtEpochMillis = now,
                            eTag = response.eTag,
                            payload = CachedRouteList(request, response.value),
                        ),
                        RouteMaxChars,
                    )
                    trimRouteEntries(now)
                }
                response.value.asRouteResult(TransitFreshness.Network, validatedAt = now)
            }
            is BffResponse.NotModified -> {
                if (cached == null) {
                    TransitLoadResult.Failure(TransitFailure.InvalidResponse("BFF returned 304 without a route-list cache"))
                } else {
                    val refreshed = cached.copy(validatedAtEpochMillis = now, eTag = response.eTag ?: cached.eTag)
                    publishRoutes(request, refreshed.payload.routes)
                    withCache { cache.write(key, refreshed, RouteMaxChars) }
                    refreshed.payload.routes.asRouteResult(TransitFreshness.NetworkValidated, validatedAt = now)
                }
            }
            is BffResponse.Failure -> {
                if (response.error is TransitFailure.ProviderIdChanged) invalidateRouteCache(request)
                cached.staleOrFailure(response.error) { it.payload.routes }
            }
        }
    }

    private suspend fun cachedCities(now: Long): TransitCacheEntry<List<TransitCity>>? =
        withCache { cache.read(CityCacheKey, CityMaxChars, now) }

    private suspend fun cachedRoutes(
        key: String,
        request: RouteListRequest,
        now: Long,
    ): TransitCacheEntry<CachedRouteList>? = withCache {
        cache.read<CachedRouteList>(key, RouteMaxChars, now)
            ?.takeIf { it.payload.request == request }
            ?: run {
                cache.remove(key)
                null
            }
    }

    private suspend fun cachedNearby(
        key: String,
        request: NearbyStopRequest,
        now: Long,
    ): TransitCacheEntry<CachedNearbyStops>? = nearbyCacheMutex.withLock {
        withCache {
            cache.read<CachedNearbyStops>(key, NearbyMaxChars, now)
                ?.takeIf { it.payload.request == request }
                ?: run {
                    cache.remove(key)
                    null
                }
        }
    }

    private suspend fun storeNearby(
        key: String,
        request: NearbyStopRequest,
        stops: List<TransitStop>,
        now: Long,
    ) = nearbyCacheMutex.withLock {
        withCache {
            cache.write(
                key,
                TransitCacheEntry(
                    fetchedAtEpochMillis = now,
                    validatedAtEpochMillis = now,
                    payload = CachedNearbyStops(request, stops),
                ),
                NearbyMaxChars,
            )
            trimNearbyEntries(now)
        }
    }

    /** Runs native preferences access off the UI dispatcher and awaits synchronous commit work. */
    private suspend fun <T> withCache(operation: () -> T): T = withContext(cacheDispatcher) { operation() }

    /** Called only while [refreshMutex] is held. */
    private suspend fun invalidateRouteCachesFor(cities: List<TransitCity>, now: Long) {
        val enabledRouteCities = cities.filter { it.capabilities.routes }.mapTo(mutableSetOf()) { it.id }
        withCache {
            cache.keys(RouteCachePrefix).forEach { key ->
                val entry = cache.read<CachedRouteList>(key, RouteMaxChars, now)
                if (entry == null || entry.payload.request.cityId !in enabledRouteCities) cache.remove(key)
            }
        }
        snapshots.value = snapshots.value.copy(
            routes = snapshots.value.routes.filterKeys { it.cityId in enabledRouteCities },
        )
    }

    private suspend fun invalidateRouteCache(request: RouteListRequest) {
        withCache { cache.remove(routeCacheKey(request)) }
        snapshots.value = snapshots.value.copy(routes = snapshots.value.routes - request)
    }

    /** Called only from [withCache] while [refreshMutex] is held. */
    private fun trimRouteEntries(now: Long) {
        val entries = cache.keys(RouteCachePrefix).mapNotNull { key ->
            cache.read<CachedRouteList>(key, RouteMaxChars, now)?.let { key to it }
        }
        entries
            .sortedBy { (_, entry) -> entry.validatedAtEpochMillis }
            .dropLast(RouteCacheLimit)
            .forEach { (key, _) -> cache.remove(key) }
    }

    /** Bounded exact-query LKG: old pans are evicted by validation time. */
    private fun trimNearbyEntries(now: Long) {
        val entries = cache.keys(NearbyCachePrefix).mapNotNull { key ->
            cache.read<CachedNearbyStops>(key, NearbyMaxChars, now)?.let { key to it }
        }
        entries
            .sortedBy { (_, entry) -> entry.validatedAtEpochMillis }
            .dropLast(NearbyCacheLimit)
            .forEach { (key, _) -> cache.remove(key) }
    }

    private fun routeCacheKey(request: RouteListRequest): String =
        "$RouteCachePrefix${request.cityId.value}.${request.locale.name}.${request.mode?.name ?: "all"}"

    private fun nearbyCacheKey(request: NearbyStopRequest): String = buildString {
        append(NearbyCachePrefix)
        append(request.cityId.value)
        append('.')
        append(request.center.latitude.toBits().toString(16))
        append('.')
        append(request.center.longitude.toBits().toString(16))
        append('.')
        append(request.radiusMeters)
        append('.')
        append(request.limit)
        append('.')
        append(request.locale.name)
    }

    private fun TransitCacheEntry<*>.isFresh(now: Long): Boolean =
        now >= 0L &&
            fetchedAtEpochMillis >= 0L &&
            validatedAtEpochMillis in fetchedAtEpochMillis..now &&
            now - validatedAtEpochMillis <= DirectoryTtlMillis

    private fun TransitCacheEntry<*>.isNearbyFresh(now: Long): Boolean =
        now >= 0L && fetchedAtEpochMillis >= 0L &&
            validatedAtEpochMillis in fetchedAtEpochMillis..now &&
            now - validatedAtEpochMillis <= NearbyTtlMillis

    private fun List<TransitCity>.asCityResult(freshness: TransitFreshness, validatedAt: Long): TransitLoadResult<List<TransitCity>> =
        if (isEmpty()) TransitLoadResult.Empty(freshness, validatedAtEpochMillis = validatedAt)
        else TransitLoadResult.Data(this, freshness, validatedAtEpochMillis = validatedAt)

    private fun List<TransitRoute>.asRouteResult(freshness: TransitFreshness, validatedAt: Long): TransitLoadResult<List<TransitRoute>> =
        if (isEmpty()) TransitLoadResult.Empty(freshness, validatedAtEpochMillis = validatedAt)
        else TransitLoadResult.Data(this, freshness, validatedAtEpochMillis = validatedAt)

    private fun List<TransitStop>.asStopResult(freshness: TransitFreshness, validatedAt: Long): TransitLoadResult<List<TransitStop>> =
        if (isEmpty()) TransitLoadResult.Empty(freshness, validatedAtEpochMillis = validatedAt)
        else TransitLoadResult.Data(this, freshness, validatedAtEpochMillis = validatedAt)

    private fun TransitCacheEntry<List<TransitCity>>?.staleOrFailure(error: TransitFailure): TransitLoadResult<List<TransitCity>> =
        if (this != null && error.canUseLastKnownGood) {
            publishCities(payload)
            payload.asCityResult(TransitFreshness.StaleOffline, validatedAtEpochMillis).withFailure(error)
        } else {
            TransitLoadResult.Failure(error)
        }

    private fun TransitCacheEntry<CachedRouteList>?.staleOrFailure(
        error: TransitFailure,
        payload: (TransitCacheEntry<CachedRouteList>) -> List<TransitRoute>,
    ): TransitLoadResult<List<TransitRoute>> = if (this != null && error.canUseLastKnownGood) {
        val routes = payload(this)
        publishRoutes(this.payload.request, routes)
        routes.asRouteResult(TransitFreshness.StaleOffline, validatedAtEpochMillis).withFailure(error)
    } else {
        TransitLoadResult.Failure(error)
    }

    private fun TransitCacheEntry<CachedNearbyStops>?.staleNearbyOrFailure(
        error: TransitFailure,
    ): TransitLoadResult<List<TransitStop>> = if (this != null && error.canUseLastKnownGood) {
        payload.stops.asStopResult(TransitFreshness.StaleOffline, validatedAtEpochMillis).withFailure(error)
    } else {
        TransitLoadResult.Failure(error)
    }

    private fun <T> TransitLoadResult<T>.withFailure(error: TransitFailure): TransitLoadResult<T> = when (this) {
        is TransitLoadResult.Data -> copy(revalidationFailure = error)
        is TransitLoadResult.Empty -> copy(revalidationFailure = error)
        is TransitLoadResult.Failure -> this
    }

    /** Publication is immutable and is visible to synchronous readers without mutable-map races. */
    private fun publishCities(cities: List<TransitCity>) {
        snapshots.value = snapshots.value.copy(cities = cities.toList())
    }

    private fun publishRoutes(request: RouteListRequest, routes: List<TransitRoute>) {
        snapshots.value = snapshots.value.copy(
            routes = snapshots.value.routes + (request to routes.toList()),
        )
    }
}
