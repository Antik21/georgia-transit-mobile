package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.parsePublicId
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object BatumiStopNames {
    private const val MaximumCatalogDriftMeters = 75
    private const val ResourcePath = "/transit/batumi/stop-names.json"
    private val entries by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream(ResourcePath))
        Json.decodeFromString<Map<String, ReviewedStopName>>(resource.bufferedReader(Charsets.UTF_8).use { it.readText() })
            .also { directory ->
                require(directory.keys.all { parsePublicId(it, "batumi", PublicEntityType.Stop)?.provider == "theta" })
            }
    }

    fun find(id: String, code: String, position: GeoPoint): LocalizedText? =
        entries[id]?.takeIf { entry ->
            entry.code == code && greatCircleMeters(entry.position, position) <= MaximumCatalogDriftMeters
        }?.name

    @Serializable
    private data class ReviewedStopName(
        val code: String,
        val position: GeoPoint,
        val source: String,
        val name: LocalizedText,
    ) {
        init {
            require(code.isNotBlank() && source.startsWith("https://"))
            require(position.latitude in 41.45..41.85 && position.longitude in 41.40..41.95)
            val names = listOf(name.ru, name.en, name.ka, name.uk, name.de, name.fr, name.ar, name.hi, name.zh)
            require(names.all { !it.isNullOrBlank() && it == it.trim() })
        }
    }
}
