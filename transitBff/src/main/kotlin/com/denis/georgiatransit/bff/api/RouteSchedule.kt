package com.denis.georgiatransit.bff.api

import kotlinx.serialization.Serializable

@Serializable
data class RouteSchedule(
    val routeId: String,
    val directionId: String,
    val serviceDate: String,
    val timeZone: String,
    val stops: List<ScheduledStop>,
)

@Serializable
data class ScheduledStop(
    val stopId: String,
    val name: LocalizedText,
    val arrivalTimes: List<String>,
)
