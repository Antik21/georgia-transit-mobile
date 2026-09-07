package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.AttributionLink
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.config.TransitousActivationConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.max

private const val TransitousCityId = "tbilisi"
private const val TransitousProvider = "transitous"
private const val TransitousMaximumArrivals = 20
private const val TransitousMaximumTransfers = 3
private const val TransitousMaximumItineraries = 4
private const val TransitousRequestTimeoutMillis = 3_500L
private const val TransitousConnectTimeoutMillis = 2_000L
private const val TransitousMaximumRetries = 2
private const val TransitousMaximumRetryAfterSeconds = 2
private const val TransitousLastKnownGoodMillis = 10 * 60 * 1_000L
private const val TransitousMaximumPlanPastSeconds = 5 * 60L
private const val TransitousMaximumPlanAdvanceSeconds = 24 * 60 * 60L
private const val TransitousRoutingRadiusMeters = 500
private const val TransitousMaximumResponseBytes = 256 * 1024
private const val TransitousMaximumDiscoveredStopIds = 128
/** Full itineraries are preserved; reject only an implausibly large response rather than truncating it. */
private const val TransitousMaximumLegsPerItinerary = 24
private const val TransitousMaximumUpstreamConcurrentRequests = 2
private const val TransitousMaximumUpstreamRequestsPerMinute = 24
private const val TransitousUpstreamRateWindowMillis = 60_000L

private val TransitousResponseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
private val TransitousTransitModes = setOf(
    "TRANSIT",
    "TRAM",
    "SUBWAY",
    "FERRY",
    "AIRPLANE",
    "BUS",
    "COACH",
    "RAIL",
    "HIGHSPEED_RAIL",
    "LONG_DISTANCE",
    "NIGHT_RAIL",
    "REGIONAL_FAST_RAIL",
    "REGIONAL_RAIL",
    "SUBURBAN",
    "FUNICULAR",
    "AERIAL_LIFT",
    "AREAL_LIFT",
    "METRO",
    "CABLE_CAR",
    "OTHER",
)
/** MOTIS labels these modes as either street or transit; identifiers disambiguate the leg. */
private val TransitousDualUseModes = setOf("ODM", "RIDE_SHARING")
private val TransitousCarModes = setOf("CAR", "CAR_PARKING", "CAR_DROPOFF")
private val TransitousNonTransitModes = setOf(
    "WALK",
    "BIKE",
    "RENTAL",
    "CAR",
    "CAR_PARKING",
    "CAR_DROPOFF",
    "FLEX",
    "DEBUG_BUS_ROUTE",
    "DEBUG_RAILWAY_ROUTE",
    "DEBUG_FERRY_ROUTE",
)

/**
 * Server-only MOTIS v2.10.2 adapter for explicitly activated, best-effort Transitous use.
 *
 * It deliberately implements only `stoptimes` and `plan`. In particular it never calls
 * `/api/v6/map/trips`, whose segments are not a vehicle-position feed and must not be inferred
 * as GPS. The adapter owns upstream JSON, identifiers, URL construction, retries, and caching;
 * no provider shape can cross into the public BFF or shared/mobile modules.
 */
