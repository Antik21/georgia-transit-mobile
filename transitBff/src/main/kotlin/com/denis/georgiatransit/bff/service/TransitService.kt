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
import com.denis.georgiatransit.bff.api.WalkingEstimate
import com.denis.georgiatransit.bff.cache.BoundedKeyedTtlCache
import com.denis.georgiatransit.bff.cache.CacheLookupOutcome
import com.denis.georgiatransit.bff.cache.SingleFlight
import com.denis.georgiatransit.bff.cache.SingleFlightLookupOutcome
import com.denis.georgiatransit.bff.control.CapabilitySnapshotSource
import com.denis.georgiatransit.bff.control.EffectiveCapabilitySnapshot
import com.denis.georgiatransit.bff.control.EffectiveCity
import com.denis.georgiatransit.bff.control.IntrinsicCapabilitySnapshotSource
import com.denis.georgiatransit.bff.geo.haversineMeters
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.WalkingQuery
import com.denis.georgiatransit.bff.provider.NormalizedResponseValidator
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderConflict
import com.denis.georgiatransit.bff.provider.ProviderCircuitProtectedAdapter
import com.denis.georgiatransit.bff.provider.ProviderFailure
import com.denis.georgiatransit.bff.provider.ProviderInvalidArgument
import com.denis.georgiatransit.bff.provider.ProviderJsonDecodeFailure
import com.denis.georgiatransit.bff.provider.ProviderNormalizedSchemaFailure
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable
import com.denis.georgiatransit.bff.provider.SyntheticProbeProvider
import com.denis.georgiatransit.bff.provider.SyntheticProbeResult
import com.denis.georgiatransit.bff.provider.SyntheticProbeTarget
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.CacheOutcome
import com.denis.georgiatransit.bff.observability.ProviderOutcome
import com.denis.georgiatransit.bff.observability.ProviderTelemetryLabels
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.observability.TelemetryOperation
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
    private val observability: BffObservability? = null,
    private val schemaDriftObserver: ((String, TelemetryCapability) -> Boolean)? = null,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.LIST_ROUTES)
        return cache(routeCaches, snapshot, "$cityId|$locale|${mode ?: "all"}", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.ROUTE)
        return cache(routeDetailCaches, snapshot, "$cityId|$routeId|$locale", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.DIRECTION_STOPS)
        return cache(stopCaches, snapshot, "$cityId|$routeId|$directionId|$locale", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.SHAPE)
        return cache(shapeCaches, snapshot, "$cityId|$routeId|$directionId", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.NEARBY_STOPS)
        val stops = cache(stopCaches, snapshot, "$cityId|directory|$locale", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
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
        val labels = telemetry(effectiveCity, TelemetryOperation.VEHICLES)
        val page = realtime(snapshot, "vehicle|$cityId|$routeId|${directionId ?: "all"}", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
                loader = {
                    effectiveCity.adapter.vehicles(routeId, directionId).let {
                        VehiclePage(it.items, it.observedAt.toString(), it.maxAgeSeconds, it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.vehiclePage(cityId, route, directionId, it) },
            )
        }
        recordData(labels, Instant.parse(page.observedAt), page.stale, page.items.size, realtime = true)
        return page
    }

    suspend fun arrivals(cityId: String, stopId: String, limit: Int, locale: String): ArrivalPage {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.arrivals, "Arrivals")
        val labels = telemetry(effectiveCity, TelemetryOperation.ARRIVALS)
        val page = realtime(snapshot, "arrival|$cityId|$stopId|$limit|$locale", labels) {
            providerCall(
                adapter = effectiveCity.adapter,
                labels = labels,
                loader = {
                    effectiveCity.adapter.arrivals(stopId, limit, locale).let {
                        ArrivalPage(it.items, it.source, it.observedAt.toString(), it.stale)
                    }
                },
                validator = { NormalizedResponseValidator.arrivalPage(cityId, stopId, it) },
            )
        }
        recordData(
            labels,
            Instant.parse(page.observedAt),
            page.stale,
            page.items.size,
            page.items.any { it.realtime },
        )
        return page
    }

    suspend fun journeys(cityId: String, query: JourneyQuery): JourneyPage {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.tripPlanning, "Trip planning")
        val labels = telemetry(effectiveCity, TelemetryOperation.JOURNEYS)
        // Journey coordinates are sensitive: do not turn them into a SingleFlight/LKG key.
        // They are retained only by the executing request/provider call.
        val page = providerCall(
            adapter = effectiveCity.adapter,
            labels = labels,
            loader = {
                effectiveCity.adapter.journeyPage(query).let {
                    JourneyPage(
                        items = it.items,
                        observedAt = it.observedAt.toString(),
                        source = it.source,
                        realtime = it.realtime,
                        stale = it.stale,
                    )
                }
            },
            validator = { NormalizedResponseValidator.journeyPage(cityId, it) },
        )
        recordData(labels, Instant.parse(page.observedAt), page.stale, page.items.size, page.realtime)
        return page
    }

    /**
     * Direct walking is intentionally request-scoped: no TTL, SingleFlight, LKG, disk cache, or
     * coordinate-bearing telemetry key may be used here.
     */
    suspend fun walkingEstimate(cityId: String, query: WalkingQuery): WalkingEstimate {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(cityId)
        requireCapability(cityId, effectiveCity.city.capabilities.tripPlanning, "Trip planning")
        val labels = telemetry(effectiveCity, TelemetryOperation.WALKING_ESTIMATE)
        val estimate = providerCall(
            adapter = effectiveCity.adapter,
            labels = labels,
            loader = { effectiveCity.adapter.walkingEstimate(query) },
            validator = { result ->
                if (!result.distanceMeters.isFinite() || result.distanceMeters < 0.0 || result.durationSeconds <= 0L) {
                    throw ProviderNormalizedSchemaFailure("The transit provider returned an invalid walking response")
                }
                try {
                    Instant.parse(result.observedAt)
                } catch (_: Exception) {
                    throw ProviderNormalizedSchemaFailure("The transit provider returned an invalid walking response")
                }
            },
        )
        recordData(labels, Instant.parse(estimate.observedAt), stale = false, itemCount = 1, realtime = false)
        return estimate
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
        labels: ProviderTelemetryLabels,
        loader: suspend () -> T,
    ): T = cache.getOrLoad("${snapshot.generation}|$key", { outcome ->
        observability?.recordCache(labels, outcome.telemetryOutcome())
    }, loader)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> realtime(
        snapshot: EffectiveCapabilitySnapshot,
        key: String,
        labels: ProviderTelemetryLabels,
        loader: suspend () -> T,
    ): T =
        try {
            realTime.get("${snapshot.generation}|$key", { outcome ->
                observability?.recordCache(labels, outcome.telemetryOutcome())
            }) { loader() as Any } as T
        } catch (exception: TimeoutCancellationException) {
            throw UpstreamTimeout("The transit provider did not respond in time")
        }

    private suspend fun <T> providerCall(
        adapter: com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter,
        labels: ProviderTelemetryLabels,
        syntheticProbe: Boolean = false,
        loader: suspend () -> T,
        validator: (T) -> Unit = {},
    ): T {
        val startedAt = System.nanoTime()
        try {
            // The ordinary service circuit must observe the whole logical operation, including
            // normalized-output validation. In particular, a half-open TTC probe must not close
            // after one successful HTTP sub-call if a later sub-call or its normalized result
            // makes the operation fail.
            val providerResult = if (adapter is ProviderCircuitProtectedAdapter) {
                loader().also(validator)
            } else {
                observability?.protectProviderCall(labels) { loader().also(validator) }
                    ?: loader().also(validator)
            }
            return providerResult.also {
                observability?.recordProviderResult(labels, ProviderOutcome.SUCCESS, System.nanoTime() - startedAt)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ProviderJsonDecodeFailure) {
            observability?.recordProviderResult(labels, ProviderOutcome.JSON_DECODE, System.nanoTime() - startedAt)
            recordSchemaInterlockIfLatched(labels, syntheticProbe)
            throw exception.toServiceFailure()
        } catch (exception: ProviderNormalizedSchemaFailure) {
            observability?.recordProviderResult(labels, ProviderOutcome.NORMALIZED_SCHEMA, System.nanoTime() - startedAt)
            recordSchemaInterlockIfLatched(labels, syntheticProbe)
            throw exception.toServiceFailure()
        } catch (exception: ProviderFailure) {
            observability?.recordProviderResult(labels, exception.telemetryOutcome(), System.nanoTime() - startedAt)
            throw exception.toServiceFailure()
        }
    }

    internal suspend fun runSyntheticProbe(target: SyntheticProbeTarget): SyntheticProbeResult {
        val snapshot = snapshot()
        val effectiveCity = snapshot.city(target.cityId)
        require(effectiveCity.adapter.telemetryProvider == target.provider) { "Invalid synthetic probe target" }
        requireCapability(target.cityId, effectiveCity.city.capabilities.capabilityEnabled(target.capability), "Probe capability")
        val adapter = effectiveCity.adapter as? SyntheticProbeProvider
            ?: throw CapabilityNotAvailable("The requested capability is not available")
        val labels = ProviderTelemetryLabels(target.cityId, target.provider, target.capability, target.operation)
        val result = providerCall(
            adapter = effectiveCity.adapter,
            labels = labels,
            syntheticProbe = true,
            loader = { adapter.probe(target) },
        )
        recordData(labels, result.observedAt, result.stale, result.itemCount, result.realtime, probe = true)
        return result
    }

    internal suspend fun isSyntheticProbeEnabled(target: SyntheticProbeTarget): Boolean {
        val currentSnapshot = snapshot()
        val effectiveCity = currentSnapshot.cities[target.cityId] ?: return false
        return effectiveCity.adapter.telemetryProvider == target.provider &&
            effectiveCity.adapter is SyntheticProbeProvider &&
            effectiveCity.city.capabilities.capabilityEnabled(target.capability)
    }

    private fun recordSchemaInterlockIfLatched(labels: ProviderTelemetryLabels, syntheticProbe: Boolean) {
        if (syntheticProbe && schemaDriftObserver?.invoke(labels.city, labels.capability) == true) {
            observability?.recordSchemaInterlock(labels)
        }
    }

    private fun telemetry(effectiveCity: EffectiveCity, operation: TelemetryOperation): ProviderTelemetryLabels =
        ProviderTelemetryLabels(
            city = effectiveCity.city.id,
            provider = effectiveCity.adapter.telemetryProvider,
            capability = operation.capability,
            operation = operation,
        )

    private fun recordData(
        labels: ProviderTelemetryLabels,
        observedAt: Instant,
        stale: Boolean,
        itemCount: Int,
        realtime: Boolean,
        probe: Boolean = false,
    ) {
        observability?.recordDataResult(labels, observedAt, stale, itemCount, realtime, probe)
    }

    private fun CacheLookupOutcome.telemetryOutcome(): CacheOutcome = when (this) {
        CacheLookupOutcome.HIT -> CacheOutcome.HIT
        CacheLookupOutcome.MISS_OWNER -> CacheOutcome.MISS_OWNER
        CacheLookupOutcome.COALESCED -> CacheOutcome.COALESCED
    }

    private fun SingleFlightLookupOutcome.telemetryOutcome(): CacheOutcome = when (this) {
        SingleFlightLookupOutcome.MISS_OWNER -> CacheOutcome.MISS_OWNER
        SingleFlightLookupOutcome.COALESCED -> CacheOutcome.COALESCED
    }

    private fun com.denis.georgiatransit.bff.api.CityCapabilities.capabilityEnabled(
        capability: TelemetryCapability,
    ): Boolean = when (capability) {
        TelemetryCapability.ROUTES -> routes
        TelemetryCapability.STOPS -> stops
        TelemetryCapability.ROUTE_GEOMETRY -> routeGeometry
        TelemetryCapability.VEHICLE_POSITIONS -> vehiclePositions
        TelemetryCapability.ARRIVALS -> arrivals
        TelemetryCapability.TRIP_PLANNING -> tripPlanning
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

    private fun ProviderFailure.telemetryOutcome(): ProviderOutcome =
        when (this) {
            is ProviderInvalidArgument -> ProviderOutcome.INVALID_ARGUMENT
            is ProviderRouteNotFound,
            is ProviderStopNotFound,
            is ProviderConflict,
            -> ProviderOutcome.INVALID_ARGUMENT
            is ProviderRateLimited -> ProviderOutcome.RATE_LIMITED
            is ProviderCapabilityUnavailable -> ProviderOutcome.CAPABILITY_UNAVAILABLE
            is ProviderBadGateway -> ProviderOutcome.BAD_GATEWAY
            is ProviderUnavailable -> ProviderOutcome.UNAVAILABLE
            is ProviderTimeout -> ProviderOutcome.TIMEOUT
        }

    private fun Int?.validatedRetryAfterSeconds(): Int? =
        takeIf { it in 1..MaximumRetryAfterSeconds }
}
