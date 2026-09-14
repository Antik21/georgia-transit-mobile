package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.api.WalkingEstimate
import com.denis.georgiatransit.bff.api.TransitModeValues
import com.denis.georgiatransit.bff.api.formatPublicId
import com.denis.georgiatransit.bff.config.BatumiThetaActivationConfig
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.ProviderOutcome
import com.denis.georgiatransit.bff.observability.ProviderTelemetryLabels
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.observability.TelemetryOperation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val BatumiCityId = KnownCityIds.Batumi
private const val BatumiProviderId = "theta"
private const val BatumiMaximumBodyBytes = 2 * 1024 * 1024
private const val BatumiMaximumRoutes = 128
private const val BatumiMaximumStops = 2_000
private const val BatumiMaximumStringBytes = 512
private const val BatumiMaximumVehicles = 128
private const val BatumiCatalogLkgDays = 7L
private val BatumiCatalogRefreshTtl = Duration.ofMinutes(10)
private val BatumiFailedRefreshCooldown = Duration.ofSeconds(20)
private val BatumiLiveCacheTtl = Duration.ofSeconds(4)
private val BatumiLiveMaximumAge = Duration.ofSeconds(60)
private val BatumiGlobalFeedFreshAge = Duration.ofSeconds(15)
private val BatumiJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Unreviewed, development-only Theta adapter. Catalog and live responses are independently
 * validated before publication. No raw upstream payload is retained. Live snapshots have a
 * four-second cadence and may be served stale only for one minute after an upstream outage.
 */
