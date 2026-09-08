package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.domain.model.ArrivalPage
import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityAvailability
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.CityReadiness
import com.denis.georgiatransit.shared.domain.model.CitySource
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitArrival
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneyLeg
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.TripId
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.model.WalkingEstimate
import kotlin.time.Instant

/** Mapping failures are converted into typed [TransitFailure.Serialization] at the client edge. */
internal fun CityDto.toDomain(): TransitCity {
    require(!capabilities.officialArrivals || capabilities.arrivals) {
        "officialArrivals requires generic arrivals"
    }
    return TransitCity(
        id = CityId(id.requireCityId()),
        name = name.en,
        center = center.toDomain(),
        capabilities = CityCapabilities(
            stops = capabilities.stops,
            vehicles = capabilities.vehiclePositions,
            arrivals = capabilities.arrivals,
            routeShapes = capabilities.routeGeometry,
            journeyPlanning = capabilities.tripPlanning,
            routes = capabilities.routes,
            officialArrivals = capabilities.officialArrivals,
        ),
        localizedName = name.toDomain(),
        defaultZoom = defaultZoom.requireFinite("defaultZoom"),
        availability = availability.toDomain(),
        attribution = attribution.map(AttributionLinkDto::toDomain),
    )
}

internal fun RouteDto.toDomain(cityId: CityId): TransitRoute = TransitRoute(
    id = RouteId(id.requirePublicId("route id")),
    cityId = cityId,
    shortName = shortName,
    name = longName.en,
    colorArgb = color.toArgb(),
    providerId = ProviderId(providerId),
    longName = longName.toDomain(),
    textColorArgb = textColor.toArgb(),
    mode = mode.toDomain(),
    directions = directions.map { it.toDomain() },
)

internal fun DirectionDto.toDomain() = TransitDirection(
    id = DirectionId(id.requirePublicId("direction id")),
    name = name.toDomain(),
    headsign = headsign.toDomain(),
)

internal fun StopDto.toDomain() = TransitStop(
    id = StopId(id.requirePublicId("stop id")),
    providerId = ProviderId(providerId),
    code = code,
    name = name.toDomain(),
    position = position.toDomain(),
    routeIds = routeIds.map { RouteId(it.requirePublicId("route id")) },
    mode = mode.toDomain(),
)

internal fun ShapeDto.toDomain() = TransitShape(
    encodedPolyline = value,
    precision = precision,
    updatedAt = updatedAt.toUtcInstant("shape updatedAt"),
).also { require(format == "encoded_polyline") { "shape format is invalid" } }

internal fun VehiclePageDto.toDomain() = VehiclePage(
    items = items.map { it.toDomain() },
    observedAt = observedAt.toUtcInstant("vehicle page observedAt"),
    maxAgeSeconds = maxAgeSeconds.also { require(it >= 0) { "maxAgeSeconds is invalid" } },
    stale = stale,
)

internal fun VehicleDto.toDomain() = TransitVehicle(
    id = VehicleId(id.requirePublicId("vehicle id")),
    routeId = RouteId(routeId.requirePublicId("route id")),
    directionId = directionId?.let { DirectionId(it.requirePublicId("direction id")) },
    position = position.toDomain(),
    bearing = bearing?.requireFinite("bearing")?.also { require(it in 0.0..360.0) { "bearing is invalid" } },
    nextStopId = nextStopId?.let { StopId(it.requirePublicId("stop id")) },
    observedAt = observedAt?.toUtcInstant("vehicle observedAt"),
    ageSeconds = ageSeconds?.also { require(it >= 0) { "ageSeconds is invalid" } },
    positionKind = positionKind.toDomain(),
)

internal fun ArrivalPageDto.toDomain() = ArrivalPage(
    items = items.map { it.toDomain() },
    source = source.toDomain(),
    observedAt = observedAt.toUtcInstant("arrival page observedAt"),
    stale = stale,
)

