package com.denis.georgiatransit.bff.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class LocalizedText(
    val ru: String,
    val en: String,
    val ka: String,
)

@Serializable
data class CityCapabilities(
    val routes: Boolean,
    val stops: Boolean,
    val routeGeometry: Boolean,
    val vehiclePositions: Boolean,
    val officialArrivals: Boolean,
    val tripPlanning: Boolean,
    /**
     * Arrivals may be supplied by a reviewed non-official schedule/realtime source. Keep this
     * separate from [officialArrivals] so a client never labels aggregated data as official.
     */
    val arrivals: Boolean = officialArrivals,
)

/**
 * A normalized statement about whether a city can be used outside local fixture development.
 * It deliberately contains no provider name, URL, identifier, credential, or implementation
 * detail. `DEVELOPMENT_FIXTURE` must never be treated as production transit readiness.
 */
@Serializable
enum class CityReadiness {
    DEVELOPMENT_FIXTURE,
    PRODUCTION_READY,
    UNREVIEWED,
}

/**
 * The normalized class of data source behind a city. A reviewed adapter is still described only
 * by this class so mobile clients do not become coupled to a particular provider.
 */
@Serializable
enum class CitySource {
    FIXTURE,
    REVIEWED_ADAPTER,
    UNREVIEWED_ADAPTER,
}

@Serializable
data class CityAvailability(
    val readiness: CityReadiness,
    val source: CitySource,
)

/** A provider-neutral, user-visible source/license link for a city. */
@Serializable
data class AttributionLink(
    val id: String,
    val label: LocalizedText,
    val url: String,
)

@Serializable
data class City(
    val id: String,
    val name: LocalizedText,
    val center: GeoPoint,
    val defaultZoom: Double,
    val capabilities: CityCapabilities,
    val availability: CityAvailability = CityAvailability(
        readiness = CityReadiness.UNREVIEWED,
        source = CitySource.UNREVIEWED_ADAPTER,
    ),
    val attribution: List<AttributionLink> = emptyList(),
)

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** Coordinate-bearing input is request-scoped and never occurs in the response. */
@Serializable
data class WalkingEstimateRequest(
    val from: GeoPoint,
    val to: GeoPoint,
    val locale: String,
)

/** Normalized direct-walking result with no geometry or endpoint coordinates. */
@Serializable
data class WalkingEstimate(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val observedAt: String,
)

@Serializable
data class Direction(
    val id: String,
    val name: LocalizedText,
    val headsign: LocalizedText,
)

@Serializable
data class Route(
    val id: String,
    val providerId: String,
    val shortName: String,
    val longName: LocalizedText,
    val color: String,
    val textColor: String,
    val mode: String,
    val directions: List<Direction>,
)

@Serializable
data class Stop(
    val id: String,
    val providerId: String,
    val code: String,
    val name: LocalizedText,
    val position: GeoPoint,
    val routeIds: List<String>,
    val mode: String,
)

@Serializable
data class Shape(
    val format: String = "encoded_polyline",
    val precision: Int,
    val value: String,
    val updatedAt: String,
)

@Serializable
enum class PositionKind {
    GPS,
    ESTIMATED,
    UNKNOWN,
}

@Serializable
data class Vehicle(
    val id: String,
    val routeId: String,
    val directionId: String? = null,
    val position: GeoPoint,
    val bearing: Double? = null,
    val nextStopId: String? = null,
    val observedAt: String? = null,
    val ageSeconds: Int? = null,
    val positionKind: PositionKind,
)

@Serializable
enum class ArrivalSource {
    OFFICIAL_REALTIME,
    AGGREGATOR_REALTIME,
    SCHEDULE,
    CLIENT_ESTIMATE,
}

@Serializable
data class Arrival(
    val stopId: String,
    val routeId: String,
    val tripId: String? = null,
    val headsign: LocalizedText,
    val scheduledAt: String? = null,
    val expectedAt: String? = null,
    val expectedInMinutes: Int? = null,
    val realtime: Boolean,
    val cancelled: Boolean,
    val source: ArrivalSource,
)

@Serializable
data class JourneyLeg(
    /** Legacy transit-only representation retained for existing clients. */
    val routeId: String,
    val directionId: String,
    val fromStopId: String,
    val toStopId: String,
    val departureAt: String,
    val arrivalAt: String,
)

/** Small app-owned taxonomy for the complete ordered itinerary. */
@Serializable
enum class JourneySegmentMode {
    TRANSIT,
    WALK,
    BICYCLE,
    CAR,
    OTHER,
}

/** Additive complete itinerary representation; non-transit segments have no route/direction. */
@Serializable
data class JourneySegment(
    val departureAt: String,
    val arrivalAt: String,
    val mode: JourneySegmentMode,
    val routeId: String? = null,
    val directionId: String? = null,
    val fromStopId: String? = null,
    val toStopId: String? = null,
    val fromPosition: GeoPoint? = null,
    val toPosition: GeoPoint? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Journey(
    val id: String,
    val departureAt: String,
    val arrivalAt: String,
    val transfers: Int,
    /** Legacy transit-only legs. New clients should use [segments] for full order. */
    val legs: List<JourneyLeg>,
    /** Omit the empty default so legacy fixture responses retain their exact serialized shape. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val segments: List<JourneySegment> = emptyList(),
)

@Serializable
data class VehiclePage(
    val items: List<Vehicle>,
    val observedAt: String,
    val maxAgeSeconds: Int,
    val stale: Boolean,
)

@Serializable
data class ArrivalPage(
    val items: List<Arrival>,
    val source: ArrivalSource,
    val observedAt: String,
    val stale: Boolean,
)

@Serializable
data class JourneyPage(
    val items: List<Journey>,
    val observedAt: String,
    val source: ArrivalSource = ArrivalSource.SCHEDULE,
    val realtime: Boolean = false,
    val stale: Boolean = false,
)

@Serializable
data class HealthResponse(
    val status: String,
    val mode: String,
)

@Serializable
data class ErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val retryAfterSeconds: Int? = null,
    val requestId: String,
)