internal class BatumiThetaTransitProviderAdapter(
    private val activation: BatumiThetaActivationConfig,
    private val client: BatumiThetaCatalogClient = BatumiThetaClient(activation),
    private val clock: Clock = Clock.systemUTC(),
    routeWorkerPollInterval: Duration = Duration.ofSeconds(5),
    routeWorkerIdleTimeout: Duration = Duration.ofMinutes(30),
    private val allBusesClient: BatumiAllBusesClient? = null,
    private val cityFeedPollInterval: Duration = Duration.ofSeconds(5),
    private val observability: BffObservability? = null,
) : CityTransitProviderAdapter, AutoCloseable {
    init { require(activation.isActivated) { "Batumi Theta adapter requires explicit development activation" } }

    override val telemetryProvider: TelemetryProvider = TelemetryProvider.BATUMI_THETA
    override val city = City(
        id = BatumiCityId,
        name = LocalizedText(ru = "Батуми", en = "Batumi", ka = "ბათუმი"),
        center = GeoPoint(41.6461, 41.6405),
        defaultZoom = 13.0,
        capabilities = CityCapabilities(
            routes = true, stops = true, routeGeometry = true, vehiclePositions = true,
            officialArrivals = false, tripPlanning = false, arrivals = true,
        ),
        availability = CityAvailability(CityReadiness.UNREVIEWED, CitySource.UNREVIEWED_ADAPTER),
    )

    private var lastKnownGood: TimedCatalog? = null
    /** Prevent stale callers queued behind one failed refresh from serially retrying upstream. */
    private var lastRefreshFailureAt: Instant? = null
    private val catalogMutex = Mutex()
    private val liveMutex = Mutex()
    /**
     * A short server-side continuity window for an arrivals board. Theta occasionally omits a
     * vehicle from one live response, and immediately replacing the board in that case creates
     * a distracting, misleadingly volatile passenger experience.
     */
    private val arrivalCandidateMutex = Mutex()
    private val liveSnapshots = mutableMapOf<String, TimedLiveSnapshot>()
    private val arrivalCandidates = mutableMapOf<ArrivalCandidateKey, ArrivalCandidateState>()
    private val routeArrivalWorkers = RouteArrivalWorkerRegistry(
        pollInterval = routeWorkerPollInterval,
        idleTimeout = routeWorkerIdleTimeout,
        loader = ::refreshRouteArrivalSnapshot,
    )
    @Volatile private var cityFeedWorker: ContinuousSnapshotWorker<BatumiAllRoutesSnapshot>? = null

    /** Starts once after runtime capability control has published its effective snapshot. */
    internal fun startGlobalFeed(enabled: () -> Boolean) {
        if (allBusesClient == null || cityFeedWorker != null) return
        synchronized(this) {
            if (cityFeedWorker == null) {
                cityFeedWorker = ContinuousSnapshotWorker(
                    pollInterval = cityFeedPollInterval,
                    onSuccess = ::recordGlobalFeedSuccess,
                    onFailure = ::recordGlobalFeedFailure,
                    isEnabled = enabled,
                    loader = ::refreshAllRoutesSnapshot,
                )
            }
        }
    }

    private suspend fun catalog(): BatumiCatalog = catalogMutex.withLock {
        val now = clock.instant()
        lastKnownGood?.takeIf { Duration.between(it.savedAt, now) < BatumiCatalogRefreshTtl }?.let { return@withLock it.catalog }
        lastKnownGood?.takeIf {
            lastRefreshFailureAt?.let { failedAt -> Duration.between(failedAt, now) < BatumiFailedRefreshCooldown } == true
        }?.let { return@withLock it.catalog }
        try {
            BatumiThetaCatalogParser.parse(client.getDbData(), now).also { parsed ->
            NormalizedResponseValidator.routes(city, parsed.routes)
            NormalizedResponseValidator.stops(city, parsed.stops)
            parsed.routes.forEach { route ->
                NormalizedResponseValidator.directionStops(city.id, route, route.directions.single().id, parsed.stopsFor(route.id))
                NormalizedResponseValidator.shape(city.id, route, route.directions.single().id, parsed.shapes.getValue(route.id))
            }
                lastKnownGood = TimedCatalog(parsed, now)
                lastRefreshFailureAt = null
            }
        } catch (failure: ProviderFailure) {
            lastRefreshFailureAt = now
            val saved = lastKnownGood
            if (saved != null && Duration.between(saved.savedAt, now) < Duration.ofDays(BatumiCatalogLkgDays)) saved.catalog else throw failure
        }
    }

    override suspend fun routes(locale: String, mode: String?): List<Route> =
        if (mode == null || mode == TransitModeValues.Bus) catalog().routes else emptyList()

    override suspend fun route(routeId: String, locale: String): Route =
        catalog().routes.singleOrNull { it.id == routeId } ?: throw ProviderRouteNotFound("The requested route was not found")

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> {
        val snapshot = catalog()
        snapshot.requireDirection(routeId, directionId)
        return snapshot.stopsFor(routeId)
    }

    override suspend fun shape(routeId: String, directionId: String): Shape {
        val snapshot = catalog()
        snapshot.requireDirection(routeId, directionId)
        return snapshot.shapes[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    }

    override suspend fun stopDirectory(locale: String): List<Stop> = catalog().stops
    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles {
        val snapshot = catalog()
        snapshot.requireDirection(routeId, directionId ?: snapshot.route(routeId).directions.single().id)
        cityFeedWorker?.let {
            val global = globalSnapshot()
            val live = global.snapshot
            return RealtimeVehicles(
                items = live.vehiclesByRoute[routeId].orEmpty().map(BatumiLiveVehicle::vehicle),
                observedAt = live.observedAt,
                maxAgeSeconds = BatumiLiveMaximumAge.seconds.toInt(),
                stale = global.stale,
            )
        }
        val live = liveSnapshot(snapshot, routeId)
        return RealtimeVehicles(
            items = live.vehicles.map(BatumiLiveVehicle::vehicle),
            observedAt = live.observedAt,
            // This field is the maximum lifetime measured from observedAt, not the snapshot's
            // current age. Returning zero made clients expire a marker between eight-second polls.
            maxAgeSeconds = BatumiLiveMaximumAge.seconds.toInt(),
            stale = live.stale,
        )
    }

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        val snapshot = catalog()
        val stop = snapshot.stops.singleOrNull { it.id == stopId }
            ?: throw ProviderStopNotFound("The requested stop was not found")
        cityFeedWorker?.let {
            val global = globalSnapshot()
            return RealtimeArrivals(
                items = if (global.stale) {
                    emptyList()
                } else {
                    stop.routeIds
                        .flatMap { routeId -> global.snapshot.arrivalsByRoute[routeId].orEmpty() }
                        .asSequence()
                        .filter { it.stopId == stop.id }
                        .mapNotNull { it.retimedFor(clock.instant()) }
                        .sortedBy(Arrival::expectedAt)
                        .take(limit.coerceIn(1, 100))
                        .toList()
                },
                source = ArrivalSource.CLIENT_ESTIMATE,
                observedAt = global.snapshot.observedAt,
                stale = global.stale,
            )
        }
        val estimates = mutableListOf<Arrival>()
        var newest = Instant.EPOCH
        var stale = false
        for (routeId in stop.routeIds) {
            val live = liveSnapshot(snapshot, routeId)
            newest = maxOf(newest, live.observedAt)
            stale = stale || live.stale
            // A stale location remains useful on the map, but never produces a passenger ETA.
            if (!live.stale) {
                val fresh = estimateArrivals(snapshot, routeId, stop, live)
                estimates += reconcileArrivalCandidates(stop.id, routeId, live.observedAt, fresh)
            }
        }
        return RealtimeArrivals(
            // The shared client requests the public endpoint maximum so a busy interchange does
            // not silently lose otherwise qualifying vehicles from its 45-minute board.
            items = estimates.sortedBy { it.expectedAt }.take(limit.coerceIn(1, 100)),
            source = ArrivalSource.CLIENT_ESTIMATE,
            observedAt = newest,
            stale = stale,
        )
    }

    override suspend fun routeArrivals(routeId: String, limitPerStop: Int, locale: String): RealtimeArrivals {
        // Validate before allocating a long-lived job for an untrusted public identifier.
        catalog().route(routeId)
        val global = cityFeedWorker?.let { globalSnapshot() }
        val snapshot = global?.snapshot?.let { citySnapshot ->
            RealtimeArrivals(
                items = if (global.stale) emptyList() else citySnapshot.arrivalsByRoute[routeId].orEmpty(),
                source = ArrivalSource.CLIENT_ESTIMATE,
                observedAt = citySnapshot.observedAt,
                stale = global.stale,
            )
        } ?: routeArrivalWorkers.get(routeId)
        val now = clock.instant()
        return snapshot.copy(
            items = snapshot.items
                .asSequence()
                .mapNotNull { it.retimedFor(now) }
                .groupBy(Arrival::stopId)
                .values
                .flatMap { arrivals -> arrivals.sortedBy(Arrival::expectedAt).take(limitPerStop.coerceIn(1, 100)) }
                .sortedWith(compareBy(Arrival::stopId, Arrival::expectedAt)),
        )
    }
    override suspend fun journeys(query: JourneyQuery): List<Journey> = unavailable()
    override suspend fun walkingEstimate(query: WalkingQuery): WalkingEstimate = unavailable()
    override fun close() {
        cityFeedWorker?.close()
        routeArrivalWorkers.close()
        allBusesClient?.close()
        client.close()
    }

    private fun <T> unavailable(): T = throw ProviderCapabilityUnavailable("This capability is not available for Batumi")

    private suspend fun globalSnapshot(): GlobalSnapshotResult {
        val snapshot = requireNotNull(cityFeedWorker).get()
        val age = Duration.between(snapshot.observedAt, clock.instant())
        if (age.isNegative || age > BatumiLiveMaximumAge) {
            throw ProviderUnavailable("Batumi global live data is unavailable")
        }
        return GlobalSnapshotResult(snapshot, stale = age > BatumiGlobalFeedFreshAge)
    }

    private fun recordGlobalFeedSuccess(snapshot: BatumiAllRoutesSnapshot, durationNanos: Long) {
        observability?.recordProviderResult(GlobalFeedTelemetryLabels, ProviderOutcome.SUCCESS, durationNanos)
        observability?.recordDataResult(
            labels = GlobalFeedTelemetryLabels,
            observedAt = snapshot.observedAt,
            stale = false,
            itemCount = snapshot.vehiclesByRoute.values.sumOf { it.size },
            realtime = true,
            probe = false,
        )
    }

    private fun recordGlobalFeedFailure(failure: Throwable, durationNanos: Long) {
        val outcome = when (failure) {
            is ProviderTimeout -> ProviderOutcome.TIMEOUT
            is ProviderJsonDecodeFailure -> ProviderOutcome.JSON_DECODE
            is ProviderNormalizedSchemaFailure -> ProviderOutcome.NORMALIZED_SCHEMA
            is ProviderBadGateway -> ProviderOutcome.BAD_GATEWAY
            else -> ProviderOutcome.UNAVAILABLE
        }
        observability?.recordProviderResult(GlobalFeedTelemetryLabels, outcome, durationNanos)
    }

    /** One city-wide fetch, one normalized generation, and one atomic ETA board for all routes. */
    private suspend fun refreshAllRoutesSnapshot(): BatumiAllRoutesSnapshot {
        val snapshot = catalog()
        val response = requireNotNull(allBusesClient).getAllBuses()
        val observedAt = clock.instant()
        val vehiclesByRoute = BatumiAllBusesParser.parse(response, snapshot, observedAt)
        validateEtaWorkload(snapshot.routes.map { route ->
            vehiclesByRoute[route.id].orEmpty().size to snapshot.cycle(route.id).chain.size
        })
        val trackedByRoute = snapshot.routes.associate { route ->
            route.id to vehiclesByRoute[route.id].orEmpty()
        }
        val arrivalsByRoute = mutableMapOf<String, List<Arrival>>()
        snapshot.routes.forEach { route ->
            val fresh = estimateRouteArrivalCandidates(
                snapshot,
                route,
                LiveSnapshot(trackedByRoute.getValue(route.id), observedAt, stale = false),
            )
            arrivalsByRoute[route.id] = reconcileRouteArrivalCandidates(route.id, observedAt, fresh)
        }
        liveMutex.withLock {
            trackedByRoute.forEach { (routeId, tracked) ->
                liveSnapshots[routeId] = TimedLiveSnapshot(LiveSnapshot(tracked, observedAt, stale = false))
            }
        }
        return BatumiAllRoutesSnapshot(trackedByRoute, arrivalsByRoute, observedAt)
    }

    /** One in-process owner fetches a route; followers receive the same validated snapshot. */
    private suspend fun liveSnapshot(catalog: BatumiCatalog, routeId: String): LiveSnapshot = liveMutex.withLock {
        val route = catalog.route(routeId)
        val rawRouteId = catalog.rawRouteId(routeId)
        val now = clock.instant()
        val cached = liveSnapshots[routeId]
        if (cached != null && Duration.between(cached.snapshot.observedAt, now) <= BatumiLiveCacheTtl) {
            return@withLock cached.snapshot.copy(stale = false)
        }
        try {
            // Theta has no source timestamp. Record the BFF receipt time, rather than the time
            // at which the request began, so cache freshness and every normalized vehicle use the
            // same defensible observation instant.
            val response = client.getBusLocsOnRoute(rawRouteId)
            val observedAt = clock.instant()
            val parsed = BatumiThetaLiveParser.parse(response, route, observedAt)
            LiveSnapshot(parsed, observedAt, stale = false).also { liveSnapshots[routeId] = TimedLiveSnapshot(it) }
        } catch (failure: ProviderFailure) {
            val lkg = cached?.snapshot
            if (lkg != null && Duration.between(lkg.observedAt, now) <= BatumiLiveMaximumAge) {
                lkg.copy(stale = true)
            } else {
                throw failure
            }
        }
    }

    private fun estimateArrivals(
        catalog: BatumiCatalog,
        routeId: String,
        stop: Stop,
        live: LiveSnapshot,
    ): List<EstimatedArrivalCandidate> {
        val route = catalog.route(routeId)
        val cycle = catalog.cycle(routeId)
        val target = cycle.point(stop.id)
        val metersPerSecond = catalog.movingMetersPerSecond(routeId)
        return live.vehicles.mapNotNull { vehicle ->
            val bus = projectVehicle(catalog, routeId, cycle, vehicle) ?: return@mapNotNull null
            val distance = forwardCycleDistance(bus.alongMeters, target.cumulativeMeters, cycle.totalMeters)
            val dwellStops = cycle.stopsBefore(bus.alongMeters, distance)
            val etaMinutes = etaMinutes(distance, metersPerSecond, dwellStops) ?: return@mapNotNull null
            val expected = live.observedAt.plusSeconds(etaMinutes * 60L)
            EstimatedArrivalCandidate(
                vehicleId = vehicle.vehicle.id,
                arrival = Arrival(
                    stopId = stop.id,
                    routeId = routeId,
                    vehicleId = vehicle.vehicle.id,
                    // Theta has no passenger headsign. The route name is safe neutral context only.
                    headsign = route.longName,
                    expectedAt = expected.toString(),
                    expectedInMinutes = etaMinutes,
                    realtime = false,
                    cancelled = false,
                    source = ArrivalSource.CLIENT_ESTIMATE,
                ),
            )
        }
    }

    /** Produces one atomic board for every stop from the same route GPS observation. */
    private suspend fun refreshRouteArrivalSnapshot(routeId: String): RealtimeArrivals {
        val snapshot = catalog()
        val route = snapshot.route(routeId)
        val live = liveSnapshot(snapshot, routeId)
        val estimates = if (live.stale) {
            emptyList()
        } else {
            reconcileRouteArrivalCandidates(
                route.id,
                live.observedAt,
                estimateRouteArrivalCandidates(snapshot, route, live),
            )
        }
        return RealtimeArrivals(
            items = estimates.sortedWith(compareBy(Arrival::stopId, Arrival::expectedAt)),
            source = ArrivalSource.CLIENT_ESTIMATE,
            observedAt = live.observedAt,
            stale = live.stale,
        )
    }

    /** Preprojects route geometry once per GPS generation instead of once per stop request. */
    private fun estimateRouteArrivalCandidates(
        catalog: BatumiCatalog,
        route: Route,
        live: LiveSnapshot,
    ): List<EstimatedArrivalCandidate> {
        val cycle = catalog.cycle(route.id)
        val metersPerSecond = catalog.movingMetersPerSecond(route.id)
        validateEtaWorkload(listOf(live.vehicles.size to cycle.chain.size))
        return live.vehicles.flatMap { vehicle ->
            val bus = projectVehicle(catalog, route.id, cycle, vehicle) ?: return@flatMap emptyList()
            cycle.chain.mapNotNull { target ->
                val distance = forwardCycleDistance(bus.alongMeters, target.cumulativeMeters, cycle.totalMeters)
                val dwellStops = cycle.stopsBefore(bus.alongMeters, distance)
                val etaMinutes = etaMinutes(distance, metersPerSecond, dwellStops) ?: return@mapNotNull null
                EstimatedArrivalCandidate(
                    vehicleId = vehicle.vehicle.id,
                    arrival = Arrival(
                        stopId = target.stop.id,
                        routeId = route.id,
                        vehicleId = vehicle.vehicle.id,
                        headsign = route.longName,
                        expectedAt = live.observedAt.plusSeconds(etaMinutes * 60L).toString(),
                        expectedInMinutes = etaMinutes,
                        realtime = false,
                        cancelled = false,
                        source = ArrivalSource.CLIENT_ESTIMATE,
                    ),
                )
            }
        }
    }

    /**
     * Keeps an individual vehicle through one missing or unclassifiable live snapshot. Its ETA
     * is still counted down from the previously accepted absolute time; a second new snapshot
     * without confirmation removes it. Only the normalized opaque vehicle ID may be published.
     */
    private suspend fun reconcileArrivalCandidates(
        stopId: String,
        routeId: String,
        observedAt: Instant,
        fresh: List<EstimatedArrivalCandidate>,
    ): List<Arrival> = arrivalCandidateMutex.withLock {
        pruneExpiredArrivalCandidates(observedAt)
        val freshByVehicle = fresh.associateBy(EstimatedArrivalCandidate::vehicleId)
        val matchingKeys = arrivalCandidates.keys.filter { it.stopId == stopId && it.routeId == routeId }

        matchingKeys.forEach { key ->
            val previous = arrivalCandidates[key] ?: return@forEach
            val replacement = freshByVehicle[key.vehicleId]
            when {
                replacement != null -> arrivalCandidates[key] = ArrivalCandidateState(
                    arrival = replacement.arrival,
                    lastConfirmedAt = observedAt,
                    lastEvaluatedAt = observedAt,
                    consecutiveMisses = 0,
                )
                previous.lastEvaluatedAt >= observedAt -> Unit
                else -> {
                    val retained = previous.arrival.retimedFor(observedAt)
                    val misses = previous.consecutiveMisses + 1
                    if (retained == null || misses >= MaximumConsecutiveCandidateMisses) {
                        arrivalCandidates.remove(key)
                    } else {
                        arrivalCandidates[key] = previous.copy(
                            arrival = retained,
                            lastEvaluatedAt = observedAt,
                            consecutiveMisses = misses,
                        )
                    }
                }
            }
        }
        freshByVehicle.forEach { (vehicleId, candidate) ->
            val key = ArrivalCandidateKey(stopId, routeId, vehicleId)
            if (key !in arrivalCandidates) {
                arrivalCandidates[key] = ArrivalCandidateState(
                    arrival = candidate.arrival,
                    lastConfirmedAt = observedAt,
                    lastEvaluatedAt = observedAt,
                    consecutiveMisses = 0,
                )
            }
        }
        arrivalCandidates
            .asSequence()
            .filter { (key, _) -> key.stopId == stopId && key.routeId == routeId }
            .map { it.value.arrival }
            .toList()
    }

    /** Applies the same one-generation continuity rule to the precomputed city-wide board. */
    private suspend fun reconcileRouteArrivalCandidates(
        routeId: String,
        observedAt: Instant,
        fresh: List<EstimatedArrivalCandidate>,
    ): List<Arrival> = arrivalCandidateMutex.withLock {
        pruneExpiredArrivalCandidates(observedAt)
        val freshByKey = fresh.associateBy { candidate ->
            ArrivalCandidateKey(candidate.arrival.stopId, routeId, candidate.vehicleId)
        }
        val matchingKeys = arrivalCandidates.keys.filter { it.routeId == routeId }
        matchingKeys.forEach { key ->
            val previous = arrivalCandidates[key] ?: return@forEach
            val replacement = freshByKey[key]
            when {
                replacement != null -> arrivalCandidates[key] = ArrivalCandidateState(
                    arrival = replacement.arrival,
                    lastConfirmedAt = observedAt,
                    lastEvaluatedAt = observedAt,
                    consecutiveMisses = 0,
                )
                previous.lastEvaluatedAt >= observedAt -> Unit
                else -> {
                    val retained = previous.arrival.retimedFor(observedAt)
                    val misses = previous.consecutiveMisses + 1
                    if (retained == null || misses >= MaximumConsecutiveCandidateMisses) {
                        arrivalCandidates.remove(key)
                    } else {
                        arrivalCandidates[key] = previous.copy(
                            arrival = retained,
                            lastEvaluatedAt = observedAt,
                            consecutiveMisses = misses,
                        )
                    }
                }
            }
        }
        freshByKey.forEach { (key, candidate) ->
            if (key !in arrivalCandidates) {
                arrivalCandidates[key] = ArrivalCandidateState(
                    arrival = candidate.arrival,
                    lastConfirmedAt = observedAt,
                    lastEvaluatedAt = observedAt,
                    consecutiveMisses = 0,
                )
            }
        }
        pruneExpiredArrivalCandidates(observedAt)
        arrivalCandidates
            .asSequence()
            .filter { (key, _) -> key.routeId == routeId }
            .map { it.value.arrival }
            .toList()
    }

    private fun pruneExpiredArrivalCandidates(now: Instant) {
        arrivalCandidates.entries.removeIf { (_, candidate) ->
            Duration.between(candidate.lastConfirmedAt, now) > ArrivalCandidateRetention ||
                candidate.arrival.retimedFor(now) == null
        }
        while (arrivalCandidates.size > MaximumArrivalCandidates) {
            val oldest = arrivalCandidates.minByOrNull { (_, candidate) -> candidate.lastConfirmedAt } ?: return
            arrivalCandidates.remove(oldest.key)
        }
    }

}

