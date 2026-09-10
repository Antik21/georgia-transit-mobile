package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.api.WalkingEstimate
import com.denis.georgiatransit.bff.config.TtcActivationConfig
import com.denis.georgiatransit.bff.geo.haversineMeters
import com.denis.georgiatransit.bff.observability.NoopProviderCallObservability
import com.denis.georgiatransit.bff.observability.ProviderCallObservability
import com.denis.georgiatransit.bff.observability.ProviderEvent
import com.denis.georgiatransit.bff.observability.ProviderTelemetryLabels
import com.denis.georgiatransit.bff.observability.TelemetryCapability
import com.denis.georgiatransit.bff.observability.TelemetryOperation
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TtcCityId = "tbilisi"
private const val TtcProviderId = "ttc"
private const val TtcMaximumResponseBytes = 256 * 1024
private const val TtcMaximumRoutes = 512
private const val TtcMaximumStops = 2_048
private const val TtcMaximumPositions = 512
private const val TtcMaximumArrivals = 40
private const val TtcMaximumItineraries = 4
private const val TtcMaximumLegs = 24
private const val TtcMaximumRetryAfterSeconds = 2
private const val TtcMaximumPublishedRetryAfterSeconds = 86_400
private const val TtcMaximumPlanClockSkewSeconds = 5 * 60L
private const val TtcPolylinePrecision = 5
private const val TtcRateWindowMillis = 60_000L
private const val TtcSafeRouteColor = "#0057B8"
private const val TtcSafeTextColor = "#FFFFFF"
private const val TtcVehicleTrackerTtlSeconds = 180L
private const val TtcVehicleTrackerMaximumEntries = 512
private const val TtcVehicleTrackerMaximumEntriesPerScope = 64
private const val TtcVehicleMaximumMetersPerSecond = 35.0
private const val TtcVehicleMatchBaseMeters = 60.0
private const val TtcVehicleMaximumMatchMeters = 1_000.0
private const val TtcVehicleAmbiguousMeters = 8.0

private val TtcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * A TTC v2 adapter. TTC's DTOs and credential are local to this source file and never leave the
 * BFF provider boundary. The TTC API does not supply vehicle IDs or position timestamps, so this
 * adapter creates neither persistent vehicle identities nor invented observation ages.
 */
