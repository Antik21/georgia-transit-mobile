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
import com.denis.georgiatransit.bff.cache.ExpiringCache
import com.denis.georgiatransit.bff.cache.SingleFlight
import com.denis.georgiatransit.bff.geo.haversineMeters
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
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
    private val registry: ProviderRegistry,
    directoryCacheTtlSeconds: Long,
    shapeCacheTtlSeconds: Long,
    realtimeSingleFlightSeconds: Long,
) : AutoCloseable {
    private val directoryTtl = directoryCacheTtlSeconds.seconds
    private val shapeTtl = shapeCacheTtlSeconds.seconds
    private val citiesCache = ExpiringCache<List<City>>(directoryTtl)
    private val routeCaches = BoundedKeyedTtlCache<String, List<Route>>(directoryTtl, MaximumDirectoryCacheKeys)
    private val routeDetailCaches = BoundedKeyedTtlCache<String, Route>(directoryTtl, MaximumDirectoryCacheKeys)
    private val stopCaches = BoundedKeyedTtlCache<String, List<Stop>>(directoryTtl, MaximumDirectoryCacheKeys)
    private val shapeCaches = BoundedKeyedTtlCache<String, Shape>(shapeTtl, MaximumDirectoryCacheKeys)
    private val realTime = SingleFlight<String, Any>(realtimeSingleFlightSeconds.seconds)

    val isReady: Boolean get() = registry.isReady

    suspend fun cities(): List<City> = citiesCache.getOrLoad {
        providerCall(loader = { registry.cities() }, validator = NormalizedResponseValidator::cities)
    }

    suspend fun routes(cityId: String, locale: String, mode: String?): List<Route> {
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.routes, "Routes")
        return cache(routeCaches, "$cityId|$locale|${mode ?: "all"}") {
            providerCall(
                loader = { adapter.routes(locale, mode) },
                validator = { NormalizedResponseValidator.routes(adapter.city, it) },
            )
        }
    }

    suspend fun route(cityId: String, routeId: String, locale: String): Route {
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.routes, "Routes")
        return cache(routeDetailCaches, "$cityId|$routeId|$locale") {
            providerCall(
                loader = { adapter.route(routeId, locale) },
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
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.stops, "Stops")
        val route = route(cityId, routeId, locale)
        return cache(stopCaches, "$cityId|$routeId|$directionId|$locale") {
            providerCall(
                loader = { adapter.directionStops(routeId, directionId, locale) },
                validator = { NormalizedResponseValidator.directionStops(cityId, route, directionId, it) },
            )
        }
    }

    suspend fun shape(cityId: String, routeId: String, directionId: String): Shape {
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.routeGeometry, "Route geometry")
        val route = route(cityId, routeId, "en")
        return cache(shapeCaches, "$cityId|$routeId|$directionId") {
            providerCall(
                loader = { adapter.shape(routeId, directionId) },
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
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.stops, "Stops")
        val stops = cache(stopCaches, "$cityId|directory|$locale") {
            providerCall(
                loader = { adapter.stopDirectory(locale) },
                validator = { NormalizedResponseValidator.stops(adapter.city, it) },
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
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.vehiclePositions, "Vehicle positions")
        val route = route(cityId, routeId, "en")
        return realtime("vehicle|$cityId|$routeId|${directionId ?: "all"}") {
            providerCall(
                loader = {
                    adapter.vehicles(routeId, directionId).let {
                        VehiclePage(it.items, it.observedAt.toString(), it.maxAgeSeconds, it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.vehiclePage(cityId, route, directionId, it) },
            )
        }
    }

    suspend fun arrivals(cityId: String, stopId: String, limit: Int, locale: String): ArrivalPage {
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.officialArrivals, "Official arrivals")
        return realtime("arrival|$cityId|$stopId|$limit|$locale") {
            providerCall(
                loader = {
                    adapter.arrivals(stopId, limit, locale).let {
                        ArrivalPage(it.items, it.source, it.observedAt.toString(), it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.arrivalPage(cityId, stopId, it) },
            )
        }
    }

    suspend fun journeys(cityId: String, query: JourneyQuery): JourneyPage {
        val adapter = adapter(cityId)
        registry.requireCapability(cityId, adapter.city.capabilities.tripPlanning, "Trip planning")
        return realtime(
            "journey|$cityId|${query.from.latitude},${query.from.longitude}|" +
                "${query.to.latitude},${query.to.longitude}|" +
                "${query.departureAt}|${query.locale}|${query.maxTransfers}",
        ) {
            providerCall(
                loader = { JourneyPage(adapter.journeys(query), Instant.now().toString()) },
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

    private fun adapter(cityId: String): CityTransitProviderAdapter = registry.adapterFor(cityId)

    private suspend fun <T> cache(
        cache: BoundedKeyedTtlCache<String, T>,
        key: String,
        loader: suspend () -> T,
    ): T = cache.getOrLoad(key, loader)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> realtime(key: String, loader: suspend () -> T): T =
        try {
            realTime.get(key) { loader() as Any } as T
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
