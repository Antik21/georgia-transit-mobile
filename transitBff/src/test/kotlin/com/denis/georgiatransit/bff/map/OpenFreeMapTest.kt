package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.mapRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OpenFreeMapTest {
    @Test
    fun localizedStylesReuseVectorTilesAndFallBackToGeorgian() = testApplication {
        val upstream = FixtureDownloader()
        val map = MapProxy(config, upstream)
        application { routing { mapRoutes(map, kutaisiMapEnabled = { true }) } }

        assertEquals(
            client.get("/v1/map/style.json?lang=ka").bodyAsText(),
            client.get("/v1/map/style.json").bodyAsText(),
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/map/tiles/14/10086/6103.png").status)
        for (lang in listOf("ka", "ru", "en")) {
            val response = client.get("/v1/map/style.json?lang=$lang")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val style = Json.parseToJsonElement(body).jsonObject
            assertContains(style.getValue("name").jsonPrimitive.content, "Natural")
            val layers = style.getValue("layers").jsonArray.map { it.jsonObject }
            val background = layers.first { it.getValue("id").jsonPrimitive.content == "map-background" }
            assertEquals("#F2EDDE", background.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
            val sources = style.getValue("sources").jsonObject
            assertEquals(setOf("gt-vector-batumi", "gt-vector-kutaisi"), sources.keys)
            assertContains(sources.toString(), "https://bff.example/v1/map/vector/{z}/{x}/{y}.pbf")
            assertContains(style.getValue("glyphs").jsonPrimitive.content, "https://bff.example/v1/map/fonts/")
            assertFalse(body.contains("tiles.openfreemap.org"))
            val label = style.getValue("layers").jsonArray.first { layer ->
                layer.jsonObject["source-layer"]?.jsonPrimitive?.content == "transportation_name"
            }.jsonObject.getValue("layout").jsonObject.getValue("text-field").jsonArray
            val georgian = Json.parseToJsonElement("""["case",["==",["to-string",["get","name:ka"]],""],["get","name"],["get","name:ka"]]""")
            assertEquals(georgian, if (lang == "ka") label else label[2])
            assertEquals(Json.parseToJsonElement("""["get","name:$lang"]"""), label.last())
            assertContentEquals(Tile, client.get("/v1/map/vector/14/10086/6103.pbf").bodyAsBytes())
        }
        assertContentEquals(Tile, client.get("/v1/map/fonts/Noto%20Sans%20Regular/1024-1279.pbf").bodyAsBytes())
        assertEquals(
            listOf(
                "https://tiles.openfreemap.org/planet",
                "https://tiles.openfreemap.org/planet/20261115_080022_pt/14/10086/6103.pbf",
                "https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/1024-1279.pbf",
            ),
            upstream.requests,
        )
    }

    @Test
    fun invalidMetadataCannotChangeTheStyleOrRedirectTileRequests() = runBlocking {
        val upstream = FixtureDownloader(metadata = """{"tiles":["https://another.example/{z}/{x}/{y}.pbf"]}""")
        MapProxy(config, upstream).use { map ->
            assertContains(map.styleJson(MapLabelLanguage.Russian), "/v1/map/vector/{z}/{x}/{y}.pbf")
            assertEquals(emptyList(), upstream.requests)
            assertFailsWith<MapProxyFailure> { map.tile(14, 10_086, 6_103) }
            assertEquals(listOf("https://tiles.openfreemap.org/planet"), upstream.requests)
        }
    }

    @Test
    fun unsupportedTileAndGlyphRequestsNeverReachUpstream() = runBlocking {
        val upstream = FixtureDownloader()
        MapProxy(config, upstream).use { map ->
            assertFailsWith<IllegalArgumentException> { map.tile(14, 0, 0) }
            assertFailsWith<IllegalArgumentException> { map.tile(15, 20172, 12206) }
            assertFailsWith<IllegalArgumentException> { map.glyphs("Other font", "1024-1279") }
            assertFailsWith<IllegalArgumentException> { map.glyphs(MapFont, "1024-1280") }
            assertEquals(emptyList(), upstream.requests)
        }
    }

    private val config = BffConfig.fromEnvironment(
        mapOf(
            "BFF_MAP_ENABLED" to "true",
            "BFF_MAP_PUBLIC_BASE_URL" to "https://bff.example",
        ),
    ).map

    private class FixtureDownloader(
        private val metadata: String =
            """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf"]}""",
    ) : MapTileDownloader {
        val requests = mutableListOf<String>()
        override suspend fun download(address: String, format: MapAssetFormat, validators: MapTileValidators?): MapTileDownload {
            requests += address
            return MapTileDownload(if (address.endsWith("/planet")) metadata.encodeToByteArray() else Tile, null, null)
        }

        override fun close() = Unit
    }

    companion object {
        private val Tile = byteArrayOf(26, 0)
    }
}