internal fun ArrivalDto.toDomain() = TransitArrival(
    stopId = StopId(stopId.requirePublicId("stop id")),
    routeId = RouteId(routeId.requirePublicId("route id")),
    tripId = tripId?.let(::TripId),
    headsign = headsign.toDomain(),
    scheduledAt = scheduledAt?.toUtcInstant("scheduledAt"),
    expectedAt = expectedAt?.toUtcInstant("expectedAt"),
    expectedInMinutes = expectedInMinutes?.also { require(it >= 0) { "expectedInMinutes is invalid" } },
    realtime = realtime,
    cancelled = cancelled,
    source = source.toDomain(),
)

internal fun JourneyPageDto.toDomain(): JourneyPage {
    require(source == ArrivalSourceDto.AggregatorRealtime || source == ArrivalSourceDto.Schedule) {
        "journey page source is invalid"
    }
    require((source == ArrivalSourceDto.AggregatorRealtime) == realtime) {
        "journey page realtime metadata is inconsistent"
    }
    return JourneyPage(
        items = items.map { it.toDomain() },
        observedAt = observedAt.toUtcInstant("journey page observedAt"),
        source = source.toDomain(),
        realtime = realtime,
        stale = stale,
    )
}
internal fun JourneyDto.toDomain(): TransitJourney {
    val journeyDeparture = departureAt.toUtcInstant("journey departureAt")
    val journeyArrival = arrivalAt.toUtcInstant("journey arrivalAt")
    require(journeyArrival >= journeyDeparture) { "journey times are invalid" }
    val mappedLegs = legs.map(JourneyLegDto::toDomain)
    val mappedSegments = segments.map(JourneySegmentDto::toDomain)
    require(mappedLegs.isNotEmpty() || mappedSegments.isNotEmpty()) { "journey has no legs or segments" }
    val orderedSegments = mappedSegments.ifEmpty { mappedLegs.map(TransitJourneyLeg::toTransitSegment) }
    var previousArrival = journeyDeparture
    orderedSegments.forEach { segment ->
        require(segment.departureAt >= journeyDeparture && segment.arrivalAt <= journeyArrival) {
            "journey segment is outside journey bounds"
        }
        require(segment.arrivalAt >= segment.departureAt) { "journey segment times are invalid" }
        require(segment.departureAt >= previousArrival) { "journey segments are not continuous" }
        previousArrival = segment.arrivalAt
    }
    if (mappedSegments.isNotEmpty()) {
        require(mappedLegs == mappedSegments.filter { it.mode == JourneySegmentMode.Transit }.map { it.toLegacyLeg() }) {
            "legacy journey legs do not match transit segments"
        }
    }
    return TransitJourney(
        id = JourneyId(id.requirePublicId("journey id")),
        departureAt = journeyDeparture,
        arrivalAt = journeyArrival,
        transfers = transfers.also { require(it in 0..6) { "transfers is invalid" } },
        legs = mappedLegs,
        segments = orderedSegments,
    )
}

internal fun JourneyLegDto.toDomain(): TransitJourneyLeg = TransitJourneyLeg(
    routeId = RouteId(routeId.requirePublicId("route id")),
    directionId = DirectionId(directionId.requirePublicId("direction id")),
    fromStopId = StopId(fromStopId.requirePublicId("stop id")),
    toStopId = StopId(toStopId.requirePublicId("stop id")),
    departureAt = departureAt.toUtcInstant("leg departureAt"),
    arrivalAt = arrivalAt.toUtcInstant("leg arrivalAt"),
)

