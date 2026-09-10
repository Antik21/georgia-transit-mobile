package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A route polyline prepared once per geometry revision for inexpensive animation frames. */
internal class PreparedVehicleRoutePath private constructor(
    val routeId: RouteId,
    val directionId: DirectionId?,
    private val points: List<GeoPoint>,
    private val cumulativeMeters: DoubleArray,
    val lengthMeters: Double,
    val isLoop: Boolean,
) {
    fun projections(point: GeoPoint): List<VehicleRouteProjection> {
        val ranked = points.zipWithNext().mapIndexedNotNull { index, (start, end) ->
            projectOnSegment(point, start, end, cumulativeMeters[index], cumulativeMeters[index + 1])
        }.sortedBy(VehicleRouteProjection::distanceMeters)
        val closest = ranked.firstOrNull() ?: return emptyList()
        val retained = mutableListOf<VehicleRouteProjection>()
        ranked.forEach { candidate ->
            if (
                candidate.distanceMeters <= MAX_PROJECTION_METERS &&
                candidate.distanceMeters - closest.distanceMeters <= PROJECTION_CANDIDATE_WINDOW_METERS &&
                retained.none { existing ->
                    alongSeparation(candidate.alongMeters, existing.alongMeters) <= PROJECTION_EQUIVALENT_ALONG_METERS
                }
            ) {
                retained += candidate
            }
        }
        return retained.take(MAX_PROJECTION_CANDIDATES)
    }

    fun positionAt(alongMeters: Double): GeoPoint {
        val target = if (isLoop) alongMeters.positiveModulo(lengthMeters) else alongMeters.coerceIn(0.0, lengthMeters)
        if (target <= 0.0) return points.first()
        if (!isLoop && target >= lengthMeters) return points.last()
        var low = 0
        var high = cumulativeMeters.lastIndex - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulativeMeters[mid + 1] < target) low = mid + 1 else high = mid
        }
        val startDistance = cumulativeMeters[low]
        val segmentLength = cumulativeMeters[low + 1] - startDistance
        val fraction = if (segmentLength <= MIN_SEGMENT_METERS) 0.0 else ((target - startDistance) / segmentLength).coerceIn(0.0, 1.0)
        return interpolate(points[low], points[low + 1], fraction)
    }

    fun alongSeparation(first: Double, second: Double): Double {
        val direct = abs(first - second)
        return if (isLoop) minOf(direct, lengthMeters - direct) else direct
    }

    companion object {
        fun from(polyline: MapPolyline): PreparedVehicleRoutePath? {
            if (polyline.points.size < 2) return null
            val retained = mutableListOf(polyline.points.first())
            val cumulative = mutableListOf(0.0)
            polyline.points.drop(1).forEach { point ->
                val segment = distanceMeters(retained.last(), point)
                if (segment >= MIN_SEGMENT_METERS) {
                    retained += point
                    cumulative += cumulative.last() + segment
                }
            }
            val length = cumulative.lastOrNull() ?: return null
            if (retained.size < 2 || length < MIN_PATH_METERS) return null
            return PreparedVehicleRoutePath(
                routeId = polyline.routeId,
                directionId = polyline.directionId,
                points = retained,
                cumulativeMeters = cumulative.toDoubleArray(),
                lengthMeters = length,
                isLoop = distanceMeters(retained.first(), retained.last()) <= LOOP_CLOSURE_METERS,
            )
        }
    }
}

internal data class VehicleRouteProjection(
    val alongMeters: Double,
    val distanceMeters: Double,
    val point: GeoPoint,
)

/** One immutable movement segment; frame lookup is logarithmic in route vertex count. */
internal data class VehicleRouteMotion(
    val path: PreparedVehicleRoutePath,
    val startAlongMeters: Double,
    val deltaMeters: Double,
    val score: Double,
) {
    val endAlongMeters: Double get() = startAlongMeters + deltaMeters
    val endPoint: GeoPoint get() = path.positionAt(startAlongMeters + deltaMeters)

    fun positionAt(fraction: Double): GeoPoint =
        path.positionAt(startAlongMeters + deltaMeters * fraction.coerceIn(0.0, 1.0))
}

internal object VehicleRoutePathInterpolator {
    fun prepare(polylines: List<MapPolyline>): Map<RouteId, List<PreparedVehicleRoutePath>> =
        polylines.mapNotNull(PreparedVehicleRoutePath::from).groupBy(PreparedVehicleRoutePath::routeId)

    fun motion(
        from: GeoPoint,
        to: GeoPoint,
        directionId: DirectionId?,
        candidates: List<PreparedVehicleRoutePath>,
        previousMotion: VehicleRouteMotion? = null,
    ): VehicleRouteMotion? {
        val exactDirection = directionId?.let { requested -> candidates.filter { it.directionId == requested } }.orEmpty()
        val scoped = exactDirection.takeIf(List<PreparedVehicleRoutePath>::isNotEmpty) ?: candidates
        val ranked = scoped.mapNotNull { path -> motionOnPath(from, to, path, previousMotion) }.sortedBy(VehicleRouteMotion::score)
        val best = ranked.firstOrNull() ?: return null
        if (
            previousMotion == null && exactDirection.isEmpty() &&
            ranked.getOrNull(1)?.let { it.score - best.score < PATH_AMBIGUITY_METERS } == true
        ) {
            return null
        }
        return best
    }

