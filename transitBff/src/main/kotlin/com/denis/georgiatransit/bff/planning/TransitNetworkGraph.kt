package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

class TransitStop(val id: String, val position: GeoPoint)

/**
 * One rideable stop sequence. A ride may board at any position and alight at any later one, so a
 * provider must split sequences wherever a vehicle may leave service.
 */
class TransitPattern(
    val routeId: String,
    val directionId: String,
    val stops: IntArray,
    val segmentGeometry: List<List<GeoPoint>>,
    val segmentApproximate: BooleanArray,
    val segmentMeters: DoubleArray,
) {
    init {
        require(stops.size >= 2) { "A pattern needs at least two stops" }
        require(segmentGeometry.size == stops.size - 1) { "Every consecutive stop pair needs geometry" }
        require(segmentApproximate.size == stops.size - 1 && segmentMeters.size == stops.size - 1)
    }

    val size: Int get() = stops.size
}

data class PatternPosition(val pattern: Int, val position: Int)

data class StopDistance(val stop: Int, val straightMeters: Double)

/** Immutable planning graph; providers rebuild and swap it atomically with their catalog. */
class TransitNetworkGraph(
    val stops: List<TransitStop>,
    val patterns: List<TransitPattern>,
) {
    val stopIndex: Map<String, Int> = stops.withIndex().associate { (index, stop) -> stop.id to index }
    val stopPatterns: List<List<PatternPosition>>
    val walkNeighbours: List<IntArray>
    val walkNeighbourMeters: List<DoubleArray>
    private val grid: Map<Long, IntArray>
    private val cellLatitude = TransferCellDegrees
    private val cellLongitude: Double

    init {
        require(stopIndex.size == stops.size) { "Stop identifiers must be unique" }
        val byStop = List(stops.size) { mutableListOf<PatternPosition>() }
        patterns.forEachIndexed { patternIndex, pattern ->
            pattern.stops.forEachIndexed { position, stop -> byStop[stop] += PatternPosition(patternIndex, position) }
        }
        stopPatterns = byStop
        val meanLatitude = if (stops.isEmpty()) 0.0 else stops.sumOf { it.position.latitude } / stops.size
        cellLongitude = TransferCellDegrees / cos(meanLatitude * PI / 180.0).coerceAtLeast(0.1)
        grid = stops.indices.groupBy { cellKey(stops[it].position, 0, 0) }.mapValues { (_, value) -> value.toIntArray() }
        val neighbours = stops.indices.map { index ->
            nearby(stops[index].position, PlannerParameters.TransferRadiusMeters).filter { it.stop != index }
        }
        walkNeighbours = neighbours.map { list -> IntArray(list.size) { list[it].stop } }
        walkNeighbourMeters = neighbours.map { list ->
            DoubleArray(list.size) { list[it].straightMeters * PlannerParameters.WalkDetourFactor }
        }
    }

    /** Straight-line candidates; radii above the grid cell size fall back to a linear scan. */
    fun stopsWithin(point: GeoPoint, radiusMeters: Double): List<StopDistance> =
        if (radiusMeters <= TransferCellMeters) {
            nearby(point, radiusMeters)
        } else {
            stops.indices.mapNotNull { index ->
                val meters = greatCircleMeters(point, stops[index].position)
                if (meters <= radiusMeters) StopDistance(index, meters) else null
            }
        }

    private fun nearby(point: GeoPoint, radiusMeters: Double): List<StopDistance> {
        val result = mutableListOf<StopDistance>()
        for (latOffset in -1..1) {
            for (lonOffset in -1..1) {
                grid[cellKey(point, latOffset, lonOffset)]?.forEach { index ->
                    val meters = greatCircleMeters(point, stops[index].position)
                    if (meters <= radiusMeters) result += StopDistance(index, meters)
                }
            }
        }
        return result
    }

    private fun cellKey(point: GeoPoint, latOffset: Int, lonOffset: Int): Long {
        val lat = floor(point.latitude / cellLatitude).toLong() + latOffset
        val lon = floor(point.longitude / cellLongitude).toLong() + lonOffset
        return (lat shl 32) xor (lon and 0xffffffffL)
    }

    private companion object {
        const val TransferCellMeters = 700.0
        const val TransferCellDegrees = TransferCellMeters / 111_320.0
    }
}
