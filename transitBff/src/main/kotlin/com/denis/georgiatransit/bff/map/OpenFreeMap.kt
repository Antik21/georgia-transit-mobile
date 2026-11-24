package com.denis.georgiatransit.bff.map

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val VectorMapMaxZoom = 14
internal const val VectorTileCacheSeconds = 3_600
internal const val GlyphCacheSeconds = 604_800
private const val OpenFreeMapOrigin = "https://tiles.openfreemap.org"
private val TileTemplate = Regex(
    """https://tiles\.openfreemap\.org/planet/\d{8}_\d{6}_pt/\{z\}/\{x\}/\{y\}\.pbf""",
)
private val TileJson = Json { ignoreUnknownKeys = true }
private val TileLifetime = Duration.ofSeconds(VectorTileCacheSeconds.toLong())
private val GlyphLifetime = Duration.ofSeconds(GlyphCacheSeconds.toLong())
internal const val MapFont = "Noto Sans Regular"

internal enum class MapLabelLanguage(val code: String) {
    Georgian("ka"), Russian("ru"), English("en");

    companion object {
        fun fromCode(value: String) = entries.firstOrNull { it.code == value }
    }
}

/** Uses a fixed OpenFreeMap host and follows its published TileJSON release without forwarding client input. */
internal class OpenFreeMap(
    private val cache: MapAssetCache,
    private val clock: Clock,
) {
    private val releaseLock = Mutex()
    private var template: String? = null
    private var refreshAt: Instant = Instant.MIN

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray {
        val address = tileTemplate()?.tileAddress(z, x, y) ?: throw MapProxyFailure("vector tiles are unavailable")
        return cache.get(address, MapAssetFormat.Vector, TileLifetime)
    }

    suspend fun glyphs(font: String, range: String): ByteArray {
        val start = range.substringBefore('-').toIntOrNull()
        require(font == MapFont && start != null && start in 0..65_280 &&
            start % 256 == 0 && range == "$start-${start + 255}") { "invalid map glyph range or font" }
        return cache.get("$OpenFreeMapOrigin/fonts/Noto%20Sans%20Regular/$range.pbf", MapAssetFormat.Glyph, GlyphLifetime)
    }

    private suspend fun tileTemplate(): String? = releaseLock.withLock {
        if (clock.instant().isBefore(refreshAt)) return@withLock template
        template = try {
            val metadata = cache.get("$OpenFreeMapOrigin/planet", MapAssetFormat.TileJson, TileLifetime)
            TileJson.decodeFromString<OpenFreeMapMetadata>(metadata.decodeToString()).tiles
                .firstOrNull(TileTemplate::matches)
        } catch (_: MapProxyFailure) {
            null
        } catch (_: SerializationException) {
            null
        }
        refreshAt = clock.instant().plus(if (template == null) Duration.ofSeconds(15) else TileLifetime)
        template
    }
}

@Serializable
private data class OpenFreeMapMetadata(val tiles: List<String>)

private fun String.tileAddress(z: Int, x: Int, y: Int) =
    replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