private data class TimedCatalog(val catalog: BatumiCatalog, val savedAt: Instant)
private data class TimedLiveSnapshot(val snapshot: LiveSnapshot)
private data class LiveSnapshot(val vehicles: List<BatumiLiveVehicle>, val observedAt: Instant, val stale: Boolean)
private data class BatumiAllRoutesSnapshot(
    val vehiclesByRoute: Map<String, List<BatumiLiveVehicle>>,
    val arrivalsByRoute: Map<String, List<Arrival>>,
    val observedAt: Instant,
)
private data class GlobalSnapshotResult(val snapshot: BatumiAllRoutesSnapshot, val stale: Boolean)
internal data class BatumiLiveVehicle(val vehicle: Vehicle, val status: Int?)
private data class EstimatedArrivalCandidate(val vehicleId: String, val arrival: Arrival)
private data class ArrivalCandidateKey(val stopId: String, val routeId: String, val vehicleId: String)
private data class ArrivalCandidateState(
    val arrival: Arrival,
    val lastConfirmedAt: Instant,
    /** Prevents repeated callers receiving the same cached live snapshot from counting as misses. */
    val lastEvaluatedAt: Instant,
    val consecutiveMisses: Int,
)
internal data class BatumiCatalog(
    val routes: List<Route>, val stops: List<Stop>, val stopOrderByRoute: Map<String, List<Stop>>, val shapes: Map<String, Shape>,
    val geometries: Map<String, List<GeoPoint>>, val rawRouteIds: Map<String, String>, val cycles: Map<String, RouteCycle>,
) {
    fun stopsFor(routeId: String): List<Stop> = stopOrderByRoute[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun requireDirection(routeId: String, directionId: String) {
        if (routes.none { it.id == routeId && it.directions.single().id == directionId }) throw ProviderRouteNotFound("The requested route or direction was not found")
    }
    fun route(routeId: String): Route = routes.singleOrNull { it.id == routeId } ?: throw ProviderRouteNotFound("The requested route was not found")
    fun rawRouteId(routeId: String): String = rawRouteIds[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun geometry(routeId: String): List<GeoPoint> = geometries[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun cycle(routeId: String): RouteCycle = cycles[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun movingMetersPerSecond(routeId: String): Double =
        (BatumiMovingKmhByRawRoute[rawRouteId(routeId)] ?: BatumiFallbackMovingKmh) / 3.6
}

/** Server-only upstream client. It never exposes Theta payloads beyond this provider boundary. */
internal interface BatumiThetaCatalogClient : AutoCloseable {
    suspend fun getDbData(): String
    suspend fun getBusLocsOnRoute(rawRouteId: String): String
}

/** Server-only bulk live source. One response contains every currently published Batumi route. */
internal interface BatumiAllBusesClient : AutoCloseable {
    suspend fun getAllBuses(): String
}

internal class BatumiThetaClient(private val activation: BatumiThetaActivationConfig) : BatumiThetaCatalogClient {
    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 8_000 } }

    override suspend fun getDbData(): String = try {
        val response = http.get { url(activation.baseUrl.toString() + "/getDbData") }
        if (response.status != HttpStatusCode.OK) throw ProviderUnavailable("Batumi catalog upstream is unavailable")
        response.readBoundedUtf8(BatumiMaximumBodyBytes)
    } catch (exception: CancellationException) { throw exception
    } catch (_: HttpRequestTimeoutException) { throw ProviderTimeout("Batumi catalog timed out")
    } catch (_: ConnectTimeoutException) { throw ProviderTimeout("Batumi catalog timed out")
    } catch (_: SocketTimeoutException) { throw ProviderTimeout("Batumi catalog timed out")
    } catch (exception: ProviderFailure) { throw exception
    } catch (_: Exception) { throw ProviderUnavailable("Batumi catalog upstream is unavailable") }

    override suspend fun getBusLocsOnRoute(rawRouteId: String): String = try {
        val response = http.get { url(activation.baseUrl.toString() + "/getBusLocsOnRoute?routeId=" + java.net.URLEncoder.encode(rawRouteId, StandardCharsets.UTF_8)) }
        if (response.status != HttpStatusCode.OK) throw ProviderUnavailable("Batumi live upstream is unavailable")
        response.readBoundedUtf8(BatumiMaximumBodyBytes)
    } catch (exception: CancellationException) { throw exception
    } catch (_: HttpRequestTimeoutException) { throw ProviderTimeout("Batumi live data timed out")
    } catch (_: ConnectTimeoutException) { throw ProviderTimeout("Batumi live data timed out")
    } catch (_: SocketTimeoutException) { throw ProviderTimeout("Batumi live data timed out")
    } catch (exception: ProviderFailure) { throw exception
    } catch (_: Exception) { throw ProviderUnavailable("Batumi live upstream is unavailable") }

    override fun close() = http.cancel()
}

internal class BatumiBatBusAllBusesClient : BatumiAllBusesClient {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 8_000
        }
    }

    override suspend fun getAllBuses(): String = try {
        val response = http.get { url(BatumiAllBusesUrl) }
        if (response.status != HttpStatusCode.OK) throw ProviderUnavailable("Batumi global live upstream is unavailable")
        response.readBoundedUtf8(BatumiMaximumBodyBytes)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: HttpRequestTimeoutException) {
        throw ProviderTimeout("Batumi global live data timed out")
    } catch (_: ConnectTimeoutException) {
        throw ProviderTimeout("Batumi global live data timed out")
    } catch (_: SocketTimeoutException) {
        throw ProviderTimeout("Batumi global live data timed out")
    } catch (exception: ProviderFailure) {
        throw exception
    } catch (_: Exception) {
        throw ProviderUnavailable("Batumi global live upstream is unavailable")
    }

    override fun close() = http.cancel()

    private companion object {
        const val BatumiAllBusesUrl = "https://batbus.app/api/getAllBuses"
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.readBoundedUtf8(limit: Int): String {
    val channel = bodyAsChannel(); val buffer = ByteArray(8_192); val output = ByteArrayOutputStream()
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size); if (read <= 0) break
        if (output.size() + read > limit) throw ProviderBadGateway("Batumi catalog response is too large")
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8)
}

