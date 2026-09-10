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
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.api.WalkingEstimate
import com.denis.georgiatransit.bff.config.BatumiThetaActivationConfig
import com.denis.georgiatransit.bff.observability.TelemetryProvider
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

private const val BatumiCityId = "batumi"
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
    private val liveSnapshots = mutableMapOf<String, TimedLiveSnapshot>()
    private val vehicleSamples = mutableMapOf<String, ArrayDeque<VehicleSample>>()

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
        if (mode == null || mode == "bus") catalog().routes else emptyList()

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
        val live = liveSnapshot(snapshot, routeId)
        return RealtimeVehicles(
            items = live.vehicles,
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
        val estimates = mutableListOf<Arrival>()
        var newest = Instant.EPOCH
        var stale = false
        for (routeId in stop.routeIds) {
            val live = liveSnapshot(snapshot, routeId)
            newest = maxOf(newest, live.observedAt)
            stale = stale || live.stale
            // A stale location remains useful on the map, but never produces a passenger ETA.
            if (!live.stale) estimates += estimateArrivals(snapshot, routeId, stop, live)
        }
        return RealtimeArrivals(
            items = estimates.sortedBy { it.expectedAt }.take(limit.coerceIn(1, 20)),
            source = ArrivalSource.CLIENT_ESTIMATE,
            observedAt = newest,
            stale = stale,
        )
    }
    override suspend fun journeys(query: JourneyQuery): List<Journey> = unavailable()
    override suspend fun walkingEstimate(query: WalkingQuery): WalkingEstimate = unavailable()
    override fun close() = client.close()

    private fun <T> unavailable(): T = throw ProviderCapabilityUnavailable("This capability is not available for Batumi")

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
            val tracked = updateSamples(routeId, parsed, catalog.geometry(routeId), observedAt)
            LiveSnapshot(tracked, observedAt, stale = false).also { liveSnapshots[routeId] = TimedLiveSnapshot(it) }
        } catch (failure: ProviderFailure) {
            val lkg = cached?.snapshot
            if (lkg != null && Duration.between(lkg.observedAt, now) <= BatumiLiveMaximumAge) {
                lkg.copy(stale = true)
            } else {
                throw failure
            }
        }
    }

    /** Retain only BFF-normalized points and short sample history, never the raw response. */
    private fun updateSamples(routeId: String, vehicles: List<Vehicle>, geometry: List<GeoPoint>, observedAt: Instant): List<Vehicle> {
        val retainedAfter = observedAt.minusSeconds(BatumiLiveMaximumAge.seconds)
        val seen = vehicles.map(Vehicle::id).toSet()
        vehicleSamples.entries.removeIf { (id, samples) ->
            while (samples.firstOrNull()?.observedAt?.isBefore(retainedAfter) == true) samples.removeFirst()
            samples.isEmpty() && id !in seen
        }
        return vehicles.map { vehicle ->
            val projection = project(geometry, vehicle.position) ?: return@map vehicle
            val samples = vehicleSamples.getOrPut(vehicle.id) { ArrayDeque() }
            samples.addLast(VehicleSample(vehicle.position, projection.alongMeters, observedAt))
            while (samples.size > 5 || samples.first().observedAt.isBefore(retainedAfter)) samples.removeFirst()
            vehicle
        }
    }

    private fun estimateArrivals(catalog: BatumiCatalog, routeId: String, stop: Stop, live: LiveSnapshot): List<Arrival> {
        val geometry = catalog.geometry(routeId)
        val route = catalog.route(routeId)
        val target = project(geometry, stop.position) ?: return emptyList()
        if (target.distanceMeters > EtaMaxProjectionMeters) return emptyList()
        val stopProjections = catalog.stopsFor(routeId).mapNotNull { candidate ->
            project(geometry, candidate.position)?.takeIf { it.distanceMeters <= EtaMaxProjectionMeters }?.let { candidate to it.alongMeters }
        }
        val loop = isLoop(geometry)
        val length = polylineLength(geometry)
        return live.vehicles.mapNotNull { vehicle ->
            val bus = project(geometry, vehicle.position) ?: return@mapNotNull null
            if (bus.distanceMeters > EtaMaxProjectionMeters) return@mapNotNull null
            val motion = directionAndSpeed(vehicle.id, length, loop) ?: return@mapNotNull null
            val distance = forwardDistance(bus.alongMeters, target.alongMeters, length, motion.direction, loop) ?: return@mapNotNull null
            val dwellStops = stopsBefore(stopProjections, bus.alongMeters, target.alongMeters, length, motion.direction, loop)
            val seconds = (distance / motion.metersPerSecond + dwellStops * EtaDwellSeconds).toLong()
            if (seconds !in 0..EtaMaximumSeconds) return@mapNotNull null
            val expected = live.observedAt.plusSeconds(seconds)
            Arrival(
                stopId = stop.id,
                routeId = routeId,
                // Theta has no passenger headsign. The route name is safe neutral context only.
                headsign = route.longName,
                expectedAt = expected.toString(),
                expectedInMinutes = ceil(seconds / 60.0).toInt(),
                realtime = false,
                cancelled = false,
                source = ArrivalSource.CLIENT_ESTIMATE,
            )
        }
    }

    /**
     * ETA needs three fresh samples with two agreeing directions. A projection jump that implies
     * a teleport, a reverse movement, a zero/slow speed, or ambiguous loop crossing produces no
     * ETA. This is deliberately stricter than the map marker policy.
     */
    private fun directionAndSpeed(vehicleId: String, length: Double, loop: Boolean): Motion? {
        val samples = vehicleSamples[vehicleId]?.toList()?.takeLast(3) ?: return null
        if (samples.size < 3 || length <= EtaMinimumMovementMeters) return null
        val segments = samples.zipWithNext().mapNotNull { (previous, current) ->
            val elapsed = Duration.between(previous.observedAt, current.observedAt).seconds
            if (elapsed !in 2..45) return null
            val directDistance = haversineMeters(previous.position, current.position)
            if (directDistance > min(750.0, elapsed * 30.0 + 80.0)) return null
            var signed = current.alongMeters - previous.alongMeters
            if (loop && kotlin.math.abs(signed) > length / 2) signed -= kotlin.math.sign(signed) * length
            if (kotlin.math.abs(signed) < EtaMinimumMovementMeters) return null
            val speed = kotlin.math.abs(signed) / elapsed
            if (speed !in EtaMinimumSpeedMetersPerSecond..EtaMaximumSpeedMetersPerSecond) return null
            kotlin.math.sign(signed).toInt() to speed
        }
        if (segments.size != 2 || segments[0].first == 0 || segments[0].first != segments[1].first) return null
        return Motion(segments[0].first, segments.map { it.second }.average())
    }
}

