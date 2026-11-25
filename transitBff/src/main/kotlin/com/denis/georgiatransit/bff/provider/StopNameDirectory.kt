package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.parsePublicId
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class StopNameDirectory(
    resourcePath: String,
    cityId: String,
    provider: String,
    latitudeRange: ClosedFloatingPointRange<Double>,
    longitudeRange: ClosedFloatingPointRange<Double>,
) {
    private val entries by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream(resourcePath))
        val json = resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.decodeFromString<Map<String, ReviewedStopName>>(json).also { directory ->
            directory.forEach { (id, entry) ->
                require(parsePublicId(id, cityId, PublicEntityType.Stop)?.provider == provider)
                require(entry.position.latitude in latitudeRange && entry.position.longitude in longitudeRange)
            }
        }
    }

    fun find(id: String, code: String, position: GeoPoint): LocalizedText? =
        entries[id]?.takeIf { entry ->
            entry.code == code && greatCircleMeters(entry.position, position) <= MaximumCatalogDriftMeters
        }?.name

    /** AZRY timetable rows carry the stop ID and provider label without a stop code or coordinates. */
    fun findForTimetable(id: String, providerLabel: String): LocalizedText? {
        val name = entries[id]?.name ?: return null
        val label = providerLabel.normalizedSpacing()
        return name.takeIf { label == it.en.normalizedSpacing() || label == it.ka.normalizedSpacing() }
    }

    @Serializable
    private data class ReviewedStopName(
        val code: String,
        val position: GeoPoint,
        val source: String,
        val name: LocalizedText,
    ) {
        init {
            require(code.isNotBlank() && source.startsWith("https://"))
            val names = listOf(name.ru, name.en, name.ka, name.uk, name.de, name.fr, name.ar, name.hi, name.zh, name.th)
            require(names.all { !it.isNullOrBlank() && it == it.trim() })
        }
    }
}

private const val MaximumCatalogDriftMeters = 75
private val Whitespace = Regex("\\s+")

private fun String.normalizedSpacing(): String = trim().replace(Whitespace, " ")