internal object BatumiThetaCatalogParser {
    fun parse(payload: String, observedAt: Instant): BatumiCatalog {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > BatumiMaximumBodyBytes) invalid()
        val root = try { BatumiJson.parseToJsonElement(payload).objectValue() } catch (_: Exception) { invalid() }
        val data = (root["data"] as? JsonObject) ?: root
        val rawRoutes = data.collection("routesNames")
        val rawStops = data.collection("busStops")
        if (rawRoutes.isEmpty() || rawRoutes.size > BatumiMaximumRoutes || rawStops.size > BatumiMaximumStops) invalid()
        val routeEntries = rawRoutes.map { (key, element) -> element.objectValue().let { obj ->
            val raw = obj.stringOr("RouteIdGeoGps", "_id", "RouteId", fallback = key ?: "").also { if (!safeString(it)) invalid() }
            RawRoute(raw, obj.stringOr("RouteNameEN", "RouteNameGeoGps", "RouteName", fallback = raw), obj.stringOr("RouteNameKA", "RouteNameGeoGpsKA", fallback = raw), obj.numberOrNull("RouteSortOrder") ?: Int.MAX_VALUE.toDouble())
        } }
        if (routeEntries.map(RawRoute::rawId).toSet().size != routeEntries.size) invalid()
        val routesByRaw = routeEntries.associateBy(RawRoute::rawId)
        val routeIds = routesByRaw.keys.associateWith(BatumiIds::route)
        val directions = routeIds.mapValues { (raw, _) -> BatumiIds.direction(raw) }
        val routes = routeEntries.sortedWith(compareBy<RawRoute> { it.order }.thenBy { it.name }).map { raw ->
            Route(routeIds.getValue(raw.rawId), BatumiIds.opaque(raw.rawId), raw.name, LocalizedText(raw.name, raw.name, raw.ka), "#1479B8", "#FFFFFF", TransitModeValues.Bus, listOf(technicalDirection(raw.rawId)))
        }
        val rawStopEntries = rawStops.map { (key, element) -> element.objectValue().let { obj ->
            val raw = obj.stringOr("BusStopIdGeoGps", "BusStopId", "_id", fallback = key ?: "").also { if (!safeString(it)) invalid() }
            val latitude = obj.number("BusStopLatitude", "Lat", "Latitude", "lat"); val longitude = obj.number("BusStopLongitude", "Lon", "Longitude", "lon")
            if (!inBatumi(latitude, longitude)) invalid()
            val memberships = (obj["routes"] as? JsonObject ?: invalid()).entries.map { (rawRoute, value) ->
                if (rawRoute !in routesByRaw) invalid(); val member = value as? JsonObject ?: invalid()
                val status = member.numberOrNull("Status") ?: 1.0
                val order = member.numberOrNull("Order") ?: 0.0
                if (status % 1.0 != 0.0 || order % 1.0 != 0.0) invalid()
                RawRouteMembership(rawRoute, status.toInt(), order.toInt())
            }
            RawStop(raw, obj.stringOr("BusStopNumber", "StopCode", fallback = raw), obj.stringOr("BusStopNameEN", "BusStopNameGeoGps", "BusStopName", "Name", fallback = raw), obj.stringOr("BusStopNameKA", "BusStopNameGeoGpsKA", fallback = obj.stringOr("BusStopNameEN", "BusStopNameGeoGps", "BusStopName", "Name", fallback = raw)), GeoPoint(latitude, longitude), memberships)
        } }
        if (rawStopEntries.map(RawStop::rawId).toSet().size != rawStopEntries.size) invalid()
        val stopsByRawId = rawStopEntries.associate { raw ->
            raw.rawId to Stop(
                BatumiIds.stop(raw.rawId),
                BatumiIds.opaque(raw.rawId),
                raw.code,
                LocalizedText(raw.name, raw.name, raw.ka),
                raw.position,
                raw.memberships.map { routeIds.getValue(it.rawRouteId) }.sorted(),
                TransitModeValues.Bus,
            )
        }
        val stops = stopsByRawId.values.sortedBy(Stop::id)
        val stopOrderByRoute = routeEntries.associate { route ->
            routeIds.getValue(route.rawId) to rawStopEntries
                .filter { raw -> raw.memberships.any { it.rawRouteId == route.rawId } }
                .sortedWith(compareBy<RawStop> { raw -> raw.memberships.single { it.rawRouteId == route.rawId }.status }
                    .thenBy { raw -> raw.memberships.single { it.rawRouteId == route.rawId }.order })
                .map { raw -> stopsByRawId.getValue(raw.rawId) }
        }
        val cycles = routeEntries.associate { route ->
            val routeId = routeIds.getValue(route.rawId)
            val members = rawStopEntries
                .mapNotNull { raw -> raw.memberships.singleOrNull { it.rawRouteId == route.rawId }?.let { membership -> raw to membership } }
                .sortedWith(compareBy<Pair<RawStop, RawRouteMembership>> { it.second.status }.thenBy { it.second.order })
            routeId to buildRouteCycle(members.map { (raw, membership) ->
                RouteStop(stopsByRawId.getValue(raw.rawId), membership.status, membership.order)
            })
        }
        val coords = data["routeCoordinatesGrouped"] as? JsonObject ?: invalid()
        val geometries = routeEntries.associate { route ->
            val points = (coords[route.rawId] as? JsonArray ?: invalid()).map(::point).also { if (it.size < 2) invalid() }
            routeIds.getValue(route.rawId) to points
        }
        val shapes = geometries.mapValues { (_, points) -> Shape(precision = 5, value = encodePolyline(points), updatedAt = observedAt.toString()) }
        return BatumiCatalog(
            routes = routes,
            stops = stops,
            stopOrderByRoute = stopOrderByRoute,
            shapes = shapes,
            geometries = geometries,
            rawRouteIds = routeIds.entries.associate { (raw, normalized) -> normalized to raw },
            cycles = cycles,
        )
    }

    private data class RawRoute(val rawId: String, val name: String, val ka: String, val order: Double)
    private data class RawStop(val rawId: String, val code: String, val name: String, val ka: String, val position: GeoPoint, val memberships: List<RawRouteMembership>)
    private data class RawRouteMembership(val rawRouteId: String, val status: Int, val order: Int)
    private fun technicalDirection(rawRoute: String) = Direction(BatumiIds.direction(rawRoute), LocalizedText("Техническое направление", "Technical direction", "ტექნიკური მიმართულება"), LocalizedText("Техническое направление", "Technical direction", "ტექნიკური მიმართულება"))
    /** Theta currently sends keyed objects; arrays are a documented legacy representation. */
    private fun JsonObject.collection(name: String): List<Pair<String?, JsonElement>> = when (val value = this[name]) {
        is JsonArray -> value.map { null to it }
        is JsonObject -> value.entries.map { (key, element) -> key to element }
        else -> invalid()
    }
    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: invalid()
    private fun JsonObject.string(vararg names: String): String = names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.content?.takeIf(::safeString) } ?: invalid()
    private fun JsonObject.stringOr(vararg names: String, fallback: String): String = names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.content?.takeIf(::safeString) } ?: fallback
    private fun JsonObject.number(vararg names: String): Double = numberOrNull(*names) ?: invalid()
    private fun JsonObject.numberOrNull(vararg names: String): Double? = names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf(Double::isFinite) }
    private fun point(element: JsonElement): GeoPoint = when (element) {
        is JsonObject -> GeoPoint(element.number("lat", "Lat", "latitude"), element.number("lon", "Lon", "longitude"))
        is JsonArray -> if (element.size == 2) GeoPoint(element[0].numberValue(), element[1].numberValue()) else invalid()
        else -> invalid()
    }.also { if (!inBatumi(it.latitude, it.longitude)) invalid() }
    private fun JsonElement.numberValue(): Double = (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: invalid()
    private fun safeString(value: String): Boolean = value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= BatumiMaximumStringBytes
    private fun inBatumi(lat: Double, lon: Double): Boolean = lat.isFinite() && lon.isFinite() && lat in 41.45..41.85 && lon in 41.40..41.95
    private fun invalid(): Nothing = throw ProviderNormalizedSchemaFailure("Batumi catalog schema validation failed")
}

