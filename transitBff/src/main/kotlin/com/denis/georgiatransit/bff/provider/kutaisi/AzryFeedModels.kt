package com.denis.georgiatransit.bff.provider.kutaisi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class AzryRoute(
    val id: String,
    val shortName: String,
    val longName: String? = null,
    val color: String? = null,
    val mode: String,
    val patterns: List<AzryPattern> = emptyList(),
    val defaultPatternSuffix: String? = null,
)

@Serializable
internal data class AzryPattern(
    val patternSuffix: String,
    val firstStop: AzryStopName,
    val lastStop: AzryStopName,
    val headsign: String? = null,
)

@Serializable
internal data class AzryStopName(val name: String)

@Serializable
internal data class AzryStop(
    val id: String,
    val code: String? = null,
    val name: String,
    val lat: Double,
    val lon: Double,
    val vehicleMode: String,
)

@Serializable
internal data class AzryPolyline(val encodedValue: String)

@Serializable
internal data class AzryPosition(
    val vehicleId: JsonPrimitive,
    val lat: Double,
    val lon: Double,
    val heading: Double? = null,
    val nextStopId: String? = null,
)

@Serializable
internal data class AzryArrival(
    val shortName: String,
    val patternSuffix: String? = null,
    val headsign: String? = null,
    val vehicleMode: String,
    val realtime: Boolean? = null,
    val realtimeArrivalMinutes: Int? = null,
    val scheduledArrivalMinutes: Int? = null,
)

@Serializable
internal data class AzrySchedule(
    val serviceDates: List<String>,
    val stops: List<AzryScheduleStop>,
)

@Serializable
internal data class AzryScheduleStop(
    val id: String,
    val name: String,
    val position: Int,
    val arrivalTimes: String? = null,
)
