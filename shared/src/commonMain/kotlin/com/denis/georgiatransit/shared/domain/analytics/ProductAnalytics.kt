@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.denis.georgiatransit.shared.domain.analytics

import com.denis.georgiatransit.shared.domain.model.CityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class AnalyticsClient(val wireName: String) {
    Android("android"),
    Ios("ios"),
    Web("web"),
    TelegramMiniApp("telegram_mini_app"),
}

enum class ZoomType(val wireName: String) {
    DoubleTap("finger_double_tap"),
    Pinch("finger_pinch"),
    Button("button"),
}

internal enum class JourneyPlanningSource(val wireName: String) {
    MapButton("map_button"),
    LongPress("long_press"),
}

internal sealed interface AnalyticsEvent {
    data object SessionStarted : AnalyticsEvent
    data object RecentRoutesUsed : AnalyticsEvent
    data object RouteSearchUsed : AnalyticsEvent
    data class ZoomStarted(val type: ZoomType) : AnalyticsEvent
    data class RoutesSelected(val cityId: CityId, val routeCount: Int, val eventId: String) : AnalyticsEvent
    data class StopOpened(val cityId: CityId, val eventId: String) : AnalyticsEvent
    data class VehicleTapped(val eventId: String) : AnalyticsEvent
    data class JourneyPlanningStarted(val source: JourneyPlanningSource, val eventId: String) : AnalyticsEvent
}

internal data class AnalyticsRecord(
    val client: AnalyticsClient,
    val deviceId: String,
    val sessionId: Long,
    val time: Long,
    val event: AnalyticsEvent,
)

/** App-scoped and called from the UI thread. Navigation and map recreation share this session. */
class ProductAnalytics internal constructor(
    private val client: AnalyticsClient,
    private val deviceId: String,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    deliver: suspend (AnalyticsRecord) -> Unit,
) {
    private val records = Channel<AnalyticsRecord>(capacity = 16)
    private val recordedSessionEvents = mutableSetOf<AnalyticsEvent>()
    private var sessionId = 0L
    private var backgroundAt: Long? = null

    private val sender = scope.launch {
        for (record in records) deliver(record)
    }

    internal fun close() {
        records.close()
        sender.cancel()
    }

    fun setForeground(foreground: Boolean) {
        val now = nowMillis()
        if (!foreground) {
            if (backgroundAt == null) backgroundAt = now
            return
        }
        val leftAt = backgroundAt
        backgroundAt = null
        if (sessionId == 0L || (leftAt != null && now - leftAt >= SessionIdleMillis)) startSession(now)
    }

    fun zoomStarted(type: ZoomType) = recordOnce(AnalyticsEvent.ZoomStarted(type))

    fun recentRoutesUsed() = recordOnce(AnalyticsEvent.RecentRoutesUsed)

    fun routeSearchUsed() = recordOnce(AnalyticsEvent.RouteSearchUsed)

    fun routesSelected(cityId: CityId, routeCount: Int) = record {
        AnalyticsEvent.RoutesSelected(cityId, routeCount, Uuid.random().toString())
    }

    internal fun journeyPlanningStarted(source: JourneyPlanningSource) = record {
        AnalyticsEvent.JourneyPlanningStarted(source, Uuid.random().toString())
    }

    internal fun stopOpened(cityId: CityId) = record {
        AnalyticsEvent.StopOpened(cityId, Uuid.random().toString())
    }

    internal fun vehicleTapped() = record {
        AnalyticsEvent.VehicleTapped(Uuid.random().toString())
    }

    private inline fun record(event: () -> AnalyticsEvent) {
        if (backgroundAt != null) return
        val now = nowMillis()
        if (sessionId == 0L) startSession(now)
        enqueue(now, event())
    }

    private fun recordOnce(event: AnalyticsEvent) {
        if (backgroundAt != null || event in recordedSessionEvents) return
        val now = nowMillis()
        if (sessionId == 0L) startSession(now)
        recordedSessionEvents.add(event)
        enqueue(now, event)
    }

    private fun startSession(now: Long) {
        sessionId = maxOf(now, sessionId + 1)
        recordedSessionEvents.clear()
        enqueue(now, AnalyticsEvent.SessionStarted)
    }

    private fun enqueue(now: Long, event: AnalyticsEvent) {
        records.trySend(AnalyticsRecord(client, deviceId, sessionId, maxOf(now, sessionId), event))
    }

    private companion object {
        const val SessionIdleMillis = 30 * 60 * 1_000L
    }
}

fun loadAnalyticsDeviceId(read: () -> String?, write: (String) -> Unit): String {
    val stored = runCatching(read).getOrNull()?.let(Uuid::parseOrNull)
    if (stored != null) return stored.toString()
    return Uuid.random().toString().also { runCatching { write(it) } }
}