internal class TtcTransitProviderAdapter(
    private val activation: TtcActivationConfig,
    private val client: TtcClient = TtcClient(activation),
    private val clock: Clock = Clock.systemUTC(),
) : CityTransitProviderAdapter, SyntheticProbeProvider, AutoCloseable {
    init {
        require(activation.isActivated) { "TTC adapter construction requires explicit activation" }
    }

    override val telemetryProvider: TelemetryProvider = TelemetryProvider.TTC

    override val city: City = City(
        id = TtcCityId,
        name = LocalizedText(ru = "Тбилиси", en = "Tbilisi", ka = "თბილისი"),
        center = GeoPoint(latitude = 41.715_137, longitude = 44.827_096),
        defaultZoom = 12.5,
        capabilities = CityCapabilities(
            routes = true,
            stops = true,
            routeGeometry = true,
            vehiclePositions = true,
            officialArrivals = true,
            tripPlanning = true,
            arrivals = true,
        ),
        availability = CityAvailability(
            readiness = CityReadiness.PRODUCTION_READY,
            source = CitySource.REVIEWED_ADAPTER,
        ),
    )

    override val probeTargets: List<SyntheticProbeTarget> = activation.probeStopId?.let { rawStopId ->
        listOf(
            SyntheticProbeTarget(
                cityId = TtcCityId,
                provider = telemetryProvider,
                capability = TelemetryCapability.ARRIVALS,
                operation = TelemetryOperation.PROBE_ARRIVALS,
                expectedRealtime = activation.probeRealtimeExpected,
                privateTarget = rawStopId,
            ),
        )
    } ?: emptyList()

    private val vehicleTracker = TtcVehicleTracker(clock)

    override suspend fun routes(locale: String, mode: String?): List<Route> {
        if (mode != null && mode != "bus") return emptyList()
        return client.routes(ttcLocale(locale), TelemetryOperation.LIST_ROUTES)
            .map { route -> route.toNormalizedRoute(locale) }
            .sortedWith(compareBy(Route::shortName).thenBy(Route::id))
    }

    override suspend fun route(routeId: String, locale: String): Route {
        val rawRouteId = TtcIds.decode(routeId, "route") { ProviderRouteNotFound("The requested route was not found") }
        return client.routes(ttcLocale(locale), TelemetryOperation.ROUTE)
            .singleOrNull { it.rawId == rawRouteId }
            ?.toNormalizedRoute(locale)
            ?: throw ProviderRouteNotFound("The requested route was not found")
    }

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> {
        val rawRouteId = TtcIds.decode(routeId, "route") { ProviderRouteNotFound("The requested route was not found") }
        val forward = TtcIds.decodeDirection(directionId, rawRouteId)
        val publicRouteId = TtcIds.routeId(rawRouteId)
        return client.routeStops(rawRouteId, forward, ttcLocale(locale))
            .filter(TtcStop::isBusStop)
            .map { stop -> stop.toNormalizedStop(locale, listOf(publicRouteId)) }
    }

    override suspend fun shape(routeId: String, directionId: String): Shape {
        val rawRouteId = TtcIds.decode(routeId, "route") { ProviderRouteNotFound("The requested route was not found") }
        val forward = TtcIds.decodeDirection(directionId, rawRouteId)
        val polyline = client.polyline(rawRouteId, forward)
        return Shape(
            // TTC publishes an encoded polyline and does not declare another precision. Its
            // documented client form is the Google-compatible precision 5 representation.
            precision = TtcPolylinePrecision,
            value = polyline,
            updatedAt = clock.instant().toString(),
        )
    }

    override suspend fun stopDirectory(locale: String): List<Stop> {
        // TTC's bounded bulk stops DTO has no route memberships. Keep this a single cancellable
        // request rather than building an unbounded route-by-route directory; empty routeIds is
        // an allowed normalized directory representation.
        return client.stops(ttcLocale(locale))
            .filter(TtcStop::isBusStop)
            .map { stop -> stop.toNormalizedStop(locale) }
    }

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles {
        val rawRouteId = TtcIds.decode(routeId, "route") { ProviderRouteNotFound("The requested route was not found") }
        val directions = if (directionId == null) {
            listOf(true, false)
        } else {
            listOf(TtcIds.decodeDirection(directionId, rawRouteId))
        }
        val publicRouteId = TtcIds.routeId(rawRouteId)
        val rawCandidates = directions.flatMap { forward ->
            client.positions(rawRouteId, forward).map { position ->
                forward to position
            }
        }
        val observedAt = clock.instant()
        val candidates = rawCandidates.map { (forward, position) ->
            TtcVehicleCandidate(rawRouteId, forward, position, observedAt, publicRouteId)
        }
        return RealtimeVehicles(
            items = vehicleTracker.assign(candidates),
            // TTC v2's documented positions object has no measurement timestamp. This is BFF
            // receipt time, so maxAge is zero and no record is advertised as stale/fresh upstream.
            observedAt = observedAt,
            maxAgeSeconds = 0,
            stale = false,
        )
    }

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        val rawStopId = TtcIds.decode(stopId, "stop") { ProviderStopNotFound("The requested stop was not found") }
        return freshArrivals(rawStopId, stopId, limit, locale)
    }

    override suspend fun probe(target: SyntheticProbeTarget): SyntheticProbeResult {
        require(target in probeTargets) { "Unknown synthetic probe target" }
        val publicStopId = TtcIds.stopId(target.privateTarget)
        val page = freshArrivals(
            rawStopId = target.privateTarget,
            publicStopId = publicStopId,
            limit = 1,
            locale = "en",
            operation = TelemetryOperation.PROBE_ARRIVALS,
        )
        return SyntheticProbeResult(
            observedAt = page.observedAt,
            itemCount = page.items.size,
            realtime = page.items.any(Arrival::realtime),
            stale = false,
        )
    }

    private suspend fun freshArrivals(
        rawStopId: String,
        publicStopId: String,
        limit: Int,
        locale: String,
        operation: TelemetryOperation = TelemetryOperation.ARRIVALS,
    ): RealtimeArrivals {
        val boundedLimit = limit.coerceIn(1, TtcMaximumArrivals)
        // Confirm the literal decoded raw ID through TTC's documented detail form before using
        // its routes/arrivals relations. A changed/rewritten provider ID is never substituted.
        if (client.stop(rawStopId, ttcLocale(locale), operation).rawId != rawStopId) {
            invalidNormalizedTtcResponse()
        }
        val arrivals = client.arrivals(rawStopId, ttcLocale(locale), operation).take(boundedLimit)
        val routesByShortName = client.stopRoutes(rawStopId, ttcLocale(locale), operation)
            .groupBy(TtcRoute::shortName)
        val observedAt = clock.instant()
        val normalized = arrivals.map { arrival ->
            val matchingRoutes = routesByShortName[arrival.shortName].orEmpty()
            val route = matchingRoutes.singleOrNull() ?: invalidNormalizedTtcResponse()
            arrival.toNormalizedArrival(
                publicStopId = publicStopId,
                publicRouteId = TtcIds.routeId(route.rawId),
                locale = locale,
                requestedAt = observedAt,
            )
        }
        val source = if (normalized.any(Arrival::realtime)) ArrivalSource.OFFICIAL_REALTIME else ArrivalSource.SCHEDULE
        NormalizedResponseValidator.arrivalPage(
            cityId = TtcCityId,
            stopId = publicStopId,
            page = ArrivalPage(normalized, source, observedAt.toString(), stale = false),
        )
        return RealtimeArrivals(normalized, source, observedAt, stale = false)
    }

    override suspend fun journeys(query: JourneyQuery): List<Journey> = journeyPage(query).items

    override suspend fun journeyPage(query: JourneyQuery): RealtimeJourneys {
        requireLeaveNowRequest(query.departureAt)
        val itineraries = client.plan(query.from, query.to, ttcLocale(query.locale), TelemetryOperation.JOURNEYS)
        val observedAt = clock.instant()
        val journeys = mutableListOf<TtcMappedJourney>()
        for (itinerary in itineraries) {
            itinerary.toNormalizedJourney(query.maxTransfers, ::resolvePlanDirection)?.let(journeys::add)
        }
        val realtime = journeys.any { it.realtime }
        val source = if (realtime) ArrivalSource.AGGREGATOR_REALTIME else ArrivalSource.SCHEDULE
        val page = JourneyPage(
            items = journeys.map(TtcMappedJourney::journey),
            observedAt = observedAt.toString(),
            source = source,
            realtime = realtime,
            stale = false,
        )
        NormalizedResponseValidator.journeyPage(TtcCityId, page)
        return RealtimeJourneys(page.items, source, realtime, observedAt, stale = false)
    }

    override suspend fun walkingEstimate(query: WalkingQuery): WalkingEstimate {
        val estimate = client.plan(query.from, query.to, ttcLocale(query.locale), TelemetryOperation.WALKING_ESTIMATE)
            .mapNotNull { itinerary -> itinerary.toWalkingEstimateOrNull() }
            .minWithOrNull(compareBy<WalkingEstimate> { it.durationSeconds }.thenBy { it.distanceMeters })
            ?: throw ProviderCapabilityUnavailable("Direct walking is not available")
        return estimate.copy(observedAt = clock.instant().toString())
    }

    override fun close() = client.close()

    private fun requireLeaveNowRequest(departureAt: Instant) {
        val now = clock.instant()
        if (abs(Duration.between(now, departureAt).seconds) > TtcMaximumPlanClockSkewSeconds) {
            throw ProviderInvalidArgument("The transit provider only supports leave-now planning")
        }
    }

    /** Plan legs omit direction, so prove F/B from the documented direction stop order. */
    private suspend fun resolvePlanDirection(
        rawRouteId: String,
        fromRawStopId: String,
        toRawStopId: String,
    ): Boolean? {
        val forwardStops = client.routeStops(
            rawRouteId,
            forward = true,
            locale = "en",
            operation = TelemetryOperation.JOURNEYS,
        ).map(TtcStop::rawId)
        val backwardStops = client.routeStops(
            rawRouteId,
            forward = false,
            locale = "en",
            operation = TelemetryOperation.JOURNEYS,
        ).map(TtcStop::rawId)
        val forwardMatches = forwardStops.orderedStopPair(fromRawStopId, toRawStopId)
        val backwardMatches = backwardStops.orderedStopPair(fromRawStopId, toRawStopId)
        return when {
            forwardMatches && !backwardMatches -> true
            backwardMatches && !forwardMatches -> false
            else -> null
        }
    }
}

private fun ttcLocale(locale: String): String = if (locale == "ka") "ka" else "en"

internal data class TtcRoute(
    val rawId: String,
    val shortName: String,
    val longName: String,
    val color: String,
    val forwardHeadsign: String,
    val backwardHeadsign: String,
)

internal data class TtcStop(
    val rawId: String,
    val code: String?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val mode: String,
)

internal data class TtcPosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Double,
    val nextStopRawId: String?,
)

internal data class TtcArrival(
    val shortName: String,
    val headsign: String,
    val realtime: Boolean,
    val realtimeMinutes: Int,
    val scheduledMinutes: Int,
)

internal data class TtcPlanPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

