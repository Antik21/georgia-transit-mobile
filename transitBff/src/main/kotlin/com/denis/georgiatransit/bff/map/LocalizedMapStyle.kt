package com.denis.georgiatransit.bff.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val Attribution = """<a href="https://openfreemap.org/">OpenFreeMap</a> <a href="https://openmaptiles.org/">© OpenMapTiles</a> <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>"""
private val BasemapLayers = requireNotNull(MapProxy::class.java.getResourceAsStream("/map/natural-layers.json"))
    .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray.map { layer -> layer.jsonObject } }

internal fun localizedStyle(origin: String, language: MapLabelLanguage, areas: Set<MapArea>): String {
    val sourceIds = areas.sortedBy(MapArea::ordinal).associateWith { "gt-vector-${it.name.lowercase()}" }
    val text = labelExpression(language)
    return buildJsonObject {
        put("version", 8)
        put("name", "Georgia Transit · OpenFreeMap Natural")
        put("glyphs", "$origin/v1/map/fonts/{fontstack}/{range}.pbf")
        put("sources", buildJsonObject {
            sourceIds.forEach { (area, id) ->
                put(id, buildJsonObject {
                    put("type", "vector")
                    put("tiles", buildJsonArray { add("$origin/v1/map/vector/{z}/{x}/{y}.pbf") })
                    put("minzoom", MinimumMapZoom)
                    put("maxzoom", VectorMapMaxZoom)
                    put("bounds", buildJsonArray {
                        add(area.west)
                        add(area.south)
                        add(area.east)
                        add(area.north)
                    })
                    put("attribution", Attribution)
                })
            }
        })
        put("layers", buildJsonArray {
            BasemapLayers.forEach { layer ->
                if ("source-layer" in layer) {
                    sourceIds.values.forEach { source -> add(layer.forSource(source, text)) }
                } else {
                    add(layer)
                }
            }
        })
    }.toString()
}

private fun JsonObject.forSource(source: String, text: JsonArray): JsonObject = buildJsonObject {
    this@forSource.forEach { (key, value) -> put(key, value) }
    put("id", "$source-${getValue("id").jsonPrimitive.content}")
    put("source", source)
    if (getValue("type").jsonPrimitive.content == "symbol") {
        put("layout", buildJsonObject {
            this@forSource.getValue("layout").jsonObject.forEach { (key, value) -> put(key, value) }
            put("text-field", text)
            put("text-font", buildJsonArray { add(MapFont) })
        })
    }
}

private fun labelExpression(language: MapLabelLanguage): JsonArray {
    val local = nonEmptyName("name:ka", property("name"))
    return if (language == MapLabelLanguage.Georgian) local else nonEmptyName("name:${language.code}", local)
}

private fun nonEmptyName(key: String, fallback: JsonElement): JsonArray = JsonArray(
    listOf(
        JsonPrimitive("case"),
        JsonArray(listOf(JsonPrimitive("=="), JsonArray(listOf(JsonPrimitive("to-string"), property(key))), JsonPrimitive(""))),
        fallback,
        property(key),
    ),
)

private fun property(key: String) = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(key)))
