package com.denis.georgiatransit.bff.geo

import com.denis.georgiatransit.bff.api.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val EarthRadiusMeters = 6_371_008.8

fun haversineMeters(first: GeoPoint, second: GeoPoint): Int {
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return (2 * EarthRadiusMeters * asin(sqrt(a))).roundToInt()
}
