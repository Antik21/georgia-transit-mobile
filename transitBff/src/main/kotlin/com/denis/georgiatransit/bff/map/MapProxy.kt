package com.denis.georgiatransit.bff.map

import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.MapBounds
import com.denis.georgiatransit.bff.config.MapProxyActivationConfig
import java.time.Clock
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

internal const val MinimumMapZoom = 8
internal enum class MapArea(val west: Double, val south: Double, val east: Double, val north: Double) {
    Batumi(41.35, 41.30, 42.20, 41.95),
    Borjomi(43.32, 41.80, 43.49, 41.96),
    Zugdidi(41.48, 42.32, 42.07, 42.68),
    Kutaisi(42.57, 42.17, 42.77, 42.35);

    val bounds get() = MapBounds(west, south, east, north)
}

/** Serves the localized basemap and restricts vector requests to active city coverage. */
internal class MapProxy(
    config: MapProxyActivationConfig,
    private val downloader: MapTileDownloader = HttpMapTileDownloader(),
    clock: Clock = Clock.systemUTC(),
    private val areas: Set<MapArea> = setOf(MapArea.Batumi),
) : AutoCloseable {
    private val publicOrigin = requireNotNull(config.publicBaseUrl).toString().trimEnd('/')
    private val openFreeMap = OpenFreeMap(MapAssetCache(downloader, clock), clock)

    fun withCityCoverage(cities: List<City>): List<City> = cities.map { city ->
        if (city.id == KnownCityIds.Kutaisi && city.capabilities.stops) city.copy(mapBounds = MapArea.Kutaisi.bounds)
        else city
    }

    private fun activeAreas(kutaisiEnabled: Boolean): Set<MapArea> =
        if (kutaisiEnabled) areas + MapArea.Kutaisi else areas

    fun styleJson(language: MapLabelLanguage = MapLabelLanguage.Georgian, kutaisiEnabled: Boolean = false): String =
        localizedStyle(publicOrigin, language, activeAreas(kutaisiEnabled))

    suspend fun tile(z: Int, x: Int, y: Int, kutaisiEnabled: Boolean = false): ByteArray {
        validate(z, x, y, activeAreas(kutaisiEnabled))
        return openFreeMap.tile(z, x, y)
    }

    suspend fun glyphs(font: String, range: String): ByteArray = openFreeMap.glyphs(font, range)

    override fun close() = downloader.close()

    companion object {
        fun validate(z: Int, x: Int, y: Int, areas: Set<MapArea> = setOf(MapArea.Batumi)) {
            if (z !in MinimumMapZoom..VectorMapMaxZoom) {
                throw IllegalArgumentException("tile z must be between $MinimumMapZoom and $VectorMapMaxZoom")
            }
            val edge = 1 shl z
            if (x !in 0 until edge || y !in 0 until edge) throw IllegalArgumentException("tile coordinates are out of range")
            val covered = areas.any { area ->
                x in longitudeTileX(area.west, z)..longitudeTileX(area.east, z) &&
                    y in latitudeTileY(area.north, z)..latitudeTileY(area.south, z)
            }
            if (!covered) throw IllegalArgumentException("tile coordinates are outside the configured map area")
        }

        private fun longitudeTileX(longitude: Double, zoom: Int): Int {
            val edge = 1 shl zoom
            return floor((longitude + 180.0) / 360.0 * edge).toInt().coerceIn(0, edge - 1)
        }

        private fun latitudeTileY(latitude: Double, zoom: Int): Int {
            val edge = 1 shl zoom
            val radians = latitude * PI / 180.0
            return floor((1.0 - asinh(tan(radians)) / PI) / 2.0 * edge).toInt().coerceIn(0, edge - 1)
        }
    }
}
