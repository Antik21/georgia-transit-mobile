package com.denis.georgiatransit.shared.domain.analytics

import com.denis.georgiatransit.shared.domain.analytics.schema.AnalyticsCatalog as Catalog

fun interface NativeAnalyticsSink {
    fun send(event: NativeAnalyticsEvent)
}

class NativeAnalyticsEvent internal constructor(
    val name: String,
    val properties: Map<String, Any>,
    val userProperties: Map<String, String>,
    val timestamp: Long,
    val insertId: String,
) {
    val isIdentify: Boolean get() = name == Catalog.Identify
}

internal fun AnalyticsRecord.toNativeEvent(): NativeAnalyticsEvent {
    val name = when (event) {
        AnalyticsEvent.SessionStarted -> Catalog.Identify
        AnalyticsEvent.RecentRoutesUsed -> Catalog.RecentRoutesUsed
        AnalyticsEvent.RouteSearchUsed -> Catalog.RouteSearchUsed
        is AnalyticsEvent.ZoomStarted -> Catalog.ZoomStarted
        is AnalyticsEvent.RoutesSelected -> Catalog.RoutesSelected
        is AnalyticsEvent.JourneyPlanningStarted -> Catalog.JourneyPlanningStarted
        is AnalyticsEvent.StopOpened -> Catalog.StopOpened
        is AnalyticsEvent.VehicleTapped -> Catalog.VehicleTapped
    }
    val properties = when (val value = event) {
        is AnalyticsEvent.ZoomStarted -> mapOf(Catalog.ZoomType to value.type.wireName)
        is AnalyticsEvent.RoutesSelected -> mapOf(
            Catalog.City to value.cityId.value,
            Catalog.RouteCount to value.routeCount,
        )
        is AnalyticsEvent.JourneyPlanningStarted -> mapOf(Catalog.Source to value.source.wireName)
        is AnalyticsEvent.StopOpened -> mapOf(Catalog.City to value.cityId.value)
        else -> emptyMap()
    }
    val eventKey = when (val value = event) {
        AnalyticsEvent.SessionStarted -> "identify"
        is AnalyticsEvent.ZoomStarted -> value.type.wireName
        is AnalyticsEvent.RoutesSelected -> "$name:${value.eventId}"
        is AnalyticsEvent.JourneyPlanningStarted -> "$name:${value.eventId}"
        is AnalyticsEvent.StopOpened -> "$name:${value.eventId}"
        is AnalyticsEvent.VehicleTapped -> "$name:${value.eventId}"
        else -> name
    }
    return NativeAnalyticsEvent(
        name,
        properties,
        mapOf(Catalog.Client to client.wireName),
        time,
        Catalog.insertId(client.wireName, sessionId, eventKey),
    )
}
