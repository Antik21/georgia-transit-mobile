package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object BatumiRouteNames {
    private val entries by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream("/transit/batumi/route-names.json"))
        val json = resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.decodeFromString<Map<String, RouteName>>(json)
    }

    fun apply(route: Route): Route {
        val entry = entries[route.id]?.takeIf { it.shortName == route.shortName } ?: return route
        return route.copy(longName = entry.name)
    }

    @Serializable
    private data class RouteName(val shortName: String, val name: LocalizedText) {
        init {
            val translations = listOf(name.ru, name.en, name.ka, name.uk, name.de, name.fr, name.ar, name.hi, name.zh, name.th)
            require(translations.all { !it.isNullOrBlank() && it == it.trim() && !it.equals(shortName, ignoreCase = true) })
        }
    }
}