internal data class TtcPlanStop(
    val rawId: String,
    val latitude: Double,
    val longitude: Double,
)

internal data class TtcPlanLeg(
    val from: TtcPlanPlace,
    val to: TtcPlanPlace,
    val start: Instant,
    val end: Instant,
    val mode: String,
    val route: TtcRoute?,
    val realtime: Boolean,
    val distanceMeters: Double,
    val intermediateStops: List<TtcPlanStop>?,
)

internal data class TtcPlanItinerary(
    val start: Instant,
    val end: Instant,
    val durationSeconds: Long,
    val legs: List<TtcPlanLeg>,
)

private fun TtcRoute.toNormalizedRoute(locale: String): Route {
    val normalizedColor = color.normalizeTtcColor()
    return Route(
        id = TtcIds.routeId(rawId),
        providerId = TtcIds.providerSuffix(rawId),
        shortName = shortName,
        longName = localizedTtcText(longName, locale),
        color = normalizedColor,
        textColor = normalizedColor.accessibleTextColor(),
        mode = "bus",
        directions = listOf(
            Direction(
                id = TtcIds.directionId(rawId, forward = true),
                name = localizedTtcText("Forward", locale),
                headsign = localizedTtcText(forwardHeadsign, locale),
            ),
            Direction(
                id = TtcIds.directionId(rawId, forward = false),
                name = localizedTtcText("Backward", locale),
                headsign = localizedTtcText(backwardHeadsign, locale),
            ),
        ),
    )
}

private fun TtcStop.toNormalizedStop(locale: String, routeIds: List<String> = emptyList()): Stop = Stop(
    id = TtcIds.stopId(rawId),
    providerId = TtcIds.providerSuffix(rawId),
    code = code ?: TtcIds.providerSuffix(rawId),
    name = localizedTtcText(name, locale),
    position = GeoPoint(latitude, longitude),
    routeIds = routeIds,
    mode = "bus",
)

private fun TtcStop.isBusStop(): Boolean = mode == "BUS"

private fun TtcArrival.toNormalizedArrival(
    publicStopId: String,
    publicRouteId: String,
    locale: String,
    requestedAt: Instant,
): Arrival {
    if (realtimeMinutes < 0 || scheduledMinutes < 0) invalidNormalizedTtcResponse()
    return Arrival(
        stopId = publicStopId,
        routeId = publicRouteId,
        headsign = localizedTtcText(headsign, locale),
        scheduledAt = requestedAt.plusSeconds(scheduledMinutes * 60L).toString(),
        expectedAt = if (realtime) requestedAt.plusSeconds(realtimeMinutes * 60L).toString() else null,
        expectedInMinutes = if (realtime) realtimeMinutes else null,
        realtime = realtime,
        cancelled = false,
        source = if (realtime) ArrivalSource.OFFICIAL_REALTIME else ArrivalSource.SCHEDULE,
    )
}

private data class TtcVehicleCandidate(
    val routeRawId: String,
    val forward: Boolean,
    val position: TtcPosition,
    val observedAt: Instant,
    val publicRouteId: String,
) {
    /** Equal same-snapshot evidence cannot distinguish separate TTC vehicles. */
    val collisionKey: String = listOf(
        routeRawId,
        if (forward) "forward" else "backward",
        "%.5f".format(java.util.Locale.ROOT, position.latitude),
        "%.5f".format(java.util.Locale.ROOT, position.longitude),
        position.heading.toInt().toString(),
        position.nextStopRawId.orEmpty(),
    ).joinToString("\u0000")

    val scope: String = "$routeRawId\u0000${if (forward) "F" else "B"}"
}

/**
 * TTC omits vehicle IDs. This bounded, process-local tracker carries opaque ephemeral IDs only
 * across plausible adjacent snapshots. It never promises an upstream/persistent vehicle identity.
 */
private class TtcVehicleTracker(private val clock: Clock) {
    private val mutex = Mutex()
    private val tracks = LinkedHashMap<String, Track>()

    suspend fun assign(candidates: List<TtcVehicleCandidate>): List<Vehicle> = mutex.withLock {
        val now = clock.instant()
        tracks.entries.removeIf { (_, track) -> Duration.between(track.lastSeen, now).seconds !in 0..TtcVehicleTrackerTtlSeconds }
        val output = mutableListOf<Vehicle>()
        candidates.groupBy(TtcVehicleCandidate::scope).forEach { (scope, scoped) ->
            // A list-index identifier would be unstable. Exact duplicate observations are instead
            // omitted because no evidence distinguishes them.
            val unique = scoped.groupBy(TtcVehicleCandidate::collisionKey).values
                .filter { it.size == 1 }
                .map { it.single() }
            val scopedTracks = tracks.values.filter { it.scope == scope }
            val feasible: Map<TtcVehicleCandidate, List<Pair<Track, Double>>> = unique.associateWith { candidate ->
                scopedTracks.mapNotNull { track ->
                    track.takeIf { it.canMatch(candidate) }?.let { matched -> matched to matched.distanceTo(candidate) }
                }
            }
            val candidateNearest: Map<TtcVehicleCandidate, Track?> =
                feasible.mapValues { (_, options) -> options.nearestUniqueTrack() }
            val trackNearest: Map<Track, TtcVehicleCandidate?> = scopedTracks.associateWith { track ->
                unique.mapNotNull { candidate ->
                    candidate.takeIf { track.canMatch(it) }?.let { it to track.distanceTo(it) }
                }.nearestUniqueCandidate()
            }
            unique.forEach { candidate ->
                val nearestTrack = candidateNearest.getValue(candidate)
                val token = when {
                    nearestTrack == null && feasible.getValue(candidate).isEmpty() -> newToken()
                    nearestTrack != null && trackNearest[nearestTrack] == candidate -> nearestTrack.token
                    // Any contested/ambiguous historical match is filtered rather than duplicating
                    // one physical vehicle under a fresh identity.
                    else -> return@forEach
                }
                tracks[token] = Track.from(candidate, token)
                output += candidate.toVehicle(token)
            }
            trimScope(scope)
        }
        trimGlobal()
        output.sortedBy(Vehicle::id)
    }

    private fun newToken(): String = "ephemeral-${UUID.randomUUID()}"

    private fun trimScope(scope: String) {
        tracks.values.filter { it.scope == scope }
            .sortedBy(Track::lastSeen)
            .dropLast(TtcVehicleTrackerMaximumEntriesPerScope)
            .forEach { tracks.remove(it.token) }
    }

    private fun trimGlobal() {
        tracks.values.sortedBy(Track::lastSeen)
            .dropLast(TtcVehicleTrackerMaximumEntries)
            .forEach { tracks.remove(it.token) }
    }

