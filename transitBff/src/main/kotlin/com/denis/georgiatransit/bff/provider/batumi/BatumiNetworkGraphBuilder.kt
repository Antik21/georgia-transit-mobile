package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.planning.MeasuredPolyline
import com.denis.georgiatransit.bff.planning.TransitNetworkGraph
import com.denis.georgiatransit.bff.planning.TransitPattern
import com.denis.georgiatransit.bff.planning.TransitStop
import com.denis.georgiatransit.bff.provider.BatumiCatalog
import com.denis.georgiatransit.bff.provider.RouteCycle
import com.denis.georgiatransit.bff.provider.chainMeters
import java.time.Instant

/** Batumi-only metadata that maps a generic pattern position back onto the route's ADR 0012 cycle. */
internal class BatumiPatternMeta(
    val routeId: String,
    val chainIndices: IntArray,
    /** Positions at or after this index belong to the second lap of a doubled circle pattern. */
    val secondLapStart: Int,
    val circle: Boolean,
)

internal class BatumiPlanningNetwork(
    val graph: TransitNetworkGraph,
    val patterns: List<BatumiPatternMeta>,
    val catalog: BatumiCatalog,
    val publishedAt: Instant,
)

/**
 * One pattern per route `Status` block, so rides never cross a terminal where the bus may leave
 * service. `RouteIsCircle` routes get one doubled lap instead, which lets a ride wrap the terminal.
 */
internal object BatumiNetworkGraphBuilder {
    private const val MaximumStopOffsetMeters = 60.0
    private const val MinimumSearchWindowMeters = 500.0
    private const val SearchWindowFactor = 4.0
    private const val ClosedShapeToleranceMeters = 50.0

    fun build(catalog: BatumiCatalog, publishedAt: Instant): BatumiPlanningNetwork {
        val stops = catalog.stops.map { TransitStop(it.id, it.position) }
        val stopIndex = stops.withIndex().associate { (index, stop) -> stop.id to index }
        val patterns = mutableListOf<TransitPattern>()
        val metas = mutableListOf<BatumiPatternMeta>()
        catalog.routes.forEach { route ->
            val cycle = catalog.cycle(route.id)
            val shape = MeasuredPolyline(catalog.geometry(route.id))
            val anchors = anchorStops(shape, cycle)
            val circle = route.id in catalog.circleRouteIds
            val runs = if (circle) listOf(doubledLap(cycle.chain.size)) else statusBlocks(cycle)
            runs.filter { it.size >= 2 }.forEach { chainIndices ->
                val segments = chainIndices.toList().zipWithNext { from, to ->
                    segment(shape, cycle, anchors, from, to)
                }
                patterns += TransitPattern(
                    routeId = route.id,
                    directionId = route.directions.single().id,
                    stops = IntArray(chainIndices.size) { stopIndex.getValue(cycle.chain[chainIndices[it]].stop.id) },
                    segmentGeometry = segments.map(Segment::points),
                    segmentApproximate = BooleanArray(segments.size) { segments[it].approximate },
                    segmentMeters = DoubleArray(segments.size) { segments[it].meters },
                )
                metas += BatumiPatternMeta(
                    routeId = route.id,
                    chainIndices = chainIndices,
                    secondLapStart = if (circle) cycle.chain.size else chainIndices.size,
                    circle = circle,
                )
            }
        }
        return BatumiPlanningNetwork(TransitNetworkGraph(stops, patterns), metas, catalog, publishedAt)
    }

    private fun doubledLap(size: Int): IntArray = IntArray(2 * size - 1) { it % size }

    private fun statusBlocks(cycle: RouteCycle): List<IntArray> =
        cycle.chain.indices.groupBy { cycle.chain[it].status }.values.map(List<Int>::toIntArray)

    private fun anchorStops(shape: MeasuredPolyline, cycle: RouteCycle): Array<Double?> {
        val anchors = arrayOfNulls<Double>(cycle.chain.size)
        var minAlong = 0.0
        cycle.chain.forEachIndexed { index, point ->
            val window = if (index == 0) {
                shape.lengthMeters
            } else {
                maxOf(MinimumSearchWindowMeters, SearchWindowFactor * chainMeters(cycle.chain[index - 1].stop.position, point.stop.position))
            }
            val along = shape.projectForward(
                target = point.stop.position,
                minAlong = minAlong,
                maxAlong = minAlong + window,
                maxDistanceMeters = MaximumStopOffsetMeters,
                preferEarliest = index == 0,
            )
            anchors[index] = along
            if (along != null) minAlong = along
        }
        return anchors
    }

    private fun segment(
        shape: MeasuredPolyline,
        cycle: RouteCycle,
        anchors: Array<Double?>,
        from: Int,
        to: Int,
    ): Segment {
        val start = anchors[from]
        val end = anchors[to]
        val fromPoint = cycle.chain[from].stop.position
        val toPoint = cycle.chain[to].stop.position
        if (start != null && end != null) {
            if (to == from + 1 && end > start) return Segment(shape.slice(start, end), approximate = false, meters = end - start)
            val closed = chainMeters(shape.points.first(), shape.points.last()) <= ClosedShapeToleranceMeters
            if (to == 0 && from == cycle.chain.lastIndex && closed) {
                val points = shape.slice(start, shape.lengthMeters) + shape.slice(0.0, end).drop(1)
                return Segment(points, approximate = false, meters = shape.lengthMeters - start + end)
            }
        }
        return Segment(listOf(fromPoint, toPoint), approximate = true, meters = chainMeters(fromPoint, toPoint))
    }

    private class Segment(val points: List<GeoPoint>, val approximate: Boolean, val meters: Double)
}