private object BatumiIds {
    fun opaque(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    fun route(raw: String) = formatPublicId(BatumiCityId, BatumiProviderId, PublicEntityType.Route, opaque(raw))
    fun stop(raw: String) = formatPublicId(BatumiCityId, BatumiProviderId, PublicEntityType.Stop, opaque(raw))
    /** The suffix denotes only upstream technical ordering/status, never a passenger headsign. */
    fun direction(raw: String) =
        formatPublicId(BatumiCityId, BatumiProviderId, PublicEntityType.Direction, "${opaque(raw)}-technical")
    /** Upstream Name is scoped to this response route and is never a passenger-visible fleet ID. */
    fun vehicle(routeId: String, rawName: String) = formatPublicId(
        BatumiCityId,
        BatumiProviderId,
        PublicEntityType.Vehicle,
        "${routeId.substringAfterLast(':')}-${token(rawName)}",
    )
    private fun token(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)),
    )
}

/** Defensive parser for getBusLocsOnRoute. `data:null` is a valid empty snapshot. */
internal object BatumiThetaLiveParser {
    fun parse(payload: String, route: Route, observedAt: Instant): List<BatumiLiveVehicle> {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > BatumiMaximumBodyBytes) invalid()
        val root = try { BatumiJson.parseToJsonElement(payload) } catch (_: Exception) { invalid() }
        val data = when (root) {
            is JsonObject -> root["data"] ?: invalid()
            else -> invalid()
        }
        val records = when (data) {
            is JsonArray -> data.toList()
            is JsonObject -> when {
                data.containsKey("Lat") || data.containsKey("lat") -> listOf(data)
                data["items"] is JsonArray -> (data["items"] as JsonArray).toList()
                data["vehicles"] is JsonArray -> (data["vehicles"] as JsonArray).toList()
                else -> data.values.toList()
            }
            kotlinx.serialization.json.JsonNull -> emptyList()
            else -> invalid()
        }
        if (records.size > BatumiMaximumVehicles) invalid()
        val names = mutableSetOf<String>()
        return records.map { element ->
            val item = element as? JsonObject ?: invalid()
            val name = item.string("Name")
            if (!names.add(name)) invalid()
            val latitude = item.number("Lat", "lat", "Latitude", "latitude")
            val longitude = item.number("Lon", "lon", "Longitude", "longitude")
            if (!inBatumi(latitude, longitude)) invalid()
            val status = item["Status"]?.let { value ->
                val primitive = value as? JsonPrimitive ?: invalid()
                primitive.content.toIntOrNull() ?: invalid()
            }
            BatumiLiveVehicle(
                vehicle = Vehicle(
                    id = BatumiIds.vehicle(route.id, name),
                    routeId = route.id,
                    position = GeoPoint(latitude, longitude),
                    observedAt = observedAt.toString(),
                    positionKind = PositionKind.GPS,
                ),
                status = status,
            )
        }
    }

    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.content
        ?.takeIf { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size <= BatumiMaximumStringBytes } ?: invalid()
    private fun JsonObject.number(vararg names: String): Double = names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf(Double::isFinite)
    } ?: invalid()
    private fun inBatumi(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in 41.45..41.85 && lon in 41.40..41.95
    private fun invalid(): Nothing = throw ProviderNormalizedSchemaFailure("Batumi live schema validation failed")
}