    data class Track(
        val token: String,
        val scope: String,
        val point: GeoPoint,
        val heading: Double,
        val nextStopRawId: String?,
        val lastSeen: Instant,
    ) {
        fun distanceTo(candidate: TtcVehicleCandidate): Double = haversineMeters(
            point,
            GeoPoint(candidate.position.latitude, candidate.position.longitude),
        ).toDouble()

        fun canMatch(candidate: TtcVehicleCandidate): Boolean {
            val elapsed = Duration.between(lastSeen, candidate.observedAt).seconds
            if (elapsed !in 0..TtcVehicleTrackerTtlSeconds) return false
            val allowedDistance = (TtcVehicleMatchBaseMeters + TtcVehicleMaximumMetersPerSecond * elapsed)
                .coerceAtMost(TtcVehicleMaximumMatchMeters)
            if (distanceTo(candidate) > allowedDistance) return false
            if (headingDifference(heading, candidate.position.heading) > 100.0) return false
            return nextStopRawId == null || candidate.position.nextStopRawId == null || nextStopRawId == candidate.position.nextStopRawId
        }

        companion object {
            fun from(candidate: TtcVehicleCandidate, token: String): Track = Track(
                token = token,
                scope = candidate.scope,
                point = GeoPoint(candidate.position.latitude, candidate.position.longitude),
                heading = candidate.position.heading,
                nextStopRawId = candidate.position.nextStopRawId,
                lastSeen = candidate.observedAt,
            )
        }
    }
}

private fun List<Pair<TtcVehicleTracker.Track, Double>>.nearestUniqueTrack(): TtcVehicleTracker.Track? {
    val candidates = sortedBy { it.second }
    if (candidates.isEmpty()) return null
    if (candidates.size == 1 || candidates[1].second - candidates[0].second > TtcVehicleAmbiguousMeters) {
        return candidates.first().first
    }
    return null
}

private fun List<Pair<TtcVehicleCandidate, Double>>.nearestUniqueCandidate(): TtcVehicleCandidate? {
    val candidates = sortedBy { it.second }
    if (candidates.isEmpty()) return null
    if (candidates.size == 1 || candidates[1].second - candidates[0].second > TtcVehicleAmbiguousMeters) {
        return candidates.first().first
    }
    return null
}

private fun headingDifference(first: Double, second: Double): Double =
    abs((first - second + 540.0) % 360.0 - 180.0)

private fun TtcVehicleCandidate.toVehicle(token: String): Vehicle = Vehicle(
    id = TtcIds.vehicleId(token),
    routeId = publicRouteId,
    directionId = TtcIds.directionId(routeRawId, forward),
    position = GeoPoint(position.latitude, position.longitude),
    bearing = position.heading,
    nextStopId = position.nextStopRawId?.let(TtcIds::stopId),
    observedAt = observedAt.toString(),
    ageSeconds = 0,
    positionKind = PositionKind.GPS,
)

private data class TtcMappedJourney(val journey: Journey, val realtime: Boolean)

private suspend fun TtcPlanItinerary.toNormalizedJourney(
    maxTransfers: Int,
    resolveDirection: suspend (rawRouteId: String, fromRawStopId: String, toRawStopId: String) -> Boolean?,
): TtcMappedJourney? {
    if (legs.isEmpty() || legs.size > TtcMaximumLegs || end.isBefore(start) || durationSeconds <= 0L) return null
    var previous = start
    val segments = mutableListOf<JourneySegment>()
    for (leg in legs) {
        if (leg.start.isBefore(previous) || leg.end.isBefore(leg.start) || leg.start.isBefore(start) || leg.end.isAfter(end)) {
            return null
        }
        previous = leg.end
        val segment = when (leg.mode) {
            "WALK" -> leg.toWalkingSegment()
            "BUS" -> {
                val transit = leg.toTransitLegOrNull() ?: return null
                val forward = resolveDirection(transit.rawRouteId, transit.fromRawStopId, transit.toRawStopId) ?: return null
                transit.toJourneySegment(forward)
            }
            else -> return null
        }
        segments += segment
    }
    val transitSegments = segments.filter { it.mode == JourneySegmentMode.TRANSIT }
    val transfers = (transitSegments.size - 1).coerceAtLeast(0)
    if (transfers > maxTransfers.coerceAtMost(6)) return null
    val canonical = buildString {
        append(start).append('|').append(end)
        legs.forEach { leg -> append('|').append(leg.mode).append('|').append(leg.start).append('|').append(leg.end) }
    }
    val journeyToken = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .copyOfRange(0, 18)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    return TtcMappedJourney(
        journey = Journey(
            id = TtcIds.journeyId("plan-$journeyToken"),
            departureAt = start.toString(),
            arrivalAt = end.toString(),
            transfers = transfers,
            legs = transitSegments.map { segment ->
                JourneyLeg(
                    routeId = requireNotNull(segment.routeId),
                    directionId = requireNotNull(segment.directionId),
                    fromStopId = requireNotNull(segment.fromStopId),
                    toStopId = requireNotNull(segment.toStopId),
                    departureAt = segment.departureAt,
                    arrivalAt = segment.arrivalAt,
                )
            },
            segments = segments,
        ),
        realtime = legs.any(TtcPlanLeg::realtime),
    )
}

private fun TtcPlanLeg.toWalkingSegment(): JourneySegment = JourneySegment(
        departureAt = start.toString(),
        arrivalAt = end.toString(),
        mode = JourneySegmentMode.WALK,
        fromPosition = GeoPoint(from.latitude, from.longitude),
        toPosition = GeoPoint(to.latitude, to.longitude),
    )

private data class TtcCompleteTransitLeg(
    val rawRouteId: String,
    val fromRawStopId: String,
    val toRawStopId: String,
    val departureAt: Instant,
    val arrivalAt: Instant,
    val from: GeoPoint,
    val to: GeoPoint,
)

private fun TtcPlanLeg.toTransitLegOrNull(): TtcCompleteTransitLeg? {
    val rawRouteId = route?.rawId ?: return null
    // The v2 contract exposes intermediate stop IDs. A transit leg is accepted only if those
    // documented stops prove both endpoints, rather than manufacturing stop IDs from names.
    val stops = intermediateStops ?: return null
    val first = stops.firstOrNull()?.takeIf { it.matches(from) } ?: return null
    val last = stops.lastOrNull()?.takeIf { it.matches(to) } ?: return null
    return TtcCompleteTransitLeg(
        rawRouteId = rawRouteId,
        fromRawStopId = first.rawId,
        toRawStopId = last.rawId,
        departureAt = start,
        arrivalAt = end,
        from = GeoPoint(from.latitude, from.longitude),
        to = GeoPoint(to.latitude, to.longitude),
    )
}