private data class TimedCatalog(val catalog: BatumiCatalog, val savedAt: Instant)
private data class TimedLiveSnapshot(val snapshot: LiveSnapshot)
private data class LiveSnapshot(val vehicles: List<Vehicle>, val observedAt: Instant, val stale: Boolean)
private data class VehicleSample(val position: GeoPoint, val alongMeters: Double, val observedAt: Instant)
internal data class BatumiCatalog(
    val routes: List<Route>, val stops: List<Stop>, val stopOrderByRoute: Map<String, List<Stop>>, val shapes: Map<String, Shape>,
    val geometries: Map<String, List<GeoPoint>>, val rawRouteIds: Map<String, String>,
) {
    fun stopsFor(routeId: String): List<Stop> = stopOrderByRoute[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun requireDirection(routeId: String, directionId: String) {
        if (routes.none { it.id == routeId && it.directions.single().id == directionId }) throw ProviderRouteNotFound("The requested route or direction was not found")
    }
    fun route(routeId: String): Route = routes.singleOrNull { it.id == routeId } ?: throw ProviderRouteNotFound("The requested route was not found")
    fun rawRouteId(routeId: String): String = rawRouteIds[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
    fun geometry(routeId: String): List<GeoPoint> = geometries[routeId] ?: throw ProviderRouteNotFound("The requested route was not found")
}

/** Server-only upstream client. It never exposes Theta payloads beyond this provider boundary. */
internal interface BatumiThetaCatalogClient : AutoCloseable {
    suspend fun getDbData(): String
    suspend fun getBusLocsOnRoute(rawRouteId: String): String
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
            Route(routeIds.getValue(raw.rawId), BatumiIds.opaque(raw.rawId), raw.name, LocalizedText(raw.name, raw.name, raw.ka), "#1479B8", "#FFFFFF", "bus", listOf(technicalDirection(raw.rawId)))
        }
        val rawStopEntries = rawStops.map { (key, element) -> element.objectValue().let { obj ->
            val raw = obj.stringOr("BusStopIdGeoGps", "BusStopId", "_id", fallback = key ?: "").also { if (!safeString(it)) invalid() }
            val latitude = obj.number("BusStopLatitude", "Lat", "Latitude", "lat"); val longitude = obj.number("BusStopLongitude", "Lon", "Longitude", "lon")
            if (!inBatumi(latitude, longitude)) invalid()
            val memberships = (obj["routes"] as? JsonObject ?: invalid()).entries.map { (rawRoute, value) ->
                if (rawRoute !in routesByRaw) invalid(); val member = value as? JsonObject ?: invalid()
                rawRoute to (member.numberOrNull("Order") ?: 0.0)
            }
            RawStop(raw, obj.stringOr("BusStopNumber", "StopCode", fallback = raw), obj.stringOr("BusStopNameEN", "BusStopNameGeoGps", "BusStopName", "Name", fallback = raw), obj.stringOr("BusStopNameKA", "BusStopNameGeoGpsKA", fallback = obj.stringOr("BusStopNameEN", "BusStopNameGeoGps", "BusStopName", "Name", fallback = raw)), GeoPoint(latitude, longitude), memberships)
        } }
        if (rawStopEntries.map(RawStop::rawId).toSet().size != rawStopEntries.size) invalid()
        val stops = rawStopEntries.map { raw -> Stop(BatumiIds.stop(raw.rawId), BatumiIds.opaque(raw.rawId), raw.code, LocalizedText(raw.name, raw.name, raw.ka), raw.position, raw.memberships.map { routeIds.getValue(it.first) }.sorted(), "bus") }.sortedBy(Stop::id)
        val byStopRaw = rawStopEntries.associateBy(RawStop::rawId)
        val stopOrderByRoute = routeEntries.associate { route ->
            routeIds.getValue(route.rawId) to rawStopEntries.filter { raw -> raw.memberships.any { it.first == route.rawId } }.sortedBy { raw -> raw.memberships.single { it.first == route.rawId }.second }.map { raw -> stops.single { it.id == BatumiIds.stop(raw.rawId) } }
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
        )
    }

    private data class RawRoute(val rawId: String, val name: String, val ka: String, val order: Double)
    private data class RawStop(val rawId: String, val code: String, val name: String, val ka: String, val position: GeoPoint, val memberships: List<Pair<String, Double>>)
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
    fun route(raw: String) = "batumi:theta:route:${opaque(raw)}"
    fun stop(raw: String) = "batumi:theta:stop:${opaque(raw)}"
    /** The suffix denotes only upstream technical ordering/status, never a passenger headsign. */
    fun direction(raw: String) = "batumi:theta:direction:${opaque(raw)}-technical"
    /** Upstream Name is scoped to this response route and is never a passenger-visible fleet ID. */
    fun vehicle(routeId: String, rawName: String) = "batumi:theta:vehicle:${routeId.substringAfterLast(':')}-${token(rawName)}"
    private fun token(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)),
    )
}