internal class TransitousTransitProviderAdapter(
    private val activation: TransitousActivationConfig,
    private val client: TransitousClient = TransitousClient(activation),
    private val clock: Clock = Clock.systemUTC(),
) : CityTransitProviderAdapter, AutoCloseable {
    init {
        require(activation.isActivated) {
            "Transitous adapter construction requires explicit eligibility and contact activation"
        }
    }
    override val city: City = City(
        id = TransitousCityId,
        name = LocalizedText(ru = "Тбилиси", en = "Tbilisi", ka = "თბილისი"),
        center = GeoPoint(latitude = 41.715_137, longitude = 44.827_096),
        defaultZoom = 12.5,
        capabilities = CityCapabilities(
            routes = false,
            stops = false,
            routeGeometry = false,
            vehiclePositions = false,
            officialArrivals = false,
            tripPlanning = activation.isRoutingApproved,
            arrivals = true,
        ),
        availability = CityAvailability(
            readiness = CityReadiness.PRODUCTION_READY,
            source = CitySource.REVIEWED_ADAPTER,
        ),
        attribution = listOf(
            AttributionLink(
                id = "transitous",
                label = LocalizedText(
                    ru = "Источники Transitous",
                    en = "Transitous sources",
                    ka = "Transitous-ის წყაროები",
                ),
                url = "https://transitous.org/sources/",
            ),
            AttributionLink(
                id = "openstreetmap",
                label = LocalizedText(
                    ru = "© участники OpenStreetMap (ODbL)",
                    en = "© OpenStreetMap contributors (ODbL)",
                    ka = "© OpenStreetMap-ის მონაწილეები (ODbL)",
                ),
                url = "https://www.openstreetmap.org/copyright",
            ),
        ),
    )

    private val stopCatalog = TransitousStopCatalog(activation.approvedStopIds)
    private val arrivalLastKnownGood = LastKnownGood<ArrivalRequestKey, RealtimeArrivals>(
        maximumEntries = 64,
        maximumAgeMillis = TransitousLastKnownGoodMillis,
        clock = clock,
    )
    private val journeyLastKnownGood = LastKnownGood<JourneyRequestKey, RealtimeJourneys>(
        maximumEntries = 32,
        maximumAgeMillis = TransitousLastKnownGoodMillis,
        clock = clock,
    )

    override suspend fun routes(locale: String, mode: String?): List<Route> = unsupported()

    override suspend fun route(routeId: String, locale: String): Route = unsupported()

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> = unsupported()

    override suspend fun shape(routeId: String, directionId: String): Shape = unsupported()

    override suspend fun stopDirectory(locale: String): List<Stop> = unsupported()

    /** There is intentionally no vehicle implementation; map trip segments are not GPS fixes. */
    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles = unsupported()

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        val upstreamStopId = TransitousIds.decodeStopId(stopId)
        val boundedLimit = limit.coerceIn(1, TransitousMaximumArrivals)
        val normalizedLocale = locale.normalizedTransitousLocale()
        stopCatalog.requireAllowed(upstreamStopId)
        val key = ArrivalRequestKey(upstreamStopId, boundedLimit, normalizedLocale)
        return try {
            val requestTime = clock.instant()
            val response = client.stopTimes(
                stopId = upstreamStopId,
                limit = boundedLimit,
                language = normalizedLocale,
                time = requestTime,
            )
            if (!TbilisiBounds.contains(response.place) || !response.place.hasExactStopId(upstreamStopId)) {
                throw ProviderBadGateway("The transit provider returned an invalid response")
            }
            val arrivals = response.stopTimes.take(boundedLimit).map { stopTime ->
                if (!TbilisiBounds.contains(stopTime.place) || !stopTime.place.hasExactStopId(upstreamStopId)) {
                    throw ProviderBadGateway("The provider returned an invalid response")
                }
                stopTime.toArrival(stopId)
            }
            val source = arrivals.pageSource()
            NormalizedResponseValidator.arrivalPage(
                cityId = TransitousCityId,
                stopId = stopId,
                page = ArrivalPage(
                    items = arrivals,
                    source = source,
                    observedAt = requestTime.toString(),
                    stale = false,
                ),
            )
            // This timestamp is intentionally taken after the bounded body has fully decoded and
            // its normalized output has been validated, so LKG age means "last good result".
            val observedAt = clock.instant()
            val page = RealtimeArrivals(
                items = arrivals,
                source = source,
                observedAt = observedAt,
                stale = false,
            )
            arrivalLastKnownGood.put(key, page, observedAt)
            page
        } catch (failure: ProviderFailure) {
            arrivalLastKnownGood.stale(key, failure)?.let { return it.copy(stale = true) }
            throw failure
        }
    }

    override suspend fun journeys(query: JourneyQuery): List<Journey> = journeyPage(query).items

    override suspend fun journeyPage(query: JourneyQuery): RealtimeJourneys {
        if (!activation.isRoutingApproved) {
            throw ProviderCapabilityUnavailable("Transitous routing is not approved")
        }
        if (!TbilisiBounds.contains(query.from) || !TbilisiBounds.contains(query.to)) {
            throw ProviderInvalidArgument("Routing coordinates are outside the configured city boundary")
        }
        val requestTime = clock.instant()
        if (
            query.departureAt.isBefore(requestTime.minusSeconds(TransitousMaximumPlanPastSeconds)) ||
            query.departureAt.isAfter(requestTime.plusSeconds(TransitousMaximumPlanAdvanceSeconds))
        ) {
            throw ProviderInvalidArgument("Routing time is outside the configured planning window")
        }
        val normalizedLocale = query.locale.normalizedTransitousLocale()
        val effectiveMaxTransfers = query.maxTransfers.coerceAtMost(TransitousMaximumTransfers)
        val key = JourneyRequestKey(
            from = query.from,
            to = query.to,
            departureAt = query.departureAt,
            locale = normalizedLocale,
            requestedMaxTransfers = query.maxTransfers,
            effectiveMaxTransfers = effectiveMaxTransfers,
        )
        return try {
            val response = client.plan(
                from = query.from,
                to = query.to,
                departureAt = query.departureAt,
                maxTransfers = effectiveMaxTransfers,
                language = normalizedLocale,
            )
            if (response.itineraries.size > TransitousMaximumItineraries) invalidTransitousResponse()
            val journeys = response.itineraries.map { itinerary ->
                itinerary.toJourney(maxTransfers = effectiveMaxTransfers)
            }
            val realtime = journeys.any { it.realtime }
            val source = if (realtime) ArrivalSource.AGGREGATOR_REALTIME else ArrivalSource.SCHEDULE
            NormalizedResponseValidator.journeyPage(
                cityId = TransitousCityId,
                page = JourneyPage(
                    items = journeys.map(MappedJourney::journey),
                    observedAt = requestTime.toString(),
                    source = source,
                    realtime = realtime,
                    stale = false,
                ),
            )
            stopCatalog.registerAll(journeys.flatMap(MappedJourney::rawStopIds).toSet())
            // Do not report the request-start time as observation time: mapping and continuity
            // validation above are part of accepting a successful provider response.
            val observedAt = clock.instant()
            val page = RealtimeJourneys(
                items = journeys.map { it.journey },
                source = source,
                realtime = realtime,
                observedAt = observedAt,
                stale = false,
            )
            journeyLastKnownGood.put(key, page, observedAt)
            page
        } catch (failure: ProviderFailure) {
            journeyLastKnownGood.stale(key, failure)?.let { return it.copy(stale = true) }
            throw failure
        }
    }

    override fun close() = client.close()

    private fun <T> unsupported(): T = throw ProviderCapabilityUnavailable("The capability is not available")
}