/** Defensive parser for the bulk BatBus live envelope; unknown route keys are ignored. */
internal object BatumiAllBusesParser {
    fun parse(payload: String, catalog: BatumiCatalog, observedAt: Instant): Map<String, List<BatumiLiveVehicle>> {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > BatumiMaximumBodyBytes) invalid()
        val root = try {
            BatumiJson.parseToJsonElement(payload) as? JsonObject ?: invalid()
        } catch (_: Exception) {
            invalid()
        }
        val data = root["data"] as? JsonObject ?: invalid()
        if (data.size > BatumiMaximumRoutes) invalid()
        val normalizedByRaw = catalog.rawRouteIds.entries.associate { (normalized, raw) -> raw to normalized }
        var totalVehicles = 0
        val result = mutableMapOf<String, List<BatumiLiveVehicle>>()
        data.forEach { (rawRouteId, records) ->
            val normalizedRouteId = normalizedByRaw[rawRouteId] ?: return@forEach
            val route = catalog.route(normalizedRouteId)
            val vehicles = BatumiThetaLiveParser.parse(
                payload = JsonObject(mapOf("data" to records)).toString(),
                route = route,
                observedAt = observedAt,
            )
            totalVehicles += vehicles.size
            if (totalVehicles > BatumiMaximumGlobalVehicles) invalid()
            result[normalizedRouteId] = vehicles
        }
        return catalog.routes.associate { route -> route.id to result[route.id].orEmpty() }
    }

    private fun invalid(): Nothing = throw ProviderNormalizedSchemaFailure("Batumi global live schema validation failed")
}

