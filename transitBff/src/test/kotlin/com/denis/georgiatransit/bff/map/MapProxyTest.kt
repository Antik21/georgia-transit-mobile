package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.config.BffConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapProxyTest {
    @Test
    fun `style uses configured absolute BFF origin never upstream template`() {
        MapProxy(config()).use { proxy ->
            val style = proxy.styleJson()
            assertTrue(style.contains("https://bff.example/v1/map/tiles/{z}/{x}/{y}.png"))
            assertTrue(style.contains("\"maxzoom\":19"))
            assertFalse(style.contains("tiles.example"))
        }
    }

    @Test
    fun `successful tiles are bounded cached by coordinate`() = runBlocking {
        val fake = FakeDownloader()
        MapProxy(config(), fake).use { proxy ->
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(0, 0, 0))
            assertContentEquals(byteArrayOf(1, 2), proxy.tile(0, 0, 0))
            assertEquals(1, fake.calls)
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

    private class FakeDownloader : MapTileDownloader {
        var calls = 0
        override suspend fun download(address: String): ByteArray { calls += 1; return byteArrayOf(1, 2) }
        override fun close() = Unit
    }
}