private fun TransitousStopTime.toArrival(publicStopId: String): Arrival {
    // `arriveBy=false` requests departure events. Do not label arrival times as departures.
    val scheduledAt = place.scheduledDeparture.toUtcTimestampOrNull()
    val expectedAt = place.departure.toUtcTimestampOrNull()
    val realtime = realTime
    return Arrival(
        stopId = publicStopId,
        routeId = TransitousIds.routeId(routeId.requireNonBlank("route id")),
        tripId = TransitousIds.tripId(tripId.requireNonBlank("trip id")),
        headsign = headsign.requireNonBlank("headsign").asLocalizedText(),
        scheduledAt = scheduledAt,
        expectedAt = expectedAt,
        expectedInMinutes = null,
        realtime = realtime,
        cancelled = cancelled || tripCancelled || place.cancelled == true,
        source = if (realtime) ArrivalSource.AGGREGATOR_REALTIME else ArrivalSource.SCHEDULE,
    )
}

private fun List<Arrival>.pageSource(): ArrivalSource =
    if (any(Arrival::realtime)) ArrivalSource.AGGREGATOR_REALTIME else ArrivalSource.SCHEDULE

private data class MappedJourney(
    val journey: Journey,
    val realtime: Boolean,
    val rawStopIds: Set<String>,
)
private data class MappedJourneySegment(
    val segment: JourneySegment,
    val legacyLeg: JourneyLeg?,
    val realtime: Boolean,
    val rawStopIds: Set<String>,
    val departureAt: Instant,
    val arrivalAt: Instant,
)