internal data class RouteStop(val stop: Stop, val status: Int, val order: Int)
internal data class RoutePoint(
    val stop: Stop,
    val status: Int,
    val order: Int,
    val cumulativeMeters: Double,
)
internal data class RouteCycle(val chain: List<RoutePoint>, val totalMeters: Double) {
    private val byStopId = chain.associateBy { it.stop.id }
    private val cumulativeMeters = chain.map(RoutePoint::cumulativeMeters)
    val statuses: Set<Int> = chain.mapTo(mutableSetOf(), RoutePoint::status)
    fun point(stopId: String): RoutePoint = byStopId[stopId]
        ?: throw ProviderStopNotFound("The requested stop was not found on the route")

    /** Counts strict intermediate stops in O(log S), including a possible cycle wrap. */
    fun stopsBefore(from: Double, targetDistance: Double): Int {
        if (targetDistance <= 2.0) return 0
        val lower = from + 1.0
        val upper = from + targetDistance - 1.0
        return countOpenInterval(lower, upper) + countOpenInterval(lower - totalMeters, upper - totalMeters)
    }

    private fun countOpenInterval(lower: Double, upper: Double): Int {
        if (upper <= 0.0 || lower >= totalMeters || upper <= lower) return 0
        val start = if (lower < 0.0) 0 else upperBound(lower)
        val end = if (upper >= totalMeters) cumulativeMeters.size else lowerBound(upper)
        return (end - start).coerceAtLeast(0)
    }