/** Defensive parser for getBusLocsOnRoute. `data:null` is a valid empty snapshot. */
internal object BatumiThetaLiveParser {
    fun parse(payload: String, route: Route, observedAt: Instant): List<Vehicle> {
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
            // Status is intentionally not exposed as a headsign/direction: its semantics are not
            // documented. It is only accepted as bounded opaque structural evidence.
            item["Status"]?.let { status -> if (status !is JsonPrimitive || status.content.length > BatumiMaximumStringBytes) invalid() }
            Vehicle(
                id = BatumiIds.vehicle(route.id, name),
                routeId = route.id,
                position = GeoPoint(latitude, longitude),
                observedAt = observedAt.toString(),
                positionKind = PositionKind.GPS,
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

private const val EtaMaxProjectionMeters = 120.0
private const val EtaMinimumMovementMeters = 15.0
private const val EtaMinimumSpeedMetersPerSecond = 1.0
private const val EtaMaximumSpeedMetersPerSecond = 16.0
private const val EtaDwellSeconds = 20.0
private const val EtaMaximumSeconds = 90 * 60L

private data class PolylineProjection(val alongMeters: Double, val distanceMeters: Double)
private data class Motion(val direction: Int, val metersPerSecond: Double)

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

private fun polylineLength(points: List<GeoPoint>): Double = points.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }
private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = (b.latitude - a.latitude) * PI / 180.0; val lon = (b.longitude - a.longitude) * PI / 180.0
    val sinLat = kotlin.math.sin(lat / 2); val sinLon = kotlin.math.sin(lon / 2)
    val h = sinLat * sinLat + cos(a.latitude * PI / 180.0) * cos(b.latitude * PI / 180.0) * sinLon * sinLon
    return 12_742_000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}
private fun isLoop(points: List<GeoPoint>) = points.size >= 3 && haversineMeters(points.first(), points.last()) <= EtaMaxProjectionMeters
private fun forwardDistance(from: Double, to: Double, length: Double, direction: Int, loop: Boolean): Double? {
    var distance = if (direction > 0) to - from else from - to
    if (distance < EtaMinimumMovementMeters && loop) distance += length
    return distance.takeIf { it >= EtaMinimumMovementMeters && it <= length + EtaMinimumMovementMeters }
}
private fun stopsBefore(
    stops: List<Pair<Stop, Double>>, from: Double, to: Double, length: Double, direction: Int, loop: Boolean,
): Int = stops.count { (_, at) ->
    val distance = forwardDistance(from, at, length, direction, loop) ?: return@count false
    val targetDistance = forwardDistance(from, to, length, direction, loop) ?: return@count false
    distance < targetDistance - EtaMinimumMovementMeters
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
