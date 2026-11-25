package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class KutaisiAzryAdapterTest {
    @Test
    fun `Thai route names match the published route and its termini`() {
        val route = azryPublicId(PublicEntityType.Route, "1:R1318")
        assertEquals("สะพานแดง", azryText("Red bridge").th)
        assertEquals("ถนนซาคาเรีย ปาเลียชวิลี เลขที่ 13 → สถานีรถไฟริโอนี", KutaisiRouteNames.name(route, "3")?.th)
        assertNull(KutaisiRouteNames.name(route, "99"))
        assertNull(azryText("New unlisted terminus").th)
    }

    @Test
    fun `direction belongs to its route and both directions share one positions snapshot`() = runTest {
        val feed = FakeAzryFeed()
        KutaisiAzryTransitProviderAdapter(feed, clock).use { adapter ->
            val route = adapter.routes("ru", "bus").single()
            val primary = route.directions.first().id
            val reverse = route.directions.last().id
            assertEquals(azryDirectionId(feed.line.id, "1:01"), primary)
            assertEquals(listOf(route.id), adapter.directionStops(route.id, primary, "en").first().routeIds)
            val snapshot = adapter.vehicles(route.id, null)
            assertEquals(clock.instant(), snapshot.observedAt)
            assertEquals(48, snapshot.maxAgeSeconds)
            val bus = adapter.vehicles(route.id, primary).items.single()
            assertNull(bus.observedAt)
            assertNull(bus.ageSeconds)
            assertEquals(emptyList(), adapter.vehicles(route.id, reverse).items)
            assertEquals(1, feed.positionReads)
            assertFailsWith<ProviderRouteNotFound> {
                adapter.vehicles(route.id, azryDirectionId("1:R2", "1:01"))
            }
        }
    }

    @Test
    fun `schedule respects selected date stop order and extended local service hours`() = runTest {
        KutaisiAzryTransitProviderAdapter(FakeAzryFeed(), clock).use { adapter ->
            val route = adapter.routes("en", null).single()
            val directionId = route.directions.first().id
            val date = LocalDate.parse("2026-11-19")
            val schedule = adapter.schedule(route.id, directionId, date, "en")
            assertEquals("Asia/Tbilisi", schedule.timeZone)
            assertEquals(listOf("Central", "Terminal"), schedule.stops.map { it.name.en })
            assertEquals(
                listOf("2026-11-19T04:00:00Z", "2026-11-19T20:15:30Z"),
                schedule.stops.first().arrivalTimes,
            )
            assertTrue(adapter.schedule(route.id, directionId, date.plusDays(1), "en").stops.isEmpty())
        }
    }

    @Test
    fun `board keeps the official forecast and scheduled fallback on distinct rows`() = runTest {
        val feed = FakeAzryFeed()
        KutaisiAzryTransitProviderAdapter(feed, clock).use { adapter ->
            val stop = azryPublicId(PublicEntityType.Stop, feed.central.id)
            val board = adapter.arrivals(stop, limit = 10, locale = "en")
            assertEquals(ArrivalSource.OFFICIAL_REALTIME, board.source)
            assertEquals(2, board.items.size)
            val (live, planned) = board.items
            assertEquals(2, live.expectedInMinutes)
            assertTrue(live.realtime)
            assertNull(live.vehicleId)
            assertNull(live.tripId)
            assertEquals(azryPublicId(PublicEntityType.Route, feed.line.id), live.routeId)
            assertEquals(ArrivalSource.SCHEDULE, planned.source)
            assertEquals("2026-11-19T08:08:00Z", planned.scheduledAt)
            assertNull(planned.expectedAt)
            assertFalse(planned.realtime)
        }
    }

    @Test
    fun `reviewed name is consistent in the directory direction and timetable`() = runTest {
        KutaisiAzryTransitProviderAdapter(FakeAzryFeed(university), clock).use { adapter ->
            val route = adapter.routes("fr", "bus").single()
            val directionId = route.directions.first().id
            val date = LocalDate.parse("2026-11-19")
            val name = adapter.stopDirectory("fr").first().name
            assertEquals("Université internationale de Koutaïssi", name.fr)
            assertEquals("Кутаисский международный университет", name.ru)
            assertEquals("库塔伊西国际大学", name.zh)
            assertEquals(name, adapter.directionStops(route.id, directionId, "ka").first().name)
            assertEquals(name, adapter.schedule(route.id, directionId, date, "ar").stops.first().name)
        }
    }

    @Test
    fun `directory uses stop identity and keeps the provider label after drift`() {
        val rows = javaClass.getResourceAsStream("/transit/kutaisi/stop-names.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val directory = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, Entry>>(rows)
        assertEquals(451, directory.size)
        directory.forEach { (id, entry) ->
            val label = KutaisiStopNames.find(id, entry.code, entry.position)!!
            assertTrue(listOf(label.ru, label.en, label.ka, label.uk, label.de, label.fr, label.ar, label.hi, label.zh, label.th)
                .all { !it.isNullOrBlank() }, id)
        }
        val changedCode = university.copy(code = "999")
        val moved = university.copy(lat = 42.216, name = "Moved stop")
        assertNull(changedCode.normalized(emptyList()).name.fr)
        assertEquals("Moved stop", moved.normalized(emptyList()).name.en)
        val id = azryPublicId(PublicEntityType.Stop, university.id)
        assertNull(KutaisiStopNames.findForTimetable(id, "Renamed university stop"))
    }

    @kotlinx.serialization.Serializable
    private data class Entry(val code: String, val position: GeoPoint)

    private val university = AzryStop(
        id = "1:1020",
        code = "227",
        name = "\"Kutaisi International University\"",
        lat = 42.214444,
        lon = 42.710544,
        vehicleMode = "BUS",
    )

    private val clock = Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC)

    private class FakeAzryFeed(
        val central: AzryStop = AzryStop("1:1", "244", "Central", 42.27, 42.70, "BUS"),
    ) : AzryFeed {
        val line = AzryRoute("1:R1", "3", "Central to Terminal", mode = "BUS")
        private val terminal = AzryStop("1:2", "52", "Terminal", 42.27, 42.71, "BUS")
        private val a = AzryStopName(central.name)
        private val b = AzryStopName(terminal.name)
        var positionReads = 0

        override suspend fun routes(locale: String) = listOf(line)
        override suspend fun route(id: String, locale: String) = line.copy(
            patterns = listOf(AzryPattern("0:01", b, a), AzryPattern("1:01", a, b)),
            defaultPatternSuffix = "1:01",
        )
        override suspend fun stops(locale: String) = listOf(central, terminal)
        override suspend fun directionStops(id: String, pattern: String, locale: String) = listOf(central, terminal)
        override suspend fun polylines(id: String, patterns: List<String>) = emptyMap<String, AzryPolyline>()
        override suspend fun positions(id: String, patterns: List<String>): Map<String, List<AzryPosition>> {
            positionReads++
            return mapOf("1:01" to listOf(AzryPosition(JsonPrimitive(47), 42.27, 42.70)))
        }
        override suspend fun arrivals(stopId: String, locale: String, limit: Int) = listOf(
            AzryArrival("3", "1:01", "Terminal", "BUS", realtime = true, realtimeArrivalMinutes = 2),
            AzryArrival("3", "1:01", "Terminal", "BUS", scheduledArrivalMinutes = 8),
            AzryArrival("Unknown", "1:01", "Terminal", "BUS", scheduledArrivalMinutes = 8),
        )
        override suspend fun stopRoutes(stopId: String, locale: String) = listOf(line)
        override suspend fun schedule(id: String, pattern: String, locale: String) = listOf(
            AzrySchedule(
                serviceDates = listOf("2026-11-19"),
                stops = listOf(
                    AzryScheduleStop(terminal.id, terminal.name, 2, "8:05"),
                    AzryScheduleStop(central.id, central.name, 1, "8:00,-,24:15:30"),
                ),
            ),
        )
        override fun close() = Unit
    }
}