internal fun JourneySegmentDto.toDomain(): TransitJourneySegment {
    require((routeId != null) == (directionId != null)) { "journey segment route metadata is inconsistent" }
    val normalizedMode = mode.toDomain()
    if (normalizedMode == JourneySegmentMode.Transit) {
        require(routeId != null && directionId != null && fromStopId != null && toStopId != null) {
            "transit journey segment is incomplete"
        }
    } else {
        require(routeId == null && directionId == null) { "non-transit journey segment has route metadata" }
    }
    return TransitJourneySegment(
        departureAt = departureAt.toUtcInstant("segment departureAt"),
        arrivalAt = arrivalAt.toUtcInstant("segment arrivalAt"),
        mode = normalizedMode,
        routeId = routeId?.let { RouteId(it.requirePublicId("route id")) },
        directionId = directionId?.let { DirectionId(it.requirePublicId("direction id")) },
        fromStopId = fromStopId?.let { StopId(it.requirePublicId("stop id")) },
        toStopId = toStopId?.let { StopId(it.requirePublicId("stop id")) },
        fromPosition = fromPosition?.toDomain(),
        toPosition = toPosition?.toDomain(),
    )
}

private fun JourneySegmentModeDto.toDomain(): JourneySegmentMode = when (this) {
    JourneySegmentModeDto.Transit -> JourneySegmentMode.Transit
    JourneySegmentModeDto.Walk -> JourneySegmentMode.Walk
    JourneySegmentModeDto.Bicycle -> JourneySegmentMode.Bicycle
    JourneySegmentModeDto.Car -> JourneySegmentMode.Car
    JourneySegmentModeDto.Other -> JourneySegmentMode.Other
}

private fun TransitJourneyLeg.toTransitSegment(): TransitJourneySegment = TransitJourneySegment(
    departureAt = departureAt,
    arrivalAt = arrivalAt,
    mode = JourneySegmentMode.Transit,
    routeId = routeId,
    directionId = directionId,
    fromStopId = fromStopId,
    toStopId = toStopId,
)

private fun TransitJourneySegment.toLegacyLeg(): TransitJourneyLeg {
    require(mode == JourneySegmentMode.Transit) { "segment is not transit" }
    return TransitJourneyLeg(
        routeId = requireNotNull(routeId) { "transit segment routeId is missing" },
        directionId = requireNotNull(directionId) { "transit segment directionId is missing" },
        fromStopId = requireNotNull(fromStopId) { "transit segment fromStopId is missing" },
        toStopId = requireNotNull(toStopId) { "transit segment toStopId is missing" },
        departureAt = departureAt,
        arrivalAt = arrivalAt,
    )
}

internal fun ErrorDto.toFailure() = when (code) {
    ErrorCodeDto.InvalidArgument -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.InvalidArgument(message, requestId)
    ErrorCodeDto.CityNotFound -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.CityNotFound(message, requestId)
    ErrorCodeDto.RouteNotFound -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.RouteNotFound(message, requestId)
    ErrorCodeDto.StopNotFound -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.StopNotFound(message, requestId)
    ErrorCodeDto.ProviderIdChanged -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.ProviderIdChanged(message, requestId)
    ErrorCodeDto.RateLimited -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.RateLimited(message, retryAfterSeconds.validRetryAfter(), requestId)
    ErrorCodeDto.Internal -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.Internal(message, requestId)
    ErrorCodeDto.CapabilityUnavailable -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.CapabilityUnavailable(message, requestId)
    ErrorCodeDto.UpstreamBadResponse -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.UpstreamBadResponse(message, requestId)
    ErrorCodeDto.UpstreamUnavailable -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.UpstreamUnavailable(message, retryAfterSeconds.validRetryAfter(), requestId)
    ErrorCodeDto.UpstreamTimeout -> com.denis.georgiatransit.shared.domain.repository.TransitFailure.UpstreamTimeout(message, requestId)
}

private fun LocalizedTextDto.toDomain() = LocalizedText(ru, en, ka)
private fun AttributionLinkDto.toDomain() = TransitAttribution(
    id = id.also { require(AttributionIdPattern.matches(it)) { "attribution id is invalid" } },
    label = label.toDomain(),
    url = url.also { require(UrlPattern.matches(it)) { "attribution url is invalid" } },
)
private fun GeoPointDto.toDomain() = GeoPoint(
    latitude = latitude.requireFinite("latitude").also { require(it in -90.0..90.0) { "latitude is invalid" } },
    longitude = longitude.requireFinite("longitude").also { require(it in -180.0..180.0) { "longitude is invalid" } },
)

