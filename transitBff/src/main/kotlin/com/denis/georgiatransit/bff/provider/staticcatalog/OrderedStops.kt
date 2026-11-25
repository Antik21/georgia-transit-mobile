package com.denis.georgiatransit.bff.provider.staticcatalog

import com.denis.georgiatransit.bff.api.GeoPoint
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.useLines

internal data class CatalogStop(
    val id: String,
    val sourceName: String,
    val position: GeoPoint,
)

internal data class CatalogDirection(
    val route: Int,
    val direction: Int,
    val stops: List<CatalogStop>,
) {
    val isLoop: Boolean get() = stops.first().id == stops.last().id
}

private val columns = listOf(
    "route", "direction", "origin", "destination", "sequence", "yandex_stop_id", "stop_name",
    "latitude", "longitude", "source_url",
)
private val csvField = Regex("""(?:^|,)(?:"((?:[^"]|"")*)"|([^",]*))(?=,|$)""")

/** Input is the existing ordered-stop export. Quoted fields may contain commas and escaped quotes. */
internal fun readCatalogDirections(path: Path): List<CatalogDirection> {
    try {
        return path.useLines { lines ->
            val rows = lines.iterator()
            require(rows.hasNext() && parseFields(rows.next().removePrefix("\uFEFF")) == columns) {
                "Invalid static catalog CSV header"
            }
            val directions = linkedMapOf<Pair<Int, Int>, MutableList<CatalogStop>>()
            val stopsById = mutableMapOf<String, CatalogStop>()
            rows.asSequence().forEachIndexed { index, line ->
                val fields = parseFields(line)
                val number = index + 2
                require(fields.size == columns.size) { "Invalid static catalog CSV row $number" }
                val route = positiveNumber(fields[0], number)
                val direction = positiveNumber(fields[1], number)
                val sequence = positiveNumber(fields[4], number)
                val id = fields[5]
                val latitude = fields[7].toDoubleOrNull()
                val longitude = fields[8].toDoubleOrNull()
                require(id.isNotEmpty() && id.all { it in '0'..'9' } && fields[6].isNotBlank() &&
                    latitude != null && latitude in -90.0..90.0 && longitude != null && longitude in -180.0..180.0
                ) { "Invalid static catalog stop on row $number" }
                val stop = CatalogStop(id, fields[6], GeoPoint(latitude, longitude))
                require((stopsById.putIfAbsent(id, stop) ?: stop) == stop) {
                    "Conflicting static catalog stop on row $number"
                }
                val ordered = directions.getOrPut(route to direction) { mutableListOf() }
                require(sequence == ordered.size + 1) { "Invalid static catalog sequence on row $number" }
                ordered.add(stop)
            }
            require(directions.isNotEmpty()) { "static catalog CSV has no directions" }
            directions.map { (key, stops) ->
                require(stops.size >= 2) { "static catalog route ${key.first}, direction ${key.second} has too few stops" }
                val uniqueStops = if (stops.first().id == stops.last().id) stops.dropLast(1) else stops
                require(uniqueStops.distinctBy(CatalogStop::id).size == uniqueStops.size) {
                    "static catalog route ${key.first}, direction ${key.second} repeats a stop"
                }
                CatalogDirection(key.first, key.second, stops)
            }.sortedWith(compareBy(CatalogDirection::route, CatalogDirection::direction))
        }
    } catch (exception: IOException) {
        throw IllegalArgumentException("Cannot read the configured static catalog CSV", exception)
    }
}

private fun positiveNumber(field: String, row: Int): Int = requireNotNull(field.toIntOrNull()?.takeIf { it > 0 }) {
    "Invalid static catalog number on row $row"
}

private fun parseFields(line: String): List<String> {
    val matches = csvField.findAll(line).toList()
    require(matches.sumOf { it.value.length } == line.length) { "Invalid CSV quoting in static catalog" }
    return matches.map { match ->
        match.groups[1]?.value?.replace("\"\"", "\"") ?: match.groupValues[2]
    }
}