    private fun motionOnPath(
        from: GeoPoint,
        to: GeoPoint,
        path: PreparedVehicleRoutePath,
        previousMotion: VehicleRouteMotion?,
    ): VehicleRouteMotion? {
        val starts = path.projections(from)
        val ends = path.projections(to)
        if (starts.isEmpty() || ends.isEmpty()) return null
        val directDistance = distanceMeters(from, to)
        val continuityAlong = previousMotion?.takeIf { it.path === path }?.endAlongMeters
        return starts.flatMap { start ->
            ends.mapNotNull { end ->
                var delta = end.alongMeters - start.alongMeters
                if (path.isLoop && abs(delta) > path.lengthMeters / 2.0) {
                    delta += if (delta > 0.0) -path.lengthMeters else path.lengthMeters
                }
                if (
                    abs(delta) > maxOf(
                        MAX_MINIMUM_PATH_MOVEMENT_METERS,
                        directDistance * MAX_PATH_TO_DIRECT_RATIO + PATH_DISTANCE_ALLOWANCE_METERS,
                    )
                ) {
                    return@mapNotNull null
                }
                val continuityPenalty = continuityAlong?.let { priorAlong ->
                    path.alongSeparation(start.alongMeters, priorAlong) * CONTINUITY_SCORE_WEIGHT
                } ?: 0.0
                VehicleRouteMotion(
                    path = path,
                    startAlongMeters = start.alongMeters,
                    deltaMeters = delta,
                    score = start.distanceMeters + end.distanceMeters +
                        abs(abs(delta) - directDistance) * MOVEMENT_SCORE_WEIGHT + continuityPenalty,
                )
            }
        }.minByOrNull(VehicleRouteMotion::score)
    }
}

private fun projectOnSegment(
    target: GeoPoint,
    start: GeoPoint,
    end: GeoPoint,
    startAlongMeters: Double,
    endAlongMeters: Double,
): VehicleRouteProjection? {
    val latitudeScale = METERS_PER_LATITUDE_DEGREE
    val longitudeScale = latitudeScale * cos(target.latitude * PI / 180.0)
    if (abs(longitudeScale) < MIN_LONGITUDE_SCALE) return null
    val startX = (start.longitude - target.longitude) * longitudeScale
    val startY = (start.latitude - target.latitude) * latitudeScale
    val endX = (end.longitude - target.longitude) * longitudeScale
    val endY = (end.latitude - target.latitude) * latitudeScale
    val dx = endX - startX
    val dy = endY - startY
    val denominator = dx * dx + dy * dy
    if (denominator <= MIN_SEGMENT_METERS * MIN_SEGMENT_METERS) return null
    val fraction = ((-startX * dx - startY * dy) / denominator).coerceIn(0.0, 1.0)
    val projected = interpolate(start, end, fraction)
    return VehicleRouteProjection(
        alongMeters = startAlongMeters + (endAlongMeters - startAlongMeters) * fraction,
        distanceMeters = hypot(startX + dx * fraction, startY + dy * fraction),
        point = projected,
    )
}

private fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint = GeoPoint(
    latitude = from.latitude + (to.latitude - from.latitude) * fraction,
    longitude = from.longitude + (to.longitude - from.longitude) * fraction,
)

private fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
    val latitudeDelta = (to.latitude - from.latitude) * PI / 180.0
    val longitudeDelta = (to.longitude - from.longitude) * PI / 180.0
    val firstLatitude = from.latitude * PI / 180.0
    val secondLatitude = to.latitude * PI / 180.0
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0).let { it * it }
    return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun Double.positiveModulo(divisor: Double): Double = ((this % divisor) + divisor) % divisor

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
private const val MIN_LONGITUDE_SCALE = 1.0
private const val MIN_SEGMENT_METERS = 0.25
private const val MIN_PATH_METERS = 1.0
private const val LOOP_CLOSURE_METERS = 50.0
private const val MAX_PROJECTION_METERS = 120.0
private const val PROJECTION_CANDIDATE_WINDOW_METERS = 20.0
private const val PROJECTION_EQUIVALENT_ALONG_METERS = 50.0
private const val MAX_PROJECTION_CANDIDATES = 8
private const val PATH_AMBIGUITY_METERS = 12.0
private const val MAX_MINIMUM_PATH_MOVEMENT_METERS = 350.0
private const val MAX_PATH_TO_DIRECT_RATIO = 5.0
private const val PATH_DISTANCE_ALLOWANCE_METERS = 80.0
private const val MOVEMENT_SCORE_WEIGHT = 0.25
private const val CONTINUITY_SCORE_WEIGHT = 2.0
