package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import kotlin.math.abs

/**
 * Headings for the buses approaching one boarding stop. Feeds such as Batumi send no bearing, so
 * the heading comes from the route path, then from movement, then from the last known value.
 */
internal class JourneyBusHeadings(
    private val path: PreparedVehicleRoutePath?,
    boardingStop: GeoPoint?,
    departureBearing: Double?,
) {
    private val stopAlong = boardingStop?.let { stop ->
        val candidates = path?.projections(stop).orEmpty()
        val atStop = departureBearing?.let { bearing ->
            candidates.minByOrNull { angleBetween(path?.bearingAt(it.alongMeters, forward = true), bearing) }
        }
        (atStop ?: candidates.firstOrNull())?.alongMeters
    }
    private val last = mutableMapOf<VehicleId, Observation>()

    fun update(vehicles: Collection<TransitVehicle>): Map<VehicleId, Double> {
        vehicles.forEach { vehicle ->
            val previous = last[vehicle.id]
            val moved = previous?.let { directVehicleTravelBearing(it.position, vehicle.position) }
            val heading = pathHeading(vehicle.position, moved) ?: moved ?: previous?.heading ?: vehicle.bearing
            last[vehicle.id] = Observation(vehicle.position, heading)
        }
        return last.mapNotNull { (id, observation) -> observation.heading?.let { id to it } }.toMap()
    }

    private fun pathHeading(position: GeoPoint, moved: Double?): Double? {
        val route = path ?: return null
        val candidates = route.projections(position)
        // A loop shape runs the same street both ways; the bus is the one still heading for the stop.
        val chosen = when {
            moved != null -> candidates.minByOrNull { angleBetween(route.bearingAt(it.alongMeters, forward = true), moved) }
            stopAlong != null -> candidates.minByOrNull { gapToStop(route, it.alongMeters, stopAlong) }
            else -> candidates.firstOrNull()
        } ?: return null
        return route.bearingAt(chosen.alongMeters, forward = true)
    }

    private fun gapToStop(route: PreparedVehicleRoutePath, along: Double, stop: Double): Double {
        val gap = stop - along
        return when {
            route.isLoop -> ((gap % route.lengthMeters) + route.lengthMeters) % route.lengthMeters
            gap >= 0.0 -> gap
            else -> Double.MAX_VALUE
        }
    }

    private fun angleBetween(first: Double?, second: Double): Double {
        if (first == null) return Double.MAX_VALUE
        val difference = abs(first - second) % 360.0
        return if (difference > 180.0) 360.0 - difference else difference
    }

    private data class Observation(val position: GeoPoint, val heading: Double?)
}