private fun TransitousItinerary.toJourney(maxTransfers: Int): MappedJourney {
    if (transfers !in 0..maxTransfers || legs.isEmpty()) invalidTransitousResponse()
    val journeyDeparture = startTime.toUpstreamInstant()
    val journeyArrival = endTime.toUpstreamInstant()
    if (journeyArrival.isBefore(journeyDeparture)) invalidTransitousResponse()
    if (legs.size > TransitousMaximumLegsPerItinerary) invalidTransitousResponse()
    var previousArrival = journeyDeparture
    val mappedSegments = legs.map { leg ->
        val mapped = when {
            leg.isTransit() -> leg.toMappedTransitSegment(journeyDeparture, journeyArrival)
            leg.isKnownNonTransit() -> leg.toMappedNonTransitSegment(journeyDeparture, journeyArrival)
            else -> invalidTransitousResponse()
        }
        if (mapped.departureAt.isBefore(previousArrival)) invalidTransitousResponse()
        previousArrival = mapped.arrivalAt
        mapped
    }
    val journey = Journey(
        id = TransitousIds.journeyId(id.requireNonBlank("itinerary id")),
        departureAt = journeyDeparture.toString(),
        arrivalAt = journeyArrival.toString(),
        transfers = transfers,
        legs = mappedSegments.filter { it.legacyLeg != null }.map { requireNotNull(it.legacyLeg) },
        segments = mappedSegments.map(MappedJourneySegment::segment),
    )
    return MappedJourney(
        journey = journey,
        realtime = mappedSegments.any(MappedJourneySegment::realtime),
        rawStopIds = mappedSegments.flatMap(MappedJourneySegment::rawStopIds).toSet(),
    )
}

private fun TransitousLeg.toMappedTransitSegment(
    journeyDeparture: Instant,
    journeyArrival: Instant,
): MappedJourneySegment {
    val routeId = routeId?.takeIf(String::isNotBlank) ?: invalidTransitousResponse()
    val directionId = directionId?.takeIf(String::isNotBlank) ?: invalidTransitousResponse()
    val fromStopId = from.stopId?.takeIf(String::isNotBlank) ?: invalidTransitousResponse()
    val toStopId = to.stopId?.takeIf(String::isNotBlank) ?: invalidTransitousResponse()
    if (!TbilisiBounds.contains(from) || !TbilisiBounds.contains(to)) invalidTransitousResponse()
    val legDeparture = startTime.toUpstreamInstant()
    val legArrival = endTime.toUpstreamInstant()
    if (
        legArrival.isBefore(legDeparture) || legDeparture.isBefore(journeyDeparture) ||
        legArrival.isAfter(journeyArrival)
    ) {
        invalidTransitousResponse()
    }
    val publicRouteId = TransitousIds.routeId(routeId)
    val publicDirectionId = TransitousIds.directionId(directionId)
    val publicFromStopId = TransitousIds.stopId(fromStopId)
    val publicToStopId = TransitousIds.stopId(toStopId)
    return MappedJourneySegment(
        segment = JourneySegment(
            departureAt = legDeparture.toString(),
            arrivalAt = legArrival.toString(),
            mode = JourneySegmentMode.TRANSIT,
            routeId = publicRouteId,
            directionId = publicDirectionId,
            fromStopId = publicFromStopId,
            toStopId = publicToStopId,
            fromPosition = from.toGeoPoint(),
            toPosition = to.toGeoPoint(),
        ),
        legacyLeg = JourneyLeg(
            routeId = publicRouteId,
            directionId = publicDirectionId,
            fromStopId = publicFromStopId,
            toStopId = publicToStopId,
            departureAt = legDeparture.toString(),
            arrivalAt = legArrival.toString(),
        ),
        realtime = realTime,
        rawStopIds = setOf(fromStopId, toStopId),
        departureAt = legDeparture,
        arrivalAt = legArrival,
    )
}

private fun TransitousLeg.toMappedNonTransitSegment(
    journeyDeparture: Instant,
    journeyArrival: Instant,
): MappedJourneySegment {
    // A route or direction on a non-transit leg cannot be represented without mislabelling it.
    if (routeId != null || directionId != null) invalidTransitousResponse()
    if (!TbilisiBounds.contains(from) || !TbilisiBounds.contains(to)) invalidTransitousResponse()
    val legDeparture = startTime.toUpstreamInstant()
    val legArrival = endTime.toUpstreamInstant()
    if (
        legArrival.isBefore(legDeparture) || legDeparture.isBefore(journeyDeparture) ||
        legArrival.isAfter(journeyArrival)
    ) {
        invalidTransitousResponse()
    }
    val rawFromStopId = from.optionalStopId()
    val rawToStopId = to.optionalStopId()
    return MappedJourneySegment(
        segment = JourneySegment(
            departureAt = legDeparture.toString(),
            arrivalAt = legArrival.toString(),
            mode = normalizedSegmentMode(),
            routeId = null,
            directionId = null,
            fromStopId = rawFromStopId?.let(TransitousIds::stopId),
            toStopId = rawToStopId?.let(TransitousIds::stopId),
            fromPosition = from.toGeoPoint(),
            toPosition = to.toGeoPoint(),
        ),
        legacyLeg = null,
        realtime = realTime,
        rawStopIds = listOfNotNull(rawFromStopId, rawToStopId).toSet(),
        departureAt = legDeparture,
        arrivalAt = legArrival,
    )
}

