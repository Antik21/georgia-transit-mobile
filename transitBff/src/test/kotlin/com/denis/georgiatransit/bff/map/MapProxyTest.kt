package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.config.BffConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapProxyTest {
    @Test
    fun `style uses configured absolute BFF origin never upstream template`() {
        MapProxy(config()).use { proxy ->
            val style = proxy.styleJson()
            assertTrue(style.contains("https://bff.example/v1/map/tiles/{z}/{x}/{y}.png"))
            assertTrue(style.contains("\"maxzoom\":19"))
            assertTrue(style.contains("\"minzoom\":8"))
            assertTrue(style.contains("\"bounds\":[41.35,41.3,42.2,41.95]"))
            assertFalse(style.contains("tiles.example"))
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
            clock.advance(Duration.ofSeconds(MapTileCacheSeconds.toLong() + 1))
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
        MapProxy.validate(8, 157, 95)
    }

    @Test
    fun `upstream cache misses are globally rate limited`() = runBlocking {
        val fake = FakeDownloader()
        val clock = MutableClock(Instant.parse("2026-09-15T00:00:00Z"))
        MapProxy(config(), fake, clock).use { proxy ->
            repeat(120) { offset ->
                assertContentEquals(byteArrayOf(1, 2), proxy.tile(19, 322_364 + offset, 195_000))
            }
            assertFailsWith<MapProxyFailure> { proxy.tile(19, 322_484, 195_000) }
            assertEquals(120, fake.calls)
        }
    }

    private fun config() = BffConfig.fromEnvironment(
        mapOf(
            "BFF_MAP_ENABLED" to "true",
            "BFF_MAP_TILE_TEMPLATE" to "https://tiles.example/{z}/{x}/{y}.png",
            "BFF_MAP_ATTRIBUTION" to "© Example",
            "BFF_MAP_PUBLIC_BASE_URL" to "https://bff.example",
        ),
    ).map

    private class FakeDownloader(
        private val revalidateAsNotModified: Boolean = false,
    ) : MapTileDownloader {
        var calls = 0
        var lastValidators: MapTileValidators? = null
        override suspend fun download(address: String, validators: MapTileValidators?): MapTileDownload {
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