internal fun GeoPoint.toDto() = GeoPointDto(latitude = latitude, longitude = longitude)

internal fun WalkingEstimateDto.toDomain() = WalkingEstimate(
    distanceMeters = distanceMeters.requireFinite("walking distance").also {
        require(it >= 0.0) { "walking distance is invalid" }
    },
    durationSeconds = durationSeconds.also { require(it > 0L) { "walking duration is invalid" } },
    observedAt = observedAt.toUtcInstant("walking observedAt"),
)
private fun CityAvailabilityDto.toDomain() = CityAvailability(readiness.toDomain(), source.toDomain())
private fun CityReadinessDto.toDomain() = when (this) {
    CityReadinessDto.DevelopmentFixture -> CityReadiness.DevelopmentFixture
    CityReadinessDto.ProductionReady -> CityReadiness.ProductionReady
    CityReadinessDto.Unreviewed -> CityReadiness.Unreviewed
}
private fun CitySourceDto.toDomain() = when (this) {
    CitySourceDto.Fixture -> CitySource.Fixture
    CitySourceDto.ReviewedAdapter -> CitySource.ReviewedAdapter
    CitySourceDto.UnreviewedAdapter -> CitySource.UnreviewedAdapter
}
private fun TransportModeDto.toDomain() = when (this) {
    TransportModeDto.Bus -> TransitMode.Bus
    TransportModeDto.Metro -> TransitMode.Metro
    TransportModeDto.Tram -> TransitMode.Tram
    TransportModeDto.Ferry -> TransitMode.Ferry
}
private fun PositionKindDto.toDomain() = when (this) {
    PositionKindDto.Gps -> VehiclePositionKind.Gps
    PositionKindDto.Estimated -> VehiclePositionKind.Estimated
    PositionKindDto.Unknown -> VehiclePositionKind.Unknown
}
private fun ArrivalSourceDto.toDomain() = when (this) {
    ArrivalSourceDto.OfficialRealtime -> ArrivalSource.OfficialRealtime
    ArrivalSourceDto.AggregatorRealtime -> ArrivalSource.AggregatorRealtime
    ArrivalSourceDto.Schedule -> ArrivalSource.Schedule
    ArrivalSourceDto.ClientEstimate -> ArrivalSource.ClientEstimate
}
private fun String.toArgb(): Long {
    require(matches(Regex("^#[0-9A-Fa-f]{6}$"))) { "color is invalid" }
    return (0xFF000000L or substring(1).toLong(16))
}
private fun String.toUtcInstant(field: String): Instant {
    require(endsWith("Z")) { "$field must be UTC" }
    return Instant.parse(this)
}
private fun String.requireCityId(): String {
    require(CityIdPattern.matches(this)) { "city id is invalid" }
    return this
}

/** Validates the BFF contract without splitting, deriving, or otherwise interpreting the ID. */
private fun String.requirePublicId(field: String): String {
    require(length <= MaxPublicIdLength && PublicIdPattern.matches(this)) { "$field is invalid" }
    return this
}
private fun Double.requireFinite(field: String): Double {
    require(isFinite()) { "$field is invalid" }
    return this
}
private fun Int?.validRetryAfter(): Int? = takeIf { it != null && it in 1..86_400 }

private const val MaxPublicIdLength = 256
private val CityIdPattern = Regex("^[a-z][a-z0-9-]{1,31}$")
private val PublicIdPattern = Regex("^[^:\\s]+:[^:\\s]+:[^:\\s]+:[^:\\s]+$")
private val AttributionIdPattern = Regex("^[a-z][a-z0-9-]{0,31}$")
private val UrlPattern = Regex("^https://[^\\s/@?#]+(?::[0-9]{1,5})?(?:/[^\\s#]*)?$")
