package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** A route shape with cumulative metres, used to cut per-stop-pair geometry slices. */
internal class MeasuredPolyline(val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size)

    init {
        require(points.size >= 2) { "A polyline needs at least two points" }
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + greatCircleMeters(points[index - 1], points[index])
        }
    }

    val lengthMeters: Double get() = cumulative.last()

    data class Projection(val along: Double, val offset: Double)

    /** The closest projection for each pass of the shape within range of a stop. */
    fun nearbyPasses(target: GeoPoint, maxDistanceMeters: Double): List<Projection> {
        val lonScale = MetersPerDegree * cos(target.latitude * PI / 180.0)
        val passes = mutableListOf<Projection>()
        var pass: Projection? = null
        for (index in 0 until points.size - 1) {
            val segmentStart = cumulative[index]
            val segmentEnd = cumulative[index + 1]
            if (segmentEnd <= segmentStart) continue
            val ax = (points[index].longitude - target.longitude) * lonScale
            val ay = (points[index].latitude - target.latitude) * MetersPerDegree
            val bx = (points[index + 1].longitude - target.longitude) * lonScale
            val by = (points[index + 1].latitude - target.latitude) * MetersPerDegree
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            if (distance <= maxDistanceMeters && (pass == null || distance < pass.offset)) {
                pass = Projection(segmentStart + t * (segmentEnd - segmentStart), distance)
            }
            // An out-of-range joint separates two visits even on consecutive shape edges.
            if (hypot(bx, by) > maxDistanceMeters) {
                pass?.let(passes::add)
                pass = null
            }
        }
        pass?.let(passes::add)
        return passes
    }

    fun slice(fromAlong: Double, toAlong: Double): List<GeoPoint> {
        require(toAlong >= fromAlong) { "A slice must run forward" }
        val result = mutableListOf(pointAt(fromAlong))
        for (index in points.indices) {
            if (cumulative[index] > fromAlong && cumulative[index] < toAlong) result += points[index]
        }
        result += pointAt(toAlong)
        return result
    }

    private fun pointAt(along: Double): GeoPoint {
        val clamped = along.coerceIn(0.0, lengthMeters)
        var index = cumulative.indexOfFirst { it >= clamped }.coerceAtLeast(1)
        if (index >= points.size) index = points.size - 1
        val start = cumulative[index - 1]
        val span = cumulative[index] - start
        val t = if (span <= 0.0) 0.0 else (clamped - start) / span
        val a = points[index - 1]
        val b = points[index]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    private companion object {
        const val MetersPerDegree = 111_320.0
    }
}