private fun TransitousLeg.isTransit(): Boolean = mode in TransitousTransitModes ||
    (mode in TransitousDualUseModes && (routeId != null || directionId != null))

private fun TransitousLeg.isKnownNonTransit(): Boolean = mode in TransitousNonTransitModes ||
    (mode in TransitousDualUseModes && routeId == null && directionId == null)

private fun TransitousPlace.optionalStopId(): String? = stopId?.also { rawStopId ->
    if (!rawStopId.isReversibleOpaqueValue()) invalidTransitousResponse()
}

/** MOTIS stop IDs are opaque. Never trim, case-fold, or otherwise guess an alias. */
private fun TransitousPlace.hasExactStopId(requestedUpstreamStopId: String): Boolean =
    stopId?.takeIf(String::isNotBlank) == requestedUpstreamStopId

private fun TransitousPlace.toGeoPoint(): GeoPoint = GeoPoint(lat, lon)

private fun TransitousLeg.normalizedSegmentMode(): JourneySegmentMode = when {
    isTransit() -> JourneySegmentMode.TRANSIT
    mode == "WALK" -> JourneySegmentMode.WALK
    mode == "BIKE" -> JourneySegmentMode.BICYCLE
    mode in TransitousCarModes -> JourneySegmentMode.CAR
    else -> JourneySegmentMode.OTHER
}

private fun String?.toUtcTimestampOrNull(): String? = this?.let { value ->
    try {
        Instant.parse(value).toString()
    } catch (_: Exception) {
        throw ProviderBadGateway("The provider returned an invalid response")
    }
}

private fun String.toUpstreamInstant(): Instant = try {
    Instant.parse(this)
} catch (_: Exception) {
    invalidTransitousResponse()
}

private fun String.requireNonBlank(field: String): String = takeIf(String::isNotBlank)
    ?: throw ProviderBadGateway("The provider returned an invalid $field")

private fun String.asLocalizedText(): LocalizedText = LocalizedText(ru = this, en = this, ka = this)

private fun String.normalizedTransitousLocale(): String = lowercase(Locale.ROOT)

private fun invalidTransitousResponse(): Nothing =
    throw ProviderBadGateway("The transit provider returned an invalid response")

private object TransitousIds {
    private const val Prefix = "$TransitousCityId:$TransitousProvider"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun stopId(raw: String): String = publicId("stop", raw)
    fun routeId(raw: String): String = publicId("route", raw)
    fun directionId(raw: String): String = publicId("direction", raw)
    fun tripId(raw: String): String = publicId("trip", raw)
    fun journeyId(raw: String): String = publicId("journey", raw)

