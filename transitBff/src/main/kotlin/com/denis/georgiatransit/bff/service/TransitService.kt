package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.RouteNotFound
import com.denis.georgiatransit.bff.api.ServiceFailure
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.StopNotFound
import com.denis.georgiatransit.bff.api.UpstreamTimeout
import com.denis.georgiatransit.bff.api.VehiclePage
import com.denis.georgiatransit.bff.cache.BoundedKeyedTtlCache
import com.denis.georgiatransit.bff.cache.SingleFlight
import com.denis.georgiatransit.bff.control.CapabilitySnapshotSource
import com.denis.georgiatransit.bff.control.EffectiveCapabilitySnapshot
import com.denis.georgiatransit.bff.control.EffectiveCity
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.geo.haversineMeters
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.NormalizedResponseValidator
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderConflict
import com.denis.georgiatransit.bff.provider.ProviderFailure
import com.denis.georgiatransit.bff.provider.ProviderInvalidArgument
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private const val MaximumDirectoryCacheKeys = 256
private const val MaximumRetryAfterSeconds = 86_400
private val routeListJson = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

class TransitService(
    private val capabilitySnapshots: CapabilitySnapshotSource,
    directoryCacheTtlSeconds: Long,
    shapeCacheTtlSeconds: Long,
    realtimeSingleFlightSeconds: Long,
) : AutoCloseable {
    constructor(
        registry: ProviderRegistry,
        directoryCacheTtlSeconds: Long,
        shapeCacheTtlSeconds: Long,
        realtimeSingleFlightSeconds: Long,
    ) : this(
        capabilitySnapshots = IntrinsicCapabilitySnapshotSource(registry),
        directoryCacheTtlSeconds = directoryCacheTtlSeconds,
        shapeCacheTtlSeconds = shapeCacheTtlSeconds,
        realtimeSingleFlightSeconds = realtimeSingleFlightSeconds,
    )

    private val directoryTtl = directoryCacheTtlSeconds.seconds
    private val shapeTtl = shapeCacheTtlSeconds.seconds
    private val routeCaches = BoundedKeyedTtlCache<String, List<Route>>(directoryTtl, MaximumDirectoryCacheKeys)
    private val routeDetailCaches = BoundedKeyedTtlCache<String, Route>(directoryTtl, MaximumDirectoryCacheKeys)
    private val stopCaches = BoundedKeyedTtlCache<String, List<Stop>>(directoryTtl, MaximumDirectoryCacheKeys)
    private val shapeCaches = BoundedKeyedTtlCache<String, Shape>(shapeTtl, MaximumDirectoryCacheKeys)
    private val realTime = SingleFlight<String, Any>(realtimeSingleFlightSeconds.seconds)
    private val cacheRevisionMutex = Mutex()
    private var cacheGeneration = -1L

    val isReady: Boolean get() = capabilitySnapshots.current().isReady

    /** City listing is derived directly from the current atomic snapshot, never a directory TTL. */
    suspend fun cities(): List<City> = snapshot().cities.values.map(EffectiveCity::city)

    suspend fun routes(cityId: String, locale: String, mode: String?): List<Route> {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.routes, "Routes")
        return cache(routeCaches, snapshot, "$cityId|$locale|${mode ?: "all"}") {
            providerCall(
                loader = { effectiveCity.adapter.routes(locale, mode) },
                validator = { NormalizedResponseValidator.routes(effectiveCity.city, it) },
            )
        }
    }

    suspend fun route(cityId: String, routeId: String, locale: String): Route {
        val snapshot = snapshot()
        return route(snapshot, cityId, routeId, locale)
    }

    private suspend fun route(
        snapshot: EffectiveCapabilitySnapshot,
        cityId: String,
        routeId: String,
        locale: String,
    ): Route {
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.routes, "Routes")
        return cache(routeDetailCaches, snapshot, "$cityId|$routeId|$locale") {
            providerCall(
                loader = { effectiveCity.adapter.route(routeId, locale) },
                validator = { NormalizedResponseValidator.route(cityId, it, routeId) },
            )
        }
    }

    suspend fun directionStops(
        cityId: String,
        routeId: String,
        directionId: String,
        locale: String,
    ): List<Stop> {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.stops, "Stops")
        val route = route(snapshot, cityId, routeId, locale)
        return cache(stopCaches, snapshot, "$cityId|$routeId|$directionId|$locale") {
            providerCall(
                loader = { effectiveCity.adapter.directionStops(routeId, directionId, locale) },
                validator = { NormalizedResponseValidator.directionStops(cityId, route, directionId, it) },
            )
        }
    }

    suspend fun shape(cityId: String, routeId: String, directionId: String): Shape {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.routeGeometry, "Route geometry")
        val route = route(snapshot, cityId, routeId, "en")
        return cache(shapeCaches, snapshot, "$cityId|$routeId|$directionId") {
            providerCall(
                loader = { effectiveCity.adapter.shape(routeId, directionId) },
                validator = { NormalizedResponseValidator.shape(cityId, route, directionId, it) },
            )
        }
    }

    suspend fun nearbyStops(
        cityId: String,
        origin: GeoPoint,
        radiusMeters: Int,
        limit: Int,
        locale: String,
    ): List<Stop> {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.stops, "Stops")
        val stops = cache(stopCaches, snapshot, "$cityId|directory|$locale") {
            providerCall(
                loader = { effectiveCity.adapter.stopDirectory(locale) },
                validator = { NormalizedResponseValidator.stops(effectiveCity.city, it) },
            )
        }
        return stops.asSequence()
            .map { it to haversineMeters(origin, it.position) }
            .filter { (_, distanceMeters) -> distanceMeters <= radiusMeters }
            .sortedBy { (_, distanceMeters) -> distanceMeters }
            .map { (stop, _) -> stop }
            .take(limit)
            .toList()
    }

    suspend fun vehicles(cityId: String, routeId: String, directionId: String?): VehiclePage {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.vehiclePositions, "Vehicle positions")
        val route = route(snapshot, cityId, routeId, "en")
        return realtime(snapshot, "vehicle|$cityId|$routeId|${directionId ?: "all"}") {
            providerCall(
                loader = {
                    effectiveCity.adapter.vehicles(routeId, directionId).let {
                        VehiclePage(it.items, it.observedAt.toString(), it.maxAgeSeconds, it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.vehiclePage(cityId, route, directionId, it) },
            )
        }
    }

    suspend fun arrivals(cityId: String, stopId: String, limit: Int, locale: String): ArrivalPage {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.officialArrivals, "Official arrivals")
        return realtime(snapshot, "arrival|$cityId|$stopId|$limit|$locale") {
            providerCall(
                loader = {
                    effectiveCity.adapter.arrivals(stopId, limit, locale).let {
                        ArrivalPage(it.items, it.source, it.observedAt.toString(), it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.arrivalPage(cityId, stopId, it) },
            )
        }
    }

    suspend fun journeys(cityId: String, query: JourneyQuery): JourneyPage {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.tripPlanning, "Trip planning")
        return realtime(
            snapshot,
            "journey|$cityId|${query.from.latitude},${query.from.longitude}|" +
                "${query.to.latitude},${query.to.longitude}|" +
                "${query.departureAt}|${query.locale}|${query.maxTransfers}",
        ) {
            providerCall(
                loader = { JourneyPage(effectiveCity.adapter.journeys(query), Instant.now().toString()) },
                validator = { NormalizedResponseValidator.journeyPage(cityId, it) },
            )
        }
    }

    fun routeEtag(routes: List<Route>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(routeListJson.encodeToString(routes).toByteArray())
        return "\"${digest.joinToString("") { "%02x".format(it) }}\""
    }

    override fun close() {
        routeCaches.close()
        routeDetailCaches.close()
        stopCaches.close()
        shapeCaches.close()
        realTime.close()
    }

    private suspend fun snapshot(): EffectiveCapabilitySnapshot {
        val currentSnapshot = capabilitySnapshots.current()
        cacheRevisionMutex.withLock {
            if (currentSnapshot.generation > cacheGeneration) {
                routeCaches.clear()
                routeDetailCaches.clear()
                stopCaches.clear()
                shapeCaches.clear()
                cacheGeneration = currentSnapshot.generation
            }
        }
        return currentSnapshot
    }

    private fun requireCapability(cityId: String, supported: Boolean, capability: String) {
        if (!supported) throw CapabilityNotAvailable("$capability is not available for city '$cityId'")
    }

    private suspend fun <T> cache(
        cache: BoundedKeyedTtlCache<String, T>,
        snapshot: EffectiveCapabilitySnapshot,
        key: String,
        loader: suspend () -> T,
    ): T = cache.getOrLoad("${snapshot.generation}|$key", loader)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> realtime(
        snapshot: EffectiveCapabilitySnapshot,
        key: String,
        loader: suspend () -> T,
    ): T =
        try {
            realTime.get("${snapshot.generation}|$key") { loader() as Any } as T
        } catch (exception: TimeoutCancellationException) {
            throw UpstreamTimeout("The transit provider did not respond in time")
        }

    private suspend fun <T> providerCall(
        loader: suspend () -> T,
        validator: (T) -> Unit = {},
    ): T =
        try {
            loader().also(validator)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ProviderFailure) {
            throw exception.toServiceFailure()
        }

    private fun ProviderFailure.toServiceFailure(): ServiceFailure =
        when (this) {
            is ProviderInvalidArgument -> com.denis.georgiatransit.bff.api.InvalidArgument(
                "The transit provider rejected the request",
            )
            is ProviderRouteNotFound -> RouteNotFound("The requested route was not found")
            is ProviderStopNotFound -> StopNotFound("The requested stop was not found")
            is ProviderConflict -> com.denis.georgiatransit.bff.api.StateConflict("The provider ID changed")
            is ProviderRateLimited -> com.denis.georgiatransit.bff.api.RequestRateLimited(
                "The transit provider is rate limited",
                retryAfterSeconds.validatedRetryAfterSeconds(),
            )
            is ProviderCapabilityUnavailable -> CapabilityNotAvailable("The requested capability is not available")
            is ProviderBadGateway -> com.denis.georgiatransit.bff.api.UpstreamBadGateway(
                "The provider returned an invalid response",
            )
            is ProviderUnavailable -> com.denis.georgiatransit.bff.api.UpstreamUnavailable(
                "The transit provider is unavailable",
                retryAfterSeconds.validatedRetryAfterSeconds(),
            )
            is ProviderTimeout -> UpstreamTimeout("The transit provider did not respond in time")
        }

    private fun Int?.validatedRetryAfterSeconds(): Int? =
        takeIf { it in 1..MaximumRetryAfterSeconds }
}