private fun TtcCompleteTransitLeg.toJourneySegment(forward: Boolean): JourneySegment = JourneySegment(
    departureAt = departureAt.toString(),
    arrivalAt = arrivalAt.toString(),
    mode = JourneySegmentMode.TRANSIT,
    routeId = TtcIds.routeId(rawRouteId),
    directionId = TtcIds.directionId(rawRouteId, forward),
    fromStopId = TtcIds.stopId(fromRawStopId),
    toStopId = TtcIds.stopId(toRawStopId),
    fromPosition = from,
    toPosition = to,
)

private fun TtcPlanStop.matches(place: TtcPlanPlace): Boolean =
    abs(latitude - place.latitude) <= 0.000_01 && abs(longitude - place.longitude) <= 0.000_01

private fun List<String>.orderedStopPair(fromRawStopId: String, toRawStopId: String): Boolean {
    val fromIndex = indexOf(fromRawStopId)
    val toIndex = indexOf(toRawStopId)
    return fromIndex >= 0 && toIndex > fromIndex
}

private fun TtcPlanItinerary.toWalkingEstimateOrNull(): WalkingEstimate? {
    if (legs.isEmpty() || legs.any { it.mode != "WALK" }) return null
    var previous = start
    for (leg in legs) {
        if (leg.start.isBefore(previous) || leg.end.isBefore(leg.start) || leg.start.isBefore(start) || leg.end.isAfter(end)) {
            return null
        }
        previous = leg.end
    }
    val distance = legs.sumOf { it.distanceMeters }
    val duration = Duration.between(start, end).seconds
    if (!distance.isFinite() || distance < 0.0 || duration <= 0L || durationSeconds != duration) return null
    return WalkingEstimate(distanceMeters = distance, durationSeconds = duration, observedAt = Instant.EPOCH.toString())
}

private fun localizedTtcText(value: String, requestedLocale: String): LocalizedText {
    if (value.isBlank()) invalidNormalizedTtcResponse()
    // TTC offers ka/en. For ru, request en upstream and make the fallback deterministic; all
    // LocalizedText members remain populated without widening the public API contract.
    return when (requestedLocale) {
        "ka" -> LocalizedText(ru = value, en = value, ka = value)
        else -> LocalizedText(ru = value, en = value, ka = value)
    }
}

private fun String.normalizeTtcColor(): String {
    val normalized = removePrefix("#").uppercase()
    return if (normalized.matches(Regex("[0-9A-F]{6}"))) "#$normalized" else TtcSafeRouteColor
}

private fun String.accessibleTextColor(): String {
    val components = removePrefix("#").takeIf { it.matches(Regex("[0-9A-F]{6}")) }
        ?.chunked(2)?.map { it.toInt(16) } ?: return TtcSafeTextColor
    fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).let { x -> x * x * x } }
    val luminance = 0.2126 * channel(components[0]) + 0.7152 * channel(components[1]) + 0.0722 * channel(components[2])
    return if (luminance > 0.179) "#000000" else TtcSafeTextColor
}

private object TtcIds {
    private const val Prefix = "$TtcCityId:$TtcProviderId"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun routeId(raw: String): String = publicId("route", raw)
    fun stopId(raw: String): String = publicId("stop", raw)
    fun vehicleId(raw: String): String = publicId("vehicle", raw)
    fun journeyId(raw: String): String = publicId("journey", raw)
    fun directionId(routeRaw: String, forward: Boolean): String = publicId("direction", "$routeRaw\u0000${if (forward) "F" else "B"}")