    private fun lowerBound(value: Double): Int {
        var low = 0
        var high = cumulativeMeters.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulativeMeters[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBound(value: Double): Int {
        var low = 0
        var high = cumulativeMeters.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulativeMeters[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }
}

private fun buildRouteCycle(stops: List<RouteStop>): RouteCycle {
    if (stops.size < 2) throw ProviderNormalizedSchemaFailure("Batumi route stop chain is incomplete")
    var cumulative = 0.0
    val chain = stops.mapIndexed { index, routeStop ->
        if (index > 0) cumulative += haversineMeters(stops[index - 1].stop.position, routeStop.stop.position)
        RoutePoint(routeStop.stop, routeStop.status, routeStop.order, cumulative)
    }
    val total = cumulative + haversineMeters(stops.last().stop.position, stops.first().stop.position)
    if (!total.isFinite() || total <= 0.0) throw ProviderNormalizedSchemaFailure("Batumi route stop chain is invalid")
    return RouteCycle(chain, total)
}

private const val EtaMaxProjectionMeters = 150.0
private const val BatumiMaximumGlobalVehicles = 4_096
private const val BatumiMaximumEtaWorkItems = 100_000L
private val GlobalFeedTelemetryLabels = ProviderTelemetryLabels(
    city = BatumiCityId,
    provider = TelemetryProvider.BATUMI_THETA,
    capability = TelemetryCapability.VEHICLE_POSITIONS,
    operation = TelemetryOperation.GLOBAL_VEHICLES,
)
private const val EtaPassedStopToleranceMeters = 150.0
private const val EtaDwellSeconds = 60.0
/** Passenger-board horizon: farther candidates are deliberately omitted rather than guessed. */
private const val EtaMaximumSeconds = 45 * 60L
private const val MaximumConsecutiveCandidateMisses = 2
/** Current generation plus the one-generation continuity bridge, both workload-bounded. */
private const val MaximumArrivalCandidates = 200_000
private val ArrivalCandidateRetention: Duration = Duration.ofMinutes(1)

private data class PolylineProjection(val alongMeters: Double, val distanceMeters: Double)
private val BatumiMovingKmhByRawRoute = mapOf(
    "60acde9ffcc7a224160c587c" to 23.7,
    "648994879327c728200b3f52" to 22.7,
    "5ed2bd4f657784b5a98a8c7e" to 24.2,
    "5ed6077c340f60873ff9e1be" to 25.1,
    "5ed60865340f60873ff9e1bf" to 23.0,
    "67ae3607e37f0ecf8032068f" to 21.1,
    "5f9f0704eb714303efce5631" to 32.2,
    "6740347dd04d3a04c35d84e4" to 23.6,
    "67402e01d04d3a04c35d84c2" to 23.0,
    "5ed60aa5340f60873ff9e1c4" to 26.2,
    "5ed60b25340f60873ff9e1c5" to 26.5,
    "64901c96e25b40c6e2150b34" to 29.6,
    "5ed60be3340f60873ff9e1c6" to 31.8,
    "6941c100f141b1861c3958a0" to 22.5,
    "5ed60d70340f60873ff9e1c8" to 26.5,
    "648996c69327c728200b3f63" to 23.8,
    "615182e95dc186cad86e0fd2" to 21.5,
    "5ed60e97340f60873ff9e1cb" to 24.4,
    "5ed60f32340f60873ff9e1cc" to 28.6,
    "675bf5b960b00f8db1965a52" to 23.8,
    "66de91d980df11324f155751" to 33.5,
    "69429301f141b1861c395bec" to 26.9,
    "67d92ac719ba93b977b83df4" to 30.5,
    "67d9522119ba93b977b83e6f" to 27.5,
    "6724af35cabac5486baeb625" to 33.7,
)
private const val BatumiFallbackMovingKmh = 25.1

/** Reuses only a still-future, previously accepted ETA during the one-snapshot continuity bridge. */
private fun Arrival.retimedFor(observedAt: Instant): Arrival? {
    val expected = expectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val seconds = Duration.between(observedAt, expected).seconds
    if (seconds < 0 || seconds > EtaMaximumSeconds) return null
    return copy(expectedInMinutes = ceil(seconds / 60.0).toInt())
}

private fun project(points: List<GeoPoint>, target: GeoPoint): PolylineProjection? {
    if (points.size < 2) return null
    val latScale = 111_320.0
    val lonScale = 111_320.0 * cos(target.latitude * PI / 180.0)
    var total = 0.0
    var best: PolylineProjection? = null
    points.zipWithNext().forEach { (a, b) ->
        val ax = (a.longitude - target.longitude) * lonScale; val ay = (a.latitude - target.latitude) * latScale
        val bx = (b.longitude - target.longitude) * lonScale; val by = (b.latitude - target.latitude) * latScale
        val dx = bx - ax; val dy = by - ay; val segment = hypot(dx, dy)
        if (segment > 0.01) {
            val t = ((-ax * dx - ay * dy) / (segment * segment)).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            val candidate = PolylineProjection(total + t * segment, distance)
            if (best?.distanceMeters == null || candidate.distanceMeters < best.distanceMeters) best = candidate
            total += segment
        }
    }
    return best
}

private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = (b.latitude - a.latitude) * PI / 180.0; val lon = (b.longitude - a.longitude) * PI / 180.0
    val sinLat = kotlin.math.sin(lat / 2); val sinLon = kotlin.math.sin(lon / 2)
    val h = sinLat * sinLat + cos(a.latitude * PI / 180.0) * cos(b.latitude * PI / 180.0) * sinLon * sinLon
    return 12_742_000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

private fun projectVehicle(
    catalog: BatumiCatalog,
    routeId: String,
    cycle: RouteCycle,
    live: BatumiLiveVehicle,
): PolylineProjection? {
    if (project(catalog.geometry(routeId), live.vehicle.position)?.distanceMeters?.let { it > EtaMaxProjectionMeters } != false) {
        return null
    }
    val status = live.status ?: cycle.statuses.singleOrNull() ?: return null
    if (status < 0) return null
    var best: PolylineProjection? = null
    cycle.chain.indices.forEach { index ->
        val start = cycle.chain[index]
        val end = cycle.chain[(index + 1) % cycle.chain.size]
        if (start.status != status || end.status != status) return@forEach
        val candidate = projectSegment(
            start.stop.position,
            end.stop.position,
            live.vehicle.position,
            start.cumulativeMeters,
        )
        if (best == null || candidate.distanceMeters < requireNotNull(best).distanceMeters) best = candidate
    }
    return best?.takeIf { it.distanceMeters <= EtaMaxProjectionMeters }
}

private fun projectSegment(
    start: GeoPoint,
    end: GeoPoint,
    target: GeoPoint,
    cumulativeMeters: Double,
): PolylineProjection {
    val latScale = 110_540.0
    val lonScale = 111_320.0 * cos(target.latitude * PI / 180.0)
    val ax = (start.longitude - target.longitude) * lonScale
    val ay = (start.latitude - target.latitude) * latScale
    val bx = (end.longitude - target.longitude) * lonScale
    val by = (end.latitude - target.latitude) * latScale
    val dx = bx - ax
    val dy = by - ay
    val segment = hypot(dx, dy)
    if (segment <= 0.01) return PolylineProjection(cumulativeMeters, hypot(ax, ay))
    val ratio = ((-ax * dx - ay * dy) / (segment * segment)).coerceIn(0.0, 1.0)
    return PolylineProjection(
        cumulativeMeters + ratio * haversineMeters(start, end),
        hypot(ax + ratio * dx, ay + ratio * dy),
    )
}

private fun forwardCycleDistance(from: Double, to: Double, total: Double): Double {
    val direct = to - from
    return when {
        direct < -EtaPassedStopToleranceMeters -> direct + total
        direct < 0.0 -> 0.0
        else -> direct
    }.coerceIn(0.0, total)
}

private fun etaMinutes(distance: Double, metersPerSecond: Double, dwellStops: Int): Int? {
    val seconds = distance / metersPerSecond + dwellStops * EtaDwellSeconds
    if (!seconds.isFinite() || seconds > EtaMaximumSeconds) return null
    return kotlin.math.round(seconds / 60.0).toInt().coerceAtLeast(1)
}

internal fun validateEtaWorkload(routeSizes: Iterable<Pair<Int, Int>>) {
    var workItems = 0L
    routeSizes.forEach { (vehicles, stops) ->
        if (vehicles < 0 || stops < 0) throw ProviderNormalizedSchemaFailure("Batumi ETA workload is invalid")
        workItems += vehicles.toLong() * stops.toLong()
        if (workItems > BatumiMaximumEtaWorkItems) {
            throw ProviderNormalizedSchemaFailure("Batumi ETA workload exceeds the safe limit")
        }
    }
}

private fun encodePolyline(points: List<GeoPoint>): String {
    var lastLat = 0; var lastLon = 0
    return buildString {
        points.forEach { point ->
            val lat = kotlin.math.round(point.latitude * 1e5).toInt(); val lon = kotlin.math.round(point.longitude * 1e5).toInt()
            appendPolylineValue(lat - lastLat); appendPolylineValue(lon - lastLon); lastLat = lat; lastLon = lon
        }
    }
}
private fun StringBuilder.appendPolylineValue(value: Int) { var encoded = value shl 1; if (value < 0) encoded = encoded.inv(); while (encoded >= 0x20) { append(((0x20 or (encoded and 0x1f)) + 63).toChar()); encoded = encoded shr 5 }; append((encoded + 63).toChar()) }
