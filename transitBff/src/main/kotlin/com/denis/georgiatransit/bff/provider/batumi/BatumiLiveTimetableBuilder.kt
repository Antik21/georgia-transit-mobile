package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.planning.LiveTrip
import com.denis.georgiatransit.bff.planning.NetworkTimetable
import com.denis.georgiatransit.bff.planning.NotServed
import com.denis.georgiatransit.bff.planning.PatternService
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.provider.BatumiLiveVehicle
import com.denis.georgiatransit.bff.provider.EtaMaximumSeconds
import com.denis.georgiatransit.bff.provider.EtaPassedStopToleranceMeters
import com.denis.georgiatransit.bff.provider.RouteCycle
import com.denis.georgiatransit.bff.provider.forwardCycleDistance
import com.denis.georgiatransit.bff.provider.projectVehicle
import com.denis.georgiatransit.bff.provider.stopChainSeconds
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToLong

private class ProjectedVehicle(val vehicleId: String, val alongMeters: Double)

/** Turns one city-wide live snapshot into per-pattern departures with the ADR 0012 ETA model. */
internal object BatumiLiveTimetableBuilder {
    fun build(
        network: BatumiPlanningNetwork,
        vehiclesByRoute: Map<String, List<BatumiLiveVehicle>>,
        observedAt: Instant,
        stale: Boolean,
        budget: PlanningBudget,
    ): NetworkTimetable {
        val catalog = network.catalog
        val projectedByRoute = network.patterns.map { it.routeId }.distinct().associateWith { routeId ->
            budget.checkpoint()
            val cycle = catalog.cycle(routeId)
            vehiclesByRoute[routeId].orEmpty().mapNotNull { live ->
                projectVehicle(catalog, routeId, cycle, live)?.let { ProjectedVehicle(live.vehicle.id, it.alongMeters) }
            }
        }
        val services = network.patterns.map { meta ->
            val projected = projectedByRoute[meta.routeId].orEmpty()
            if (projected.isEmpty()) return@map null
            val cycle = catalog.cycle(meta.routeId)
            val metersPerSecond = catalog.movingMetersPerSecond(meta.routeId)
            val along = alongMeters(meta, cycle)
            PatternService(
                liveTrips = if (stale) emptyList() else projected.flatMap { liveTrips(meta, cycle, along, metersPerSecond, it, observedAt) },
                headwaySeconds = ceil(cycle.totalMeters / (metersPerSecond * projected.size)).toLong().coerceAtLeast(1L),
                rideOffsetSeconds = DoubleArray(along.size) { stopChainSeconds(along[it] - along[0], metersPerSecond, it) },
            )
        }
        return NetworkTimetable(
            observedAt = observedAt,
            liveHorizonEndEpochSecond = observedAt.epochSecond + EtaMaximumSeconds,
            stale = stale,
            services = services,
        )
    }

    private fun alongMeters(meta: BatumiPatternMeta, cycle: RouteCycle): DoubleArray =
        DoubleArray(meta.chainIndices.size) { position ->
            val lap = if (position >= meta.secondLapStart) cycle.totalMeters else 0.0
            cycle.chain[meta.chainIndices[position]].cumulativeMeters + lap
        }

    /**
     * A bus yields its current pass (stops not yet passed) and, on a non-circle pattern, its next
     * circuit for the stops it already passed; both reuse the board's distance and dwell rules.
     */
    private fun liveTrips(
        meta: BatumiPatternMeta,
        cycle: RouteCycle,
        along: DoubleArray,
        metersPerSecond: Double,
        vehicle: ProjectedVehicle,
        observedAt: Instant,
    ): List<LiveTrip> {
        val current = LongArray(along.size) { NotServed }
        val nextCircuit = LongArray(along.size) { NotServed }
        val position = vehicle.alongMeters
        along.indices.forEach { index ->
            val chainMeters = cycle.chain[meta.chainIndices[index]].cumulativeMeters
            val ahead = chainMeters - position >= -EtaPassedStopToleranceMeters
            val distance = forwardCycleDistance(position, chainMeters, cycle.totalMeters)
            val (target, value) = when {
                index >= meta.secondLapStart -> current to distance + if (ahead) cycle.totalMeters else 0.0
                ahead -> current to distance
                meta.circle -> return@forEach
                else -> nextCircuit to distance
            }
            val seconds = stopChainSeconds(value, metersPerSecond, cycle.stopsBefore(position, value))
            target[index] = observedAt.epochSecond + seconds.roundToLong()
        }
        return listOf(current, nextCircuit)
            .filter { times -> times.any { it != NotServed } }
            .map { LiveTrip(vehicle.vehicleId, it) }
    }
}