    fun providerSuffix(raw: String): String {
        validateRaw(raw)
        return encoder.encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(publicId: String, entity: String, failure: () -> ProviderFailure): String = try {
        val parts = publicId.split(':')
        if (parts.size != 4 || parts[0] != TtcCityId || parts[1] != TtcProviderId || parts[2] != entity || parts[3].isBlank()) {
            throw IllegalArgumentException()
        }
        decoder.decode(parts[3]).toString(StandardCharsets.UTF_8).also { raw ->
            validateRaw(raw)
            if (encoder.encodeToString(raw.toByteArray(StandardCharsets.UTF_8)) != parts[3]) throw IllegalArgumentException()
        }
    } catch (_: Exception) {
        throw failure()
    }

    fun decodeDirection(directionId: String, expectedRouteRaw: String): Boolean {
        val raw = decode(directionId, "direction") { ProviderInvalidArgument("The requested direction is not available") }
        val separator = raw.lastIndexOf('\u0000')
        if (separator <= 0 || raw.substring(0, separator) != expectedRouteRaw) {
            throw ProviderInvalidArgument("The requested direction is not available")
        }
        return when (raw.substring(separator + 1)) {
            "F" -> true
            "B" -> false
            else -> throw ProviderCapabilityUnavailable("The requested direction is not available")
        }
    }

    private fun publicId(entity: String, raw: String): String {
        validateRaw(raw)
        return "$Prefix:$entity:${encoder.encodeToString(raw.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun validateRaw(raw: String) {
        if (
            raw.isBlank() || raw.any(Char::isWhitespace) || raw.any { it == '/' || it == '\\' } ||
                raw.toByteArray(StandardCharsets.UTF_8).size > 150
        ) {
            invalidNormalizedTtcResponse()
        }
    }
}

private fun invalidNormalizedTtcResponse(): Nothing =
    throw ProviderNormalizedSchemaFailure("The transit provider returned an invalid response")

/** TTC HTTP boundary: one credential header, bounded GET retries, response size and local load. */
internal class TtcClient(
    private val activation: TtcActivationConfig,
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        // A credential-bearing request must never follow a provider-controlled redirect. The
        // resulting 3xx is handled as a fixed safe provider failure below.
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = activation.connectTimeoutMillis
            requestTimeoutMillis = activation.requestTimeoutMillis
            socketTimeoutMillis = activation.socketTimeoutMillis
        }
    },
    private val observability: ProviderCallObservability = NoopProviderCallObservability,
    private val retryJitter: TtcRetryJitter = DefaultTtcRetryJitter,
) : AutoCloseable {
    private val budget = TtcUpstreamBudget(activation.maximumConcurrentRequests, activation.maximumStartsPerMinute)

    init {
        require(activation.isActivated) { "TTC client requires explicit activation" }
    }

    suspend fun routes(
        locale: String,
        operation: TelemetryOperation,
    ): List<TtcRoute> =
        getArray(
            listOf("routes"),
            listOf("modes" to "BUS", "locale" to locale),
            operation.capability,
            operation,
        )
            .map(::parseTtcRoute)
            .bounded(TtcMaximumRoutes)

    suspend fun stops(locale: String): List<TtcStop> =
        getArray(listOf("stops"), listOf("locale" to locale), TelemetryCapability.STOPS, TelemetryOperation.NEARBY_STOPS)
            .map(::parseTtcStop)
            .bounded(TtcMaximumStops)

    suspend fun stop(rawStopId: String, locale: String, operation: TelemetryOperation): TtcStop =
        parseTtcStop(
            getObject(
                listOf("stops", ttcEntityReference(rawStopId)),
                listOf("locale" to locale),
                operation.capability,
                operation,
            ),
        )

    suspend fun stopRoutes(rawStopId: String, locale: String, operation: TelemetryOperation): List<TtcRoute> =
        getArray(
            listOf("stops", ttcEntityReference(rawStopId), "routes"),
            listOf("locale" to locale),
            operation.capability,
            operation,
        ).map(::parseTtcRoute).bounded(TtcMaximumRoutes)

    suspend fun routeStops(
        rawRouteId: String,
        forward: Boolean,
        locale: String,
        operation: TelemetryOperation = TelemetryOperation.DIRECTION_STOPS,
    ): List<TtcStop> =
        getArray(
            listOf("routes", ttcEntityReference(rawRouteId), "stops"),
            listOf("forward" to forward.toString(), "locale" to locale),
            operation.capability,
            operation,
        ).map(::parseTtcStop).bounded(TtcMaximumStops)

    suspend fun polyline(rawRouteId: String, forward: Boolean): String =
        getObject(
            listOf("routes", ttcEntityReference(rawRouteId), "polyline"),
            listOf("forward" to forward.toString()),
            TelemetryCapability.ROUTE_GEOMETRY,
            TelemetryOperation.SHAPE,
        ).requiredString("encodedValue")

    suspend fun positions(rawRouteId: String, forward: Boolean): List<TtcPosition> =
        getArray(
            listOf("routes", ttcEntityReference(rawRouteId), "positions"),
            listOf("forward" to forward.toString()),
            TelemetryCapability.VEHICLE_POSITIONS,
            TelemetryOperation.VEHICLES,
        ).map(::parseTtcPosition).bounded(TtcMaximumPositions)

    suspend fun arrivals(rawStopId: String, locale: String, operation: TelemetryOperation): List<TtcArrival> =
        getArray(
            listOf("stops", ttcEntityReference(rawStopId), "arrival-times"),
            listOf("locale" to locale, "ignoreScheduledArrivalTimes" to "false"),
            TelemetryCapability.ARRIVALS,
            operation,
        ).map(::parseTtcArrival).bounded(TtcMaximumArrivals)

    suspend fun plan(
        from: GeoPoint,
        to: GeoPoint,
        locale: String,
        operation: TelemetryOperation,
    ): List<TtcPlanItinerary> =
        getObject(
            listOf("plan"),
            listOf(
                "fromPlace" to "${from.latitude},${from.longitude}",
                "toPlace" to "${to.latitude},${to.longitude}",
                "departMode" to "leaveNow",
                "modes" to "WALK,BUS",
                "optimize" to "quick",
                "locale" to locale,
            ),
            operation.capability,
            operation,
        ).requiredArray("itineraries").map(::parseTtcItinerary).bounded(TtcMaximumItineraries)

    override fun close() = httpClient.close()

    private suspend fun getArray(
        path: List<String>,
        query: List<Pair<String, String>>,
        capability: TelemetryCapability,
        operation: TelemetryOperation,
    ): JsonArray = get(path, query, capability, operation).asArray()

    private suspend fun getObject(
        path: List<String>,
        query: List<Pair<String, String>>,
        capability: TelemetryCapability,
        operation: TelemetryOperation,
    ): JsonObject = get(path, query, capability, operation).asObject()

    private suspend fun get(
        path: List<String>,
        query: List<Pair<String, String>>,
        capability: TelemetryCapability,
        operation: TelemetryOperation,
    ): JsonElement {
        val labels = ProviderTelemetryLabels(TtcCityId, TelemetryProvider.TTC, capability, operation)
        val missing = operation.ttcNotFoundFailure()
        var retry = 0
        while (true) {
            try {
                val attempt = budget.execute(labels, observability) {
                    val response = httpClient.get {
                        url(requireNotNull(activation.baseUrl).toString())
                        url {
                            appendPathSegments(*path.toTypedArray(), encodeSlash = true)
                            query.forEach { (name, value) -> parameters.append(name, value) }
                        }
                        // Credential ownership has exactly one write site in the BFF.
                        header("X-Api-Key", activation.apiKeyForRequest())
                        timeout {
                            connectTimeoutMillis = activation.connectTimeoutMillis
                            requestTimeoutMillis = activation.requestTimeoutMillis
                            socketTimeoutMillis = activation.socketTimeoutMillis
                        }
                    }
                    response.toTtcAttempt(missing)
                }
                when (attempt) {
                    is TtcAttempt.Complete -> return attempt.value
                    is TtcAttempt.Retry -> {
                        retry += 1
                        if (retry > activation.maximumRetries) throw attempt.failure
                        observability.recordEvent(labels, ProviderEvent.RETRY)
                        delay(retryDelayMillis(retry, attempt.retryAfterSeconds))
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ProviderFailure) {
                throw failure
            } catch (failure: Throwable) {
                if (failure.isRetryableTtcTransport() && retry < activation.maximumRetries) {
                    retry += 1
                    observability.recordEvent(labels, ProviderEvent.RETRY)
                    delay(retryDelayMillis(retry, null))
                } else if (failure.isTimeoutTtcTransport()) {
                    throw ProviderTimeout("The transit provider did not respond in time")
                } else {
                    throw ProviderUnavailable("The transit provider is unavailable")
                }
            }
        }
    }

    private suspend fun HttpResponse.toTtcAttempt(missing: ProviderFailure): TtcAttempt = when {
        status.value in RetryableTtcStatusCodes -> {
            val retryAfter = retryAfterSeconds()
            if (status == HttpStatusCode.TooManyRequests && retryAfter > TtcMaximumRetryAfterSeconds) {
                call.cancel()
                throw ProviderRateLimited("The transit provider is rate limited", retryAfter)
            }
            // The permit covers cancellation of retryable error bodies too. Never hand an open
            // response stream to the retry loop after the local concurrency slot is released.
            val failure = retryFailure(retryAfter)
            call.cancel()
            TtcAttempt.Retry(retryAfter, failure)
        }
        else -> TtcAttempt.Complete(decodeOrThrow(missing))
    }

    private fun HttpResponse.retryFailure(retryAfterSeconds: Int): ProviderFailure = when {
        status == HttpStatusCode.RequestTimeout -> ProviderTimeout("The transit provider did not respond in time")
        status == HttpStatusCode.TooManyRequests -> ProviderRateLimited("The transit provider is rate limited", retryAfterSeconds)
        status.value in 500..599 -> ProviderUnavailable("The transit provider is unavailable")
        else -> ProviderBadGateway("The transit provider returned an invalid response")
    }

    private suspend fun HttpResponse.decodeOrThrow(missing: ProviderFailure): JsonElement = when {
        status == HttpStatusCode.OK -> try {
            TtcJson.parseToJsonElement(readBodyWithinLimit())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (_: Exception) {
            throw ProviderJsonDecodeFailure("The transit provider returned an invalid response")
        }
        status == HttpStatusCode.NotFound -> discardAndThrow(missing)
        status == HttpStatusCode.RequestTimeout -> discardAndThrow(ProviderTimeout("The transit provider did not respond in time"))
        status == HttpStatusCode.TooManyRequests -> discardAndThrow(
            ProviderRateLimited("The transit provider is rate limited", retryAfterSeconds()),
        )
        status.value in 500..599 -> discardAndThrow(ProviderUnavailable("The transit provider is unavailable"))
        status == HttpStatusCode.BadRequest || status == HttpStatusCode.UnprocessableEntity -> discardAndThrow(
            ProviderInvalidArgument("The transit provider rejected the request"),
        )
        else -> discardAndThrow(ProviderBadGateway("The transit provider returned an invalid response"))
    }

    private suspend fun HttpResponse.readBodyWithinLimit(): String {
        if ((headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > TtcMaximumResponseBytes) {
            call.cancel()
            invalidNormalizedTtcResponse()
        }
        val channel = bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (count == 0) continue
            if (output.size() > TtcMaximumResponseBytes - count) {
                call.cancel()
                invalidNormalizedTtcResponse()
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun HttpResponse.discardAndThrow(failure: ProviderFailure): Nothing {
        call.cancel()
        throw failure
    }

    private fun HttpResponse.retryAfterSeconds(): Int {
        val raw = headers[HttpHeaders.RetryAfter] ?: return 1
        return raw.toIntOrNull()?.takeIf { it in 1..TtcMaximumPublishedRetryAfterSeconds }
            ?: runCatching {
                Duration.between(Instant.now(), ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                    .seconds.coerceIn(1L, TtcMaximumPublishedRetryAfterSeconds.toLong()).toInt()
            }.getOrDefault(1)
    }

    private fun retryDelayMillis(retry: Int, retryAfterSeconds: Int?): Long {
        val exponential = (250L * (1L shl (retry - 1))).coerceAtMost(2_000L)
        val jitter = retryJitter.nextMillis((exponential / 4L).coerceAtLeast(1L)).coerceIn(0L, exponential / 4L)
        val retryAfter = (retryAfterSeconds ?: 0).coerceAtMost(TtcMaximumRetryAfterSeconds) * 1_000L
        return max(exponential + jitter, retryAfter)
    }
}

/** Injectable so provider retry timing can be checked deterministically without a live TTC call. */
internal fun interface TtcRetryJitter {
    fun nextMillis(upperInclusive: Long): Long
}

private object DefaultTtcRetryJitter : TtcRetryJitter {
    override fun nextMillis(upperInclusive: Long): Long =
        ThreadLocalRandom.current().nextLong(upperInclusive + 1L)
}

private sealed interface TtcAttempt {
    data class Complete(val value: JsonElement) : TtcAttempt
    data class Retry(val retryAfterSeconds: Int, val failure: ProviderFailure) : TtcAttempt
}

private val RetryableTtcStatusCodes = setOf(408, 429, 500, 502, 503, 504)

private fun TelemetryOperation.ttcNotFoundFailure(): ProviderFailure = when (this) {
    TelemetryOperation.LIST_ROUTES,
    TelemetryOperation.ROUTE,
    TelemetryOperation.SHAPE,
    TelemetryOperation.VEHICLES,
    -> ProviderRouteNotFound("The requested route was not found")
    TelemetryOperation.DIRECTION_STOPS,
    TelemetryOperation.NEARBY_STOPS,
    TelemetryOperation.ARRIVALS,
    TelemetryOperation.PROBE_ARRIVALS,
    -> ProviderStopNotFound("The requested stop was not found")
    TelemetryOperation.JOURNEYS,
    TelemetryOperation.WALKING_ESTIMATE,
    TelemetryOperation.PROBE_JOURNEYS,
    -> ProviderUnavailable("The transit provider is unavailable")
}

private fun Throwable.isRetryableTtcTransport(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException || this is IOException

private fun Throwable.isTimeoutTtcTransport(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

private class TtcUpstreamBudget(maximumConcurrentRequests: Int, private val maximumStartsPerMinute: Int) {
    private val permits = Semaphore(maximumConcurrentRequests)
    private val starts = ArrayDeque<Long>()
    private val mutex = Mutex()

    suspend fun <T> execute(
        labels: ProviderTelemetryLabels,
        observability: ProviderCallObservability,
        block: suspend () -> T,
    ): T {
        if (!permits.tryAcquire()) {
            observability.recordEvent(labels, ProviderEvent.RATE_BUDGET_REJECTED)
            throw ProviderRateLimited("The transit provider is rate limited", 1)
        }
        try {
            reserveStart(labels, observability)
            return block()
        } finally {
            permits.release()
        }
    }

    private suspend fun reserveStart(
        labels: ProviderTelemetryLabels,
        observability: ProviderCallObservability,
    ) {
        val waitMillis = mutex.withLock {
            val now = System.currentTimeMillis()
            while (starts.firstOrNull()?.let { now - it >= TtcRateWindowMillis } == true) starts.removeFirst()
            if (starts.size < maximumStartsPerMinute) {
                starts.addLast(now)
                return
            }
            ceil((starts.first() + TtcRateWindowMillis - now).toDouble()).toLong().coerceAtLeast(1L)
        }
        observability.recordEvent(labels, ProviderEvent.RATE_BUDGET_REJECTED)
        throw ProviderRateLimited(
            "The transit provider is rate limited",
            ceil(waitMillis / 1_000.0).toInt().coerceIn(1, 60),
        )
    }
}

private fun ttcEntityReference(rawId: String): String {
    // Preserve a literal TTC `1:` prefix rather than stripping/re-adding it. Older response
    // variants may emit an unprefixed ID, for which TTC v2 requires the documented `1:` ref.
    if (
        rawId.isBlank() || rawId.any(Char::isWhitespace) || rawId.any { it.isISOControl() || it == '/' || it == '\\' } ||
            rawId.toByteArray(StandardCharsets.UTF_8).size > 150
    ) {
        invalidNormalizedTtcResponse()
    }
    return if (rawId.startsWith("1:")) rawId else "1:$rawId"
}

private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: invalidNormalizedTtcResponse()
private fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: invalidNormalizedTtcResponse()
private fun JsonObject.requiredObject(name: String): JsonObject = this[name].asRequiredElement().asObject()
private fun JsonObject.requiredArray(name: String): JsonArray = this[name].asRequiredElement().asArray()
private fun JsonObject.requiredString(name: String): String = this[name].asRequiredElement().stringValue()
private fun JsonObject.optionalStringOrNull(name: String): String? = when (val value = this[name]) {
    null,
    JsonNull,
    -> null
    else -> value.stringValue()
}
private fun JsonObject.requiredNumber(name: String): Double = this[name].asRequiredElement().numberValue()
private fun JsonObject.requiredBoolean(name: String): Boolean = this[name].asRequiredElement().booleanValue()
private fun JsonElement?.asRequiredElement(): JsonElement = this ?: invalidNormalizedTtcResponse()
private fun JsonElement.stringValue(): String = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    ?.takeIf(String::isNotBlank) ?: invalidNormalizedTtcResponse()
private fun JsonElement.numberValue(): Double = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
    ?.takeIf(Double::isFinite) ?: invalidNormalizedTtcResponse()
private fun JsonElement.booleanValue(): Boolean = (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.let { value ->
    when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }
} ?: invalidNormalizedTtcResponse()
private fun <T> List<T>.bounded(maximum: Int): List<T> =
    takeIf { size <= maximum } ?: invalidNormalizedTtcResponse()

private fun parseTtcRoute(element: JsonElement): TtcRoute {
    val source = element.asObject()
    if (source.requiredString("mode") != "BUS") invalidNormalizedTtcResponse()
    val longName = source.requiredString("longName")
    val names = source["longNames"]?.let { value ->
        if (value is JsonNull) invalidNormalizedTtcResponse()
        value.asObject()
    }
    return TtcRoute(
        rawId = source.requiredString("id"),
        shortName = source.requiredString("shortName"),
        longName = longName,
        color = source.requiredString("color"),
        forwardHeadsign = names?.requiredString("forwardLongName") ?: longName,
        backwardHeadsign = names?.requiredString("backwardLongName") ?: longName,
    )
}

private fun parseTtcStop(element: JsonElement): TtcStop {
    val source = element.asObject()
    val mode = source.requiredString("vehicleMode")
    if (mode !in setOf("BUS", "GONDOLA", "SUBWAY")) invalidNormalizedTtcResponse()
    return TtcStop(
        rawId = source.requiredString("id"),
        code = source.optionalStringOrNull("code"),
        name = source.requiredString("name"),
        latitude = source.requiredNumber("lat").validLatitude(),
        longitude = source.requiredNumber("lon").validLongitude(),
        mode = mode,
    )
}

private fun parseTtcPosition(element: JsonElement): TtcPosition {
    val source = element.asObject()
    return TtcPosition(
        latitude = source.requiredNumber("lat").validLatitude(),
        longitude = source.requiredNumber("lon").validLongitude(),
        heading = source.requiredNumber("heading").takeIf { it in 0.0..360.0 } ?: invalidNormalizedTtcResponse(),
        nextStopRawId = source.optionalStringOrNull("nextStopId"),
    )
}

private fun parseTtcArrival(element: JsonElement): TtcArrival {
    val source = element.asObject()
    // The TTC type marks both minute values numeric. Accept string numeric encoding only at this
    // wire boundary, reject null/negative/decimal values, and never let a malformed ETA escape.
    return TtcArrival(
        shortName = source.requiredString("shortName"),
        headsign = source.requiredString("headsign"),
        realtime = source.requiredBoolean("realtime"),
        realtimeMinutes = source.requiredNumber("realtimeArrivalMinutes").minutesValue(),
        scheduledMinutes = source.requiredNumber("scheduledArrivalMinutes").minutesValue(),
    )
}

private fun parseTtcItinerary(element: JsonElement): TtcPlanItinerary {
    val source = element.asObject()
    val start = source.requiredString("startTime").toTtcInstant()
    val end = source.requiredString("endTime").toTtcInstant()
    return TtcPlanItinerary(
        start = start,
        end = end,
        durationSeconds = source.requiredNumber("duration").durationValue(),
        legs = source.requiredArray("legs").map(::parseTtcPlanLeg).bounded(TtcMaximumLegs),
    )
}

private fun parseTtcPlanLeg(element: JsonElement): TtcPlanLeg {
    val source = element.asObject()
    val intermediate = when (val value = source["intermediateStops"]) {
        null,
        JsonNull,
        -> null
        else -> value.asArray().map(::parseTtcPlanStop).bounded(TtcMaximumStops)
    }
    val mode = source.requiredString("mode")
    if (mode !in setOf("WALK", "BUS")) invalidNormalizedTtcResponse()
    return TtcPlanLeg(
        from = source.requiredObject("from").toTtcPlanPlace(),
        to = source.requiredObject("to").toTtcPlanPlace(),
        start = source.requiredString("startTime").toTtcInstant(),
        end = source.requiredString("endTime").toTtcInstant(),
        mode = mode,
        route = when (val value = source["route"]) {
            null,
            JsonNull,
            -> null
            else -> parseTtcRoute(value)
        },
        realtime = source.requiredBoolean("realTime"),
        distanceMeters = source.requiredNumber("distance").takeIf { it >= 0.0 } ?: invalidNormalizedTtcResponse(),
        intermediateStops = intermediate,
    )
}

private fun JsonObject.toTtcPlanPlace(): TtcPlanPlace = TtcPlanPlace(
    name = requiredString("name"),
    latitude = requiredNumber("lat").validLatitude(),
    longitude = requiredNumber("lon").validLongitude(),
)

private fun parseTtcPlanStop(element: JsonElement): TtcPlanStop {
    val source = element.asObject()
    source.requiredString("code")
    source.requiredString("name")
    if (source.requiredString("vehicleMode") != "BUS") invalidNormalizedTtcResponse()
    return TtcPlanStop(
        rawId = source.requiredString("id"),
        latitude = source.requiredNumber("lat").validLatitude(),
        longitude = source.requiredNumber("lon").validLongitude(),
    )
}

private fun Double.validLatitude(): Double = takeIf { it in -90.0..90.0 } ?: invalidNormalizedTtcResponse()
private fun Double.validLongitude(): Double = takeIf { it in -180.0..180.0 } ?: invalidNormalizedTtcResponse()
private fun Double.minutesValue(): Int =
    takeIf { it >= 0.0 && it <= 1_440.0 && it == kotlin.math.floor(it) }?.toInt() ?: invalidNormalizedTtcResponse()
private fun Double.durationValue(): Long =
    takeIf { it > 0.0 && it <= 86_400.0 && it == kotlin.math.floor(it) }?.toLong() ?: invalidNormalizedTtcResponse()
private fun String.toTtcInstant(): Instant = try {
    Instant.parse(this)
} catch (_: Exception) {
    invalidNormalizedTtcResponse()
}
