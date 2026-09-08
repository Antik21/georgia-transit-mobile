package com.denis.georgiatransit.shared.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport-only types for the published normalized Transit BFF OpenAPI. */
@Serializable
internal data class CityDto(
    val id: String,
    val name: LocalizedTextDto,
    val center: GeoPointDto,
    val defaultZoom: Double,
    val capabilities: CityCapabilitiesDto,
    val availability: CityAvailabilityDto,
    val attribution: List<AttributionLinkDto> = emptyList(),
)

@Serializable internal data class LocalizedTextDto(val ru: String, val en: String, val ka: String)
@Serializable internal data class GeoPointDto(val latitude: Double, val longitude: Double)

/** POST body for the request-scoped walking endpoint. It is never persisted by the data layer. */
@Serializable
internal data class WalkingEstimateRequestDto(
    val from: GeoPointDto,
    val to: GeoPointDto,
    val locale: String,
)

@Serializable
internal data class WalkingEstimateDto(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val observedAt: String,
)

@Serializable
internal data class CityCapabilitiesDto(
    val routes: Boolean,
    val stops: Boolean,
    val routeGeometry: Boolean,
    val vehiclePositions: Boolean,
    val officialArrivals: Boolean,
    val tripPlanning: Boolean,
    val arrivals: Boolean = officialArrivals,
)

@Serializable
internal data class AttributionLinkDto(
    val id: String,
    val label: LocalizedTextDto,
    val url: String,
)

@Serializable
internal data class CityAvailabilityDto(val readiness: CityReadinessDto, val source: CitySourceDto)

@Serializable
internal enum class CityReadinessDto {
    @SerialName("DEVELOPMENT_FIXTURE") DevelopmentFixture,
    @SerialName("PRODUCTION_READY") ProductionReady,
    @SerialName("UNREVIEWED") Unreviewed,
}

@Serializable
internal enum class CitySourceDto {
    @SerialName("FIXTURE") Fixture,
    @SerialName("REVIEWED_ADAPTER") ReviewedAdapter,
    @SerialName("UNREVIEWED_ADAPTER") UnreviewedAdapter,
}

@Serializable internal data class DirectionDto(val id: String, val name: LocalizedTextDto, val headsign: LocalizedTextDto)

@Serializable
internal data class RouteDto(
    val id: String,
    val providerId: String,
    val shortName: String,
    val longName: LocalizedTextDto,
    val color: String,
    val textColor: String,
    val mode: TransportModeDto,
    val directions: List<DirectionDto>,
)

@Serializable
internal data class StopDto(
    val id: String,
    val providerId: String,
    val code: String,
    val name: LocalizedTextDto,
    val position: GeoPointDto,
    val routeIds: List<String>,
    val mode: TransportModeDto,
)

@Serializable
internal data class ShapeDto(val format: String, val precision: Int, val value: String, val updatedAt: String)

@Serializable
internal enum class TransportModeDto {
    @SerialName("bus") Bus,
    @SerialName("metro") Metro,
    @SerialName("tram") Tram,
    @SerialName("ferry") Ferry,
}

@Serializable
internal enum class PositionKindDto {
    @SerialName("GPS") Gps,
    @SerialName("ESTIMATED") Estimated,
    @SerialName("UNKNOWN") Unknown,
}

@Serializable
internal data class VehicleDto(
    val id: String,
    val routeId: String,
    val directionId: String? = null,
    val position: GeoPointDto,
    val bearing: Double? = null,
    val nextStopId: String? = null,
    val observedAt: String? = null,
    val ageSeconds: Int? = null,
    val positionKind: PositionKindDto,
)

@Serializable
internal data class VehiclePageDto(
    val items: List<VehicleDto>,
    val observedAt: String,
    val maxAgeSeconds: Int,
    val stale: Boolean,
)

@Serializable
internal enum class ArrivalSourceDto {
    @SerialName("OFFICIAL_REALTIME") OfficialRealtime,
    @SerialName("AGGREGATOR_REALTIME") AggregatorRealtime,
    @SerialName("SCHEDULE") Schedule,
    @SerialName("CLIENT_ESTIMATE") ClientEstimate,
}

@Serializable
internal data class ArrivalDto(
    val stopId: String,
    val routeId: String,
    val tripId: String? = null,
    val headsign: LocalizedTextDto,
    val scheduledAt: String? = null,
    val expectedAt: String? = null,
    val expectedInMinutes: Int? = null,
    val realtime: Boolean,
    val cancelled: Boolean,
    val source: ArrivalSourceDto,
)

@Serializable
internal data class ArrivalPageDto(
    val items: List<ArrivalDto>,
    val source: ArrivalSourceDto,
    val observedAt: String,
    val stale: Boolean,
)

@Serializable
internal data class JourneyLegDto(
    val routeId: String,
    val directionId: String,
    val fromStopId: String,
    val toStopId: String,
    val departureAt: String,
    val arrivalAt: String,
)

@Serializable
internal enum class JourneySegmentModeDto {
    @SerialName("TRANSIT") Transit,
    @SerialName("WALK") Walk,
    @SerialName("BICYCLE") Bicycle,
    @SerialName("CAR") Car,
    @SerialName("OTHER") Other,
}

@Serializable
internal data class JourneySegmentDto(
    val departureAt: String,
    val arrivalAt: String,
    val mode: JourneySegmentModeDto,
    val routeId: String? = null,
    val directionId: String? = null,
    val fromStopId: String? = null,
    val toStopId: String? = null,
    val fromPosition: GeoPointDto? = null,
    val toPosition: GeoPointDto? = null,
)

@Serializable
internal data class JourneyDto(
    val id: String,
    val departureAt: String,
    val arrivalAt: String,
    val transfers: Int,
    val legs: List<JourneyLegDto>,
    val segments: List<JourneySegmentDto> = emptyList(),
)

@Serializable
internal data class JourneyPageDto(
    val items: List<JourneyDto>,
    val observedAt: String,
    val source: ArrivalSourceDto = ArrivalSourceDto.Schedule,
    val realtime: Boolean = false,
    val stale: Boolean = false,
)

@Serializable internal data class ErrorEnvelopeDto(val error: ErrorDto)

@Serializable
internal data class ErrorDto(
    val code: ErrorCodeDto,
    val message: String,
    val retryAfterSeconds: Int? = null,
    val requestId: String,
)

@Serializable
internal enum class ErrorCodeDto {
    @SerialName("INVALID_ARGUMENT") InvalidArgument,
    @SerialName("CITY_NOT_FOUND") CityNotFound,
    @SerialName("ROUTE_NOT_FOUND") RouteNotFound,
    @SerialName("STOP_NOT_FOUND") StopNotFound,
    @SerialName("PROVIDER_ID_CHANGED") ProviderIdChanged,
    @SerialName("RATE_LIMITED") RateLimited,
    @SerialName("INTERNAL_ERROR") Internal,
    @SerialName("CAPABILITY_NOT_AVAILABLE") CapabilityUnavailable,
    @SerialName("UPSTREAM_BAD_RESPONSE") UpstreamBadResponse,
    @SerialName("UPSTREAM_UNAVAILABLE") UpstreamUnavailable,
    @SerialName("UPSTREAM_TIMEOUT") UpstreamTimeout,
}
