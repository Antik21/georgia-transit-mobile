package com.denis.georgiatransit.bff.geo

import com.denis.georgiatransit.bff.api.GeoPoint
import kotlin.math.round

private const val PolylineScale = 1e5

internal fun encodePolyline(points: List<GeoPoint>): String {
    var lastLat = 0
    var lastLon = 0
    return buildString {
        points.forEach { point ->
            val lat = round(point.latitude * PolylineScale).toInt()
            val lon = round(point.longitude * PolylineScale).toInt()
            appendPolylineValue(lat - lastLat)
            appendPolylineValue(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
    }
}

internal fun decodePolyline(value: String): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    var index = 0
    var lat = 0
    var lon = 0
    fun next(): Int {
        var result = 0
        var shift = 0
        while (true) {
            require(index < value.length) { "Truncated polyline" }
            val chunk = value[index++].code - 63
            result = result or ((chunk and 0x1f) shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
    while (index < value.length) {
        lat += next()
        lon += next()
        points += GeoPoint(lat / PolylineScale, lon / PolylineScale)
    }
    return points
}

private fun StringBuilder.appendPolylineValue(value: Int) {
    var encoded = value shl 1
    if (value < 0) encoded = encoded.inv()
    while (encoded >= 0x20) {
        append(((0x20 or (encoded and 0x1f)) + 63).toChar())
        encoded = encoded shr 5
    }
    append((encoded + 63).toChar())
}
