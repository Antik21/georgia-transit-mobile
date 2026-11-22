package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.domain.analytics.AnalyticsEvent
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsRecord
import kotlinx.serialization.Serializable

@Serializable
internal data class AnalyticsRequest(
    val client: String,
    val deviceId: String,
    val sessionId: Long,
    val time: Long,
    val zoomType: String? = null,
    val routesSelection: RoutesSelection? = null,
    val recentRoutesUsed: Boolean = false,
    val routeSearchUsed: Boolean = false,
    val journeyPlanning: JourneyPlanning? = null,
    val stopOpening: StopOpening? = null,
    val vehicleTapId: String? = null,
) {
    @Serializable
    data class RoutesSelection(val cityId: String, val routeCount: Int, val eventId: String)

    @Serializable
    data class JourneyPlanning(val source: String, val eventId: String)

    @Serializable
    data class StopOpening(val cityId: String, val eventId: String)
}

internal fun AnalyticsRecord.toRequest(): AnalyticsRequest {
    val request = AnalyticsRequest(client.wireName, deviceId, sessionId, time)
    return when (val value = event) {
        AnalyticsEvent.SessionStarted -> request
        AnalyticsEvent.RecentRoutesUsed -> request.copy(recentRoutesUsed = true)
        AnalyticsEvent.RouteSearchUsed -> request.copy(routeSearchUsed = true)
        is AnalyticsEvent.ZoomStarted -> request.copy(zoomType = value.type.wireName)
        is AnalyticsEvent.JourneyPlanningStarted -> request.copy(
            journeyPlanning = AnalyticsRequest.JourneyPlanning(value.source.wireName, value.eventId),
        )
        is AnalyticsEvent.StopOpened -> request.copy(
            stopOpening = AnalyticsRequest.StopOpening(value.cityId.value, value.eventId),
        )
        is AnalyticsEvent.VehicleTapped -> request.copy(vehicleTapId = value.eventId)
        is AnalyticsEvent.RoutesSelected -> request.copy(
            routesSelection = AnalyticsRequest.RoutesSelection(value.cityId.value, value.routeCount, value.eventId),
        )
    }
}
