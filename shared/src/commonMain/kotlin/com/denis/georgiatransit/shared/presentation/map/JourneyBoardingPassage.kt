package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.distanceMetersTo
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import kotlin.math.abs
import kotlin.time.Instant

/** Confirms forward travel across the boarding stop from two ordered GPS observations. */
internal class JourneyBoardingPassage(private val boarding: JourneyBoarding) {
    private data class Observation(val point: GeoPoint, val at: Instant)
    private var approach: Observation? = null
    private var lastSeen: Instant? = null

    fun observe(page: VehiclePage, path: PreparedVehicleRoutePath, nowMillis: Long): Boolean {
        if (page.stale || path.routeId != boarding.routeId || path.directionId != boarding.directionId) return false
        val vehicle = page.items.firstOrNull { it.id == boarding.plannedVehicleId } ?: return false
        if (vehicle.routeId != boarding.routeId || vehicle.positionKind != VehiclePositionKind.Gps ||
            (vehicle.directionId != null && vehicle.directionId != boarding.directionId)) return false
        val at = vehicle.observedAt ?: return false
        if (nowMillis - at.toEpochMilliseconds() !in 0L..page.maxAgeSeconds * 1000L ||
            (vehicle.ageSeconds ?: 0) > page.maxAgeSeconds || lastSeen?.let { at <= it } == true) return false
        lastSeen = at
        val stop = boarding.stopPosition ?: return false
        val stopAlong = path.projections(stop).filter {
            it.distanceMeters <= MAX_LATERAL_METERS && matchesHeading(path, it.alongMeters, boarding.departureBearing)
        }.singleOrNull()?.alongMeters ?: return false

        val previous = approach
        val elapsed = previous?.let { (at - it.at).inWholeMilliseconds }
        val movement = previous?.let { directVehicleTravelBearing(it.point, vehicle.position) }
        val projections = path.projections(vehicle.position).filter { it.distanceMeters <= MAX_LATERAL_METERS }
        fun offset(along: Double): Double {
            val delta = along - stopAlong
            if (!path.isLoop || abs(delta) <= path.lengthMeters / 2) return delta
            return if (delta < 0) delta + path.lengthMeters else delta - path.lengthMeters
        }
        if (previous != null && movement != null && elapsed != null && elapsed in 1..120_000 &&
            previous.point.distanceMetersTo(vehicle.position) <= 25.0 + elapsed * 0.035) {
            fun branch(candidates: List<VehicleRouteProjection>): VehicleRouteProjection? =
                candidates.singleOrNull() ?: candidates.filter { matchesHeading(path, it.alongMeters, movement) }.singleOrNull()
            val before = branch(path.projections(previous.point).filter { it.distanceMeters <= MAX_LATERAL_METERS })
            val after = branch(projections)
            if (before != null && after != null &&
                offset(before.alongMeters) in -450.0..10.0 && offset(after.alongMeters) in 60.0..450.0) return true
        }
        // Resolve overlapping branches using the eventual travel heading across both observations.
        if (projections.any { offset(it.alongMeters) in -450.0..10.0 }) approach = Observation(vehicle.position, at)
        else if (projections.isEmpty() || elapsed != null && elapsed > 120_000) approach = null
        return false
    }

    private fun matchesHeading(path: PreparedVehicleRoutePath, along: Double, reference: Double?): Boolean {
        if (reference == null) return true
        val bearing = path.bearingAt(along, forward = true) ?: return false
        return abs((bearing - reference + 540.0) % 360.0 - 180.0) < 60.0
    }

    private companion object { const val MAX_LATERAL_METERS = 30.0 }
}
