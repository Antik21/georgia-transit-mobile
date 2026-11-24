package com.denis.georgiatransit.shared.domain.analytics

import com.denis.georgiatransit.shared.domain.model.CityId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductAnalyticsTest {
    @Test
    fun sendsEachZoomTypeOnceAndResetsOnlyAfterTheBackgroundTimeout() = runTest {
        var time = 1_000L
        val records = mutableListOf<AnalyticsRecord>()
        val analytics = ProductAnalytics(
            AnalyticsClient.TelegramMiniApp, "device", backgroundScope, { time }, { records.add(it) },
        )
        analytics.setForeground(true)
        repeat(3) { ZoomType.entries.forEach(analytics::zoomStarted) }
        analytics.setForeground(false)
        analytics.zoomStarted(ZoomType.Button)
        time += 30 * 60 * 1_000L - 1
        analytics.setForeground(true)
        analytics.zoomStarted(ZoomType.Pinch)
        runCurrent()

        assertEquals(
            listOf(AnalyticsEvent.SessionStarted) + ZoomType.entries.map { AnalyticsEvent.ZoomStarted(it) },
            records.map(AnalyticsRecord::event),
        )
        assertEquals(setOf(1_000L), records.map(AnalyticsRecord::sessionId).toSet())
        assertEquals(setOf(AnalyticsClient.TelegramMiniApp), records.map(AnalyticsRecord::client).toSet())

        analytics.setForeground(false)
        time += 30 * 60 * 1_000L
        analytics.setForeground(true)
        repeat(2) { analytics.zoomStarted(ZoomType.Button) }
        runCurrent()

        val reopened = records.drop(4)
        assertEquals(
            listOf(AnalyticsEvent.SessionStarted, AnalyticsEvent.ZoomStarted(ZoomType.Button)),
            reopened.map(AnalyticsRecord::event),
        )
        assertEquals(setOf(time), reopened.map(AnalyticsRecord::sessionId).toSet())
    }

    @Test
    fun recordsEachRoutesConfirmationIncludingAnEmptySelection() = runTest {
        val records = mutableListOf<AnalyticsRecord>()
        val analytics = ProductAnalytics(
            AnalyticsClient.Android, "device", backgroundScope, { 1_000L }, { records.add(it) },
        )
        val city = CityId("kutaisi")
        analytics.setForeground(true)
        analytics.routesSelected(city, 3)
        analytics.routesSelected(city, 3)
        analytics.routesSelected(city, 0)
        runCurrent()

        assertEquals(AnalyticsEvent.SessionStarted, records.first().event)
        val selections = records.map { it.event }.filterIsInstance<AnalyticsEvent.RoutesSelected>()
        assertEquals(listOf(3, 3, 0), selections.map { it.routeCount })
        assertEquals(setOf(city), selections.map { it.cityId }.toSet())
        assertEquals(3, selections.map { it.eventId }.toSet().size)
    }

    @Test
    fun recordsRecentRoutesAndSearchIndependentlyOncePerSession() = runTest {
        var time = 1_000L
        val records = mutableListOf<AnalyticsRecord>()
        val analytics = ProductAnalytics(
            AnalyticsClient.Android, "device", backgroundScope, { time }, { records.add(it) },
        )
        fun useRouteTools() {
            analytics.recentRoutesUsed()
            analytics.routeSearchUsed()
        }
        fun sessionsFor(event: AnalyticsEvent) = records.filter { it.event == event }.map { it.sessionId }

        analytics.setForeground(true)
        repeat(2) { useRouteTools() }
        analytics.setForeground(false)
        time += 60_000
        analytics.setForeground(true)
        useRouteTools()
        runCurrent()
        assertEquals(listOf(1_000L), sessionsFor(AnalyticsEvent.RecentRoutesUsed))
        assertEquals(listOf(1_000L), sessionsFor(AnalyticsEvent.RouteSearchUsed))

        analytics.setForeground(false)
        time += 30 * 60 * 1_000L
        analytics.setForeground(true)
        useRouteTools()
        runCurrent()
        assertEquals(listOf(1_000L, time), sessionsFor(AnalyticsEvent.RecentRoutesUsed))
        assertEquals(listOf(1_000L, time), sessionsFor(AnalyticsEvent.RouteSearchUsed))
    }

    @Test
    fun usesAStableInstallationIdWhenStorageWorks() {
        var stored: String? = null
        val first = loadAnalyticsDeviceId({ stored }, { stored = it })
        val second = loadAnalyticsDeviceId({ stored }, { stored = it })
        assertEquals(first, second)
        assertNotEquals(first, loadAnalyticsDeviceId({ "invalid" }, {}))
    }
}
