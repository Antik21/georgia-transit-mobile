package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import com.denis.georgiatransit.bff.planning.MeasuredPolyline
import com.denis.georgiatransit.bff.planning.MeasuredPolyline.Projection
import com.denis.georgiatransit.bff.provider.chainMeters

/** Matches one ordered service run to either direction of the provider's route shape. */
internal class BatumiRouteShape(points: List<GeoPoint>) {
    private val closed = chainMeters(points.first(), points.last()) <= ClosedShapeToleranceMeters
    private val forward = Direction(points, closed)
    private val reverse = Direction(points.asReversed(), closed)

    fun match(stops: List<GeoPoint>): Match {
        val forwardMatch = forward.match(stops)
        val reverseMatch = reverse.match(stops)
        return if (reverseMatch.isBetterThan(forwardMatch)) reverseMatch else forwardMatch
    }

    internal class Match(
        private val shape: MeasuredPolyline,
        private val lapLength: Double?,
        private val anchors: List<Projection?>,
        private val score: Score,
    ) {
        fun isBetterThan(other: Match): Boolean = score.isBetterThan(other.score)

        fun segment(from: Int, to: Int): Segment? {
            val start = anchors[from]?.along ?: return null
            val end = anchors[to]?.along ?: return null
            val target = if (to == 0) end + (lapLength ?: return null) else end
            if (target <= start) return null
            return Segment(shape.slice(start, target), approximate = false, meters = target - start)
        }
    }

    internal class Segment(val points: List<GeoPoint>, val approximate: Boolean, val meters: Double)

    internal data class Score(val pairs: Int = 0, val stops: Int = 0, val cost: Double = 0.0) {
        fun isBetterThan(other: Score): Boolean = when {
            pairs != other.pairs -> pairs > other.pairs
            stops != other.stops -> stops > other.stops
            else -> cost < other.cost
        }
    }

    private class Anchor(val stop: Int, val projection: Projection, val previous: Anchor?, val score: Score)

    private class Direction(points: List<GeoPoint>, closed: Boolean) {
        private val shape = MeasuredPolyline(points)
        // Closed shapes can start in the middle of a service run. Unroll one seam.
        private val continuousShape = if (closed) MeasuredPolyline(points + points) else shape
        private val lapLength = if (closed) shape.lengthMeters + greatCircleMeters(points.last(), points.first()) else null

        fun match(stops: List<GeoPoint>): Match {
            val passesByStop = stops.map { shape.nearbyPasses(it, MaximumStopOffsetMeters) }
            val first = passesByStop.indexOfFirst { it.isNotEmpty() }
            val anchors = MutableList<Projection?>(stops.size) { null }
            if (first < 0) return Match(continuousShape, lapLength, anchors, Score())
            val windows = stops.zipWithNext { a, b -> maxOf(MinimumSearchWindowMeters, SearchWindowFactor * chainMeters(a, b)) }
            val best = passesByStop[first]
                .map { start -> align(passesByStop, windows, first, start) }
                .reduce { previous, candidate -> if (candidate.score.isBetterThan(previous.score)) candidate else previous }
            var anchor: Anchor? = best
            while (anchor != null) {
                anchors[anchor.stop] = anchor.projection
                anchor = anchor.previous
            }
            return Match(continuousShape, lapLength, anchors, best.score)
        }

        private fun align(
            passesByStop: List<List<Projection>>,
            windows: List<Double>,
            first: Int,
            start: Projection,
        ): Anchor {
            val initial = Anchor(first, start, null, Score(stops = 1, cost = start.offset))
            val reachable = mutableListOf(initial)
            val limit = lapLength?.let { start.along + it } ?: shape.lengthMeters
            var best = initial
            for (index in first + 1 until passesByStop.size) {
                // Keep the best alignment to each pass. Earlier rows remain reachable so a
                // missing or misordered stop does not prevent matching the rest of the route.
                val row = passesByStop[index].mapNotNull { pass ->
                    val projection = if (lapLength != null && pass.along < start.along) {
                        pass.copy(along = pass.along + lapLength)
                    } else pass
                    if (projection.along <= limit) bestAnchor(index, projection, reachable, windows[index - 1]) else null
                }
                for (anchor in row) {
                    if (anchor.score.isBetterThan(best.score)) best = anchor
                }
                reachable += row
            }
            return best
        }

        private fun bestAnchor(stop: Int, projection: Projection, reachable: List<Anchor>, window: Double): Anchor? {
            var best: Anchor? = null
            for (previous in reachable) {
                val travel = projection.along - previous.projection.along
                if (travel <= 0.0) continue
                val adjacent = previous.stop == stop - 1
                // Prefer short plausible arcs when both alignments cover the same stop pairs.
                val excess = if (adjacent) (travel - window).coerceAtLeast(0.0) else 0.0
                val score = Score(
                    pairs = previous.score.pairs + if (adjacent) 1 else 0,
                    stops = previous.score.stops + 1,
                    cost = previous.score.cost + projection.offset + excess * ExcessTravelWeight,
                )
                if (best == null || score.isBetterThan(best.score)) best = Anchor(stop, projection, previous, score)
            }
            return best
        }
    }

    private companion object {
        const val MaximumStopOffsetMeters = 60.0
        const val MinimumSearchWindowMeters = 500.0
        const val SearchWindowFactor = 4.0
        const val ClosedShapeToleranceMeters = 50.0
        // Prefer cleaner stop projections. A kilometre beyond the window adds one metre of cost.
        const val ExcessTravelWeight = 0.001
    }
}
