package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.config.BffConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class MapProxyTest {
    @Test
    fun `style uses configured absolute BFF origin never upstream template`() {
        MapProxy(config()).use { proxy ->
            val style = proxy.styleJson()
            assertTrue(style.contains("https://bff.example/v1/map/vector/{z}/{x}/{y}.pbf"))
            assertTrue(style.contains("\"maxzoom\":14"))
            assertTrue(style.contains("\"minzoom\":8"))
            assertTrue(style.contains("\"bounds\":[41.35,41.3,42.2,41.95]"))
            assertFalse(style.contains("tiles.openfreemap.org"))
        }
    }

    @Test
    fun `Zugdidi coverage adds a regional source and keeps Batumi tiles available`() = runBlocking {
        val fake = FakeDownloader()
        MapProxy(config(), fake, areas = setOf(MapArea.Batumi, MapArea.Zugdidi)).use { proxy ->
            val style = proxy.styleJson()
            assertTrue(style.contains("\"bounds\":[41.35,41.3,42.2,41.95]"))
            assertTrue(style.contains("\"bounds\":[41.48,42.32,42.07,42.68]"))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(14, 10_097, 6_050))
            assertFailsWith<IllegalArgumentException> { proxy.tile(14, 0, 0) }
            assertEquals(2, fake.calls)
        }
    }

    @Test
    fun `city coverage matches the public vector bounds and leaves Batumi unchanged`() {
        val city = City(
            id = "kutaisi",
            name = LocalizedText("Кутаиси", "Kutaisi", "ქუთაისი"),
            center = GeoPoint(42.27, 42.70),
            defaultZoom = 15.0,
            capabilities = CityCapabilities(stops = true, routes = true, routeGeometry = true,
                vehiclePositions = false, officialArrivals = false, tripPlanning = false),
        )
        val batumi = city.copy(id = "batumi")
        MapProxy(config(), areas = setOf(MapArea.Batumi, MapArea.Zugdidi)).use { proxy ->
            val (bounded, originalBatumi) = proxy.withCityCoverage(listOf(city, batumi))
            val coverage = assertNotNull(bounded.mapBounds)
            val sources = Json.parseToJsonElement(proxy.styleJson(kutaisiEnabled = true)).jsonObject.getValue("sources")
            assertEquals(setOf("gt-vector-batumi", "gt-vector-zugdidi", "gt-vector-kutaisi"), sources.jsonObject.keys)
            val styleBounds = sources.jsonObject.getValue("gt-vector-kutaisi").jsonObject.getValue("bounds")
                .jsonArray.map { it.jsonPrimitive.double }
            assertEquals(styleBounds, listOf(coverage.west, coverage.south, coverage.east, coverage.north))
            assertEquals(batumi, originalBatumi)
            val disabled = city.copy(capabilities = city.capabilities.copy(stops = false))
            assertEquals(listOf(disabled), proxy.withCityCoverage(listOf(disabled)))
        }
    }

    @Test
    fun `successful tiles are bounded cached by coordinate`() = runBlocking {
        val fake = FakeDownloader()
        MapProxy(config(), fake).use { proxy ->
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertEquals(1, fake.calls)
        }
    }

    @Test
    fun `expired tile is conditionally revalidated and 304 refreshes cached bytes`() = runBlocking {
        val fake = FakeDownloader(revalidateAsNotModified = true)
        val clock = MutableClock(Instant.parse("2026-09-15T00:00:00Z"))
        MapProxy(config(), fake, clock).use { proxy ->
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            clock.advance(Duration.ofSeconds(VectorTileCacheSeconds.toLong() + 1))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertEquals(2, fake.calls)
            assertEquals(MapTileValidators("tile-etag", "Mon, 15 Sep 2026 00:00:00 GMT"), fake.lastValidators)
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertEquals(2, fake.calls)
        }
    }

    @Test
    fun `tile requests are limited to the Batumi area and supported zooms`() {
        assertFailsWith<IllegalArgumentException> { MapProxy.validate(7, 78, 47) }
        assertFailsWith<IllegalArgumentException> { MapProxy.validate(8, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MapProxy.validate(15, 20_172, 12_206) }
        MapProxy.validate(8, 157, 95)
    }

    @Test
    fun `Kutaisi tiles are served only while enabled and keep the original Batumi coverage`() = runBlocking {
        val fake = FakeDownloader()
        MapProxy(config(), fake).use { proxy ->
            assertFailsWith<IllegalArgumentException> { proxy.tile(14, 10_135, 6_065) }
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(14, 10_135, 6_065, kutaisiEnabled = true))
            assertFailsWith<IllegalArgumentException> { proxy.tile(14, 10_135, 6_065) }
            assertFailsWith<IllegalArgumentException> { proxy.tile(14, 0, 0, kutaisiEnabled = true) }
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95, kutaisiEnabled = true))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(8, 157, 95))
            assertEquals(2, fake.calls)
        }
    }

    @Test
    fun `upstream cache misses are globally rate limited`() = runBlocking {
        val fake = FakeDownloader()
        val clock = MutableClock(Instant.parse("2026-09-15T00:00:00Z"))
        val cache = MapAssetCache(fake, clock)
        repeat(120) { offset ->
            val bytes = cache.get("https://tiles.example/$offset.pbf", MapAssetFormat.Vector, Duration.ofHours(1))
            assertContentEquals(byteArrayOf(1, 2), bytes)
        }
        assertFailsWith<MapProxyFailure> {
            cache.get("https://tiles.example/120.pbf", MapAssetFormat.Vector, Duration.ofHours(1))
        }
        assertEquals(120, fake.calls)
    }

    private fun config() = BffConfig.fromEnvironment(
        mapOf(
            "BFF_MAP_ENABLED" to "true",
            "BFF_MAP_PUBLIC_BASE_URL" to "https://bff.example",
        ),
    ).map

    private class FakeDownloader(
        private val revalidateAsNotModified: Boolean = false,
    ) : MapTileDownloader {
        var calls = 0
        var lastValidators: MapTileValidators? = null
        override suspend fun download(address: String, format: MapAssetFormat, validators: MapTileValidators?): MapTileDownload {
            if (format == MapAssetFormat.TileJson) {
                val metadata = """{"tiles":["https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf"]}"""
                return MapTileDownload(metadata.encodeToByteArray(), null, null)
            }
            calls += 1
            lastValidators = validators
            if (revalidateAsNotModified && validators != null) {
                return MapTileDownload(bytes = null, eTag = null, lastModified = null)
            }
            return MapTileDownload(
                bytes = byteArrayOf(1, 2),
                eTag = "tile-etag",
                lastModified = "Mon, 15 Sep 2026 00:00:00 GMT",
            )
        }
        override fun close() = Unit
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
