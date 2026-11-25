package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.LocalizedText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shares the published route directory with the landing page. */
internal object KutaisiRouteNames {
    private val routes by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream("/transit/kutaisi/route-names.json"))
        resource.bufferedReader().use { Json.decodeFromString<List<Entry>>(it.readText()) }.associateBy { it.id }
    }

    fun name(id: String, number: String): LocalizedText? = routes[id]?.takeIf { it.number == number }?.name

    private val thaiLabels by lazy {
        buildMap {
            for (route in routes.values) {
                val thai = requireNotNull(route.name.th).split(" → ")
                for (source in listOf(route.name.en, route.name.ka)) {
                    val stops = source.split(" → ")
                    require(stops.size == 2 && thai.size == 2)
                    put(source, requireNotNull(route.name.th))
                    put(stops.first(), thai.first())
                    put(stops.last(), thai.last())
                }
            }
        }
    }

    fun thaiLabel(source: String): String? = thaiLabels[source]

    @Serializable
    private data class Entry(val id: String, val number: String, val name: LocalizedText)
}
