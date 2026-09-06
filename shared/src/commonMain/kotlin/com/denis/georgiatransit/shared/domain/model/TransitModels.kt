package com.denis.georgiatransit.shared.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CityId(val value: String)

@Serializable
data class RouteId(val value: String)

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

@Serializable
data class CityCapabilities(
    val stops: Boolean,
    val vehicles: Boolean,
    val arrivals: Boolean,
    val routeShapes: Boolean,
    val journeyPlanning: Boolean,
    val experimental: Boolean = false,
)

@Serializable
data class TransitCity(
    val id: CityId,
    val name: String,
    val countryCode: String = "GE",
    val center: GeoPoint,
    val capabilities: CityCapabilities,
)

@Serializable
data class TransitRoute(
    val id: RouteId,
    val cityId: CityId,
    val shortName: String,
    val name: String,
    val colorArgb: Long,
)

