package com.denis.georgiatransit.bff.api

import kotlinx.serialization.Serializable

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
)

@Serializable
data class City(
    val id: String,
    val name: LocalizedText,
    val center: GeoPoint,
    val defaultZoom: Double,
    val capabilities: CityCapabilities,
)

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
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
    val routeId: String,
    val directionId: String,
    val fromStopId: String,
    val toStopId: String,
    val departureAt: String,
    val arrivalAt: String,
)

@Serializable
data class Journey(
    val id: String,
    val departureAt: String,
    val arrivalAt: String,
    val transfers: Int,
    val legs: List<JourneyLeg>,
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