    fun decodeStopId(publicId: String): String {
        val prefix = "$Prefix:stop:"
        val encoded = publicId.removePrefix(prefix)
        if (encoded == publicId || encoded.isBlank()) throw ProviderStopNotFound("The requested stop was not found")
        return try {
            decoder.decode(encoded).toString(StandardCharsets.UTF_8).also { raw ->
                if (!raw.isReversibleOpaqueValue() || encoder.encodeToString(raw.toByteArray(StandardCharsets.UTF_8)) != encoded) {
                    throw ProviderStopNotFound("The requested stop was not found")
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw ProviderStopNotFound("The requested stop was not found")
        }
    }

    private fun publicId(entity: String, raw: String): String {
        if (!raw.isReversibleOpaqueValue()) invalidTransitousResponse()
        val encoded = encoder.encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        return "$Prefix:$entity:$encoded"
    }
}

private fun String.isReversibleOpaqueValue(): Boolean =
    isNotBlank() && none(Char::isWhitespace) && toByteArray(StandardCharsets.UTF_8).size <= 150

private class TransitousStopCatalog(approvedStopIds: Set<String>) {
    private val approved = approvedStopIds.toSet()
    private val discovered = LinkedHashMap<String, Unit>()
    private val mutex = Mutex()

    init {
        require(approved.all(String::isReversibleOpaqueValue)) { "Invalid approved Transitous stop ID" }
    }

    suspend fun requireAllowed(rawStopId: String) {
        val isAllowed = mutex.withLock { rawStopId in approved || rawStopId in discovered }
        if (!isAllowed) throw ProviderStopNotFound("The requested stop was not found")
    }

    suspend fun registerAll(rawStopIds: Set<String>) = mutex.withLock {
        if (rawStopIds.size > TransitousMaximumDiscoveredStopIds || rawStopIds.any { !it.isReversibleOpaqueValue() }) {
            invalidTransitousResponse()
        }
        rawStopIds.forEach { rawStopId ->
            if (rawStopId !in approved) {
                discovered.remove(rawStopId)
                discovered[rawStopId] = Unit
            }
        }
        while (discovered.size > TransitousMaximumDiscoveredStopIds) {
            discovered.entries.iterator().next().also { discovered.remove(it.key) }
        }
    }
}

private data class ArrivalRequestKey(
    val rawStopId: String,
    val effectiveLimit: Int,
    val normalizedLocale: String,
)

private data class JourneyRequestKey(
    val from: GeoPoint,
    val to: GeoPoint,
    val departureAt: Instant,
    val locale: String,
    val requestedMaxTransfers: Int,
    val effectiveMaxTransfers: Int,
)

private object TbilisiBounds {
    private const val MinimumLatitude = 41.55
    private const val MaximumLatitude = 42.0
    private const val MinimumLongitude = 44.55
    private const val MaximumLongitude = 45.05

    fun contains(point: GeoPoint): Boolean = point.latitude in MinimumLatitude..MaximumLatitude &&
        point.longitude in MinimumLongitude..MaximumLongitude

    fun contains(place: TransitousPlace): Boolean = contains(GeoPoint(place.lat, place.lon))
}

/** A bounded in-memory BFF LKG store; stale is set only after an eligible upstream outage. */
private class LastKnownGood<K, V>(
    private val maximumEntries: Int,
    private val maximumAgeMillis: Long,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>()

    suspend fun put(key: K, value: V, observedAt: Instant) = mutex.withLock {
        entries.remove(key)
        entries[key] = Entry(value, observedAt.toEpochMilli())
        while (entries.size > maximumEntries) entries.entries.iterator().next().also { entries.remove(it.key) }
    }

    suspend fun stale(key: K, failure: ProviderFailure): V? {
        if (failure !is ProviderUnavailable && failure !is ProviderTimeout && failure !is ProviderRateLimited) return null
        return mutex.withLock {
            entries[key]?.takeIf { clock.millis() - it.observedAtMillis in 0..maximumAgeMillis }?.value
        }
    }

    private data class Entry<V>(val value: V, val observedAtMillis: Long)
}

/** Minimal typed subset of the pinned MOTIS OpenAPI v2.10.2 responses. */
@Serializable
internal data class TransitousStopTimesResponse(
    val stopTimes: List<TransitousStopTime>,
    val place: TransitousPlace,
)

@Serializable
internal data class TransitousStopTime(
    val place: TransitousPlace,
    val realTime: Boolean,
    val headsign: String,
    val tripId: String,
    val routeId: String,
    val cancelled: Boolean,
    val tripCancelled: Boolean,
)

@Serializable
internal data class TransitousPlanResponse(val itineraries: List<TransitousItinerary>)

@Serializable
internal data class TransitousItinerary(
    val startTime: String,
    val endTime: String,
    val transfers: Int,
    val id: String,
    val legs: List<TransitousLeg>,
)

@Serializable
internal data class TransitousLeg(
    val mode: String,
    val from: TransitousPlace,
    val to: TransitousPlace,
    val startTime: String,
    val endTime: String,
    val realTime: Boolean,
    val routeId: String? = null,
    val directionId: String? = null,
)

@Serializable
internal data class TransitousPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val stopId: String? = null,
    val departure: String? = null,
    val scheduledDeparture: String? = null,
    val cancelled: Boolean? = null,
)

/** Typed HTTP client with bounded load, redacted errors, and no provider payload logging. */
internal class TransitousClient(
    private val activation: TransitousActivationConfig,
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = TransitousConnectTimeoutMillis
            requestTimeoutMillis = TransitousRequestTimeoutMillis
            socketTimeoutMillis = TransitousRequestTimeoutMillis
        }
    },
) : AutoCloseable {
    init {
        require(activation.isActivated) { "Transitous client requires explicit activation" }
    }

    suspend fun stopTimes(stopId: String, limit: Int, language: String, time: Instant): TransitousStopTimesResponse =
        get(
            endpoint = "stoptimes",
            query = listOf(
                "stopId" to stopId,
                "time" to time.toString(),
                "arriveBy" to "false",
                "direction" to "LATER",
                "n" to limit.toString(),
                "mode" to "TRANSIT",
                "exactRadius" to "true",
                "language" to language,
            ),
            missing = { ProviderStopNotFound("The requested stop was not found") },
        )

    suspend fun plan(
        from: GeoPoint,
        to: GeoPoint,
        departureAt: Instant,
        maxTransfers: Int,
        language: String,
    ): TransitousPlanResponse = get(
        endpoint = "plan",
        query = listOf(
            "fromPlace" to "${from.latitude},${from.longitude}",
            "toPlace" to "${to.latitude},${to.longitude}",
            "radius" to TransitousRoutingRadiusMeters.toString(),
            "time" to departureAt.toString(),
            "arriveBy" to "false",
            "maxTransfers" to maxTransfers.toString(),
            "maxTravelTime" to "120",
            "searchWindow" to "600",
            "maxPreTransitTime" to "900",
            "maxPostTransitTime" to "900",
            "maxDirectTime" to "0",
            "directModes" to "",
            "preTransitModes" to "WALK",
            "postTransitModes" to "WALK",
            "detailedLegs" to "false",
            "detailedTransfers" to "false",
            "numItineraries" to TransitousMaximumItineraries.toString(),
            "maxItineraries" to TransitousMaximumItineraries.toString(),
            "numLegAlternatives" to "0",
            "timeout" to "3",
            "language" to language,
        ),
        missing = { ProviderUnavailable("The transit provider is unavailable") },
    )

    override fun close() = httpClient.close()

    private suspend inline fun <reified T> get(
        endpoint: String,
        query: List<Pair<String, String>>,
        crossinline missing: () -> ProviderFailure,
    ): T {
        var retry = 0
        while (true) {
            try {
                when (
                    val attempt: TransitousAttempt<T> = TransitousUpstreamBudget.execute {
                        val response = httpClient.get {
                            url(requireNotNull(activation.baseUrl).toString())
                            url {
                                appendPathSegments("api", "v6", endpoint)
                                query.forEach { (name, value) -> parameters.append(name, value) }
                            }
                            header(HttpHeaders.UserAgent, activation.userAgent())
                            timeout {
                                connectTimeoutMillis = TransitousConnectTimeoutMillis
                                requestTimeoutMillis = TransitousRequestTimeoutMillis
                                socketTimeoutMillis = TransitousRequestTimeoutMillis
                            }
                        }
                        if (response.status.value in RetryableStatusCodes && retry < TransitousMaximumRetries) {
                            val retryAfterSeconds = response.retryAfterSeconds()
                            if (
                                response.status == HttpStatusCode.TooManyRequests &&
                                retryAfterSeconds > TransitousMaximumRetryAfterSeconds
                            ) {
                                response.call.cancel()
                                throw ProviderRateLimited("The transit provider is rate limited", retryAfterSeconds)
                            }
                            response.call.cancel()
                            TransitousAttempt.Retry(retryDelayMillis(retry + 1, retryAfterSeconds))
                        } else {
                            TransitousAttempt.Complete<T>(response.decode(missing))
                        }
                    }
                ) {
                    is TransitousAttempt.Complete -> return attempt.value
                    is TransitousAttempt.Retry -> {
                        retry += 1
                        delay(attempt.delayMillis)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ProviderFailure) {
                throw failure
            } catch (failure: Throwable) {
                if (failure.isRetryableTransport() && retry < TransitousMaximumRetries) {
                    retry += 1
                    delay(retryDelayMillis(retry, null))
                    continue
                }
                throw if (failure.isTimeoutTransport()) {
                    ProviderTimeout("The transit provider did not respond in time")
                } else {
                    ProviderUnavailable("The transit provider is unavailable")
                }
            }
        }
    }

    private suspend inline fun <reified T> HttpResponse.decode(missing: () -> ProviderFailure): T = when {
        status == HttpStatusCode.OK -> try {
            TransitousResponseJson.decodeFromString<T>(readBodyWithinLimit())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            throw ProviderBadGateway("The transit provider returned an invalid response")
        }
        status == HttpStatusCode.NotFound -> discardAndThrow(missing())
        status == HttpStatusCode.RequestTimeout -> discardAndThrow(
            ProviderTimeout("The transit provider did not respond in time"),
        )
        status == HttpStatusCode.TooManyRequests -> discardAndThrow(
            ProviderRateLimited("The transit provider is rate limited", retryAfterSeconds()),
        )
        status.value in 500..599 -> discardAndThrow(
            ProviderUnavailable("The transit provider is unavailable"),
        )
        status == HttpStatusCode.BadRequest || status == HttpStatusCode.UnprocessableEntity -> discardAndThrow(
            ProviderInvalidArgument("The transit provider rejected the request"),
        )
        else -> discardAndThrow(ProviderBadGateway("The transit provider returned an invalid response"))
    }

    private suspend fun HttpResponse.readBodyWithinLimit(): String {
        val advertisedLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (advertisedLength != null && advertisedLength > TransitousMaximumResponseBytes) {
            call.cancel()
            invalidTransitousResponse()
        }
        val channel = bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (count == 0) continue
            if (output.size() > TransitousMaximumResponseBytes - count) {
                call.cancel()
                invalidTransitousResponse()
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun HttpResponse.discardAndThrow(failure: ProviderFailure): Nothing {
        // No upstream error body is decoded, logged, or forwarded.
        call.cancel()
        throw failure
    }

    private fun HttpResponse.retryAfterSeconds(): Int {
        val value = headers[HttpHeaders.RetryAfter] ?: return 1
        return value.toIntOrNull()?.takeIf { it in 1..86_400 }
            ?: runCatching {
                Duration.between(
                    Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                ).seconds.coerceIn(1L, 86_400L).toInt()
            }.getOrDefault(1)
    }

    private fun retryDelayMillis(retry: Int, retryAfterSeconds: Int?): Long {
        val exponential = 250L * (1L shl (retry - 1))
        val retryAfter = (retryAfterSeconds ?: 0).coerceAtMost(TransitousMaximumRetryAfterSeconds) * 1_000L
        return max(exponential, retryAfter)
    }

    private fun Throwable.isRetryableTransport(): Boolean =
        this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException || this is IOException

    private fun Throwable.isTimeoutTransport(): Boolean =
        this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

    private companion object {
        val RetryableStatusCodes = setOf(408, 429, 500, 502, 503, 504)
    }
}

private sealed interface TransitousAttempt<out T> {
    data class Complete<T>(val value: T) : TransitousAttempt<T>
    data class Retry(val delayMillis: Long) : TransitousAttempt<Nothing>
}

/** Shared JVM-wide, fail-fast budget for every Transitous HTTP attempt, including retries. */
private object TransitousUpstreamBudget {
    private val permits = Semaphore(TransitousMaximumUpstreamConcurrentRequests)
    private val starts = ArrayDeque<Long>()
    private val mutex = Mutex()

    suspend fun <T> execute(block: suspend () -> T): T {
        if (!permits.tryAcquire()) {
            throw ProviderRateLimited("The transit provider is rate limited", 1)
        }
        try {
            reserveRequestStart()
            return block()
        } finally {
            permits.release()
        }
    }

    private suspend fun reserveRequestStart() = mutex.withLock {
        val now = System.currentTimeMillis()
        while (starts.firstOrNull()?.let { now - it >= TransitousUpstreamRateWindowMillis } == true) {
            starts.removeFirst()
        }
        if (starts.size >= TransitousMaximumUpstreamRequestsPerMinute) {
            val retryAfterSeconds = ceil(
                (starts.first() + TransitousUpstreamRateWindowMillis - now) / 1_000.0,
            ).toInt().coerceIn(1, 60)
            throw ProviderRateLimited("The transit provider is rate limited", retryAfterSeconds)
        }
        starts.addLast(now)
    }
}
