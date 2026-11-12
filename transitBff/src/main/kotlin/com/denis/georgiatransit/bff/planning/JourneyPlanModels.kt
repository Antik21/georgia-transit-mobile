package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException

enum class JourneyPlanMode {
    FASTEST,
    LESS_WALKING,
    FEWER_TRANSFERS,
}

/** Request-scoped and never retained: it must not become a cache key, log field, or metric label. */
data class JourneyPlanQuery(
    val from: GeoPoint,
    val to: GeoPoint,
    val departureAt: Instant,
    val maxTransfers: Int,
    val mode: JourneyPlanMode,
    val transferBufferSeconds: Int,
    val locale: String,
)

data class GeoBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun contains(point: GeoPoint): Boolean =
        point.latitude in minLatitude..maxLatitude && point.longitude in minLongitude..maxLongitude
}

/** ADR 0022 phase-1 constants. */
internal object PlannerParameters {
    const val AccessRadiusMeters = 1_000.0
    const val TransferRadiusMeters = 600.0
    const val WalkDetourFactor = 1.3
    const val WalkingMetersPerSecond = 1.3
    const val WalkOnlyMaximumMeters = 2_000.0
    const val SamePlaceMeters = 150.0
    const val MaximumOptions = 3
    const val SlackMinimumSeconds = 15 * 60L
    const val SlackFraction = 0.5
    const val TransferMinimumSavingSeconds = 5 * 60L
    const val WalkWeight = 1.5
    const val WaitWeight = 1.2
    const val TransferPenaltySeconds = 4 * 60L
    const val MaximumTransfers = 2
}

internal fun walkingMeters(from: GeoPoint, to: GeoPoint): Double =
    greatCircleMeters(from, to) * PlannerParameters.WalkDetourFactor

internal fun walkingSeconds(meters: Double): Long = ceil(meters / PlannerParameters.WalkingMetersPerSecond).toLong()

/**
 * Planning is CPU-bound, so coroutine timeouts cannot interrupt it; the search polls this budget
 * between pattern scans instead.
 */
class PlanningBudget(
    private val deadlineNanos: Long,
    private val isCancelled: () -> Boolean = { false },
) {
    fun checkpoint() {
        if (isCancelled()) throw CancellationException("Journey planning was cancelled")
        if (System.nanoTime() - deadlineNanos > 0) throw PlanningBudgetExceeded()
    }

    companion object {
        fun unbounded(): PlanningBudget = PlanningBudget(System.nanoTime() + Long.MAX_VALUE / 2)
    }
}

class PlanningBudgetExceeded : RuntimeException("Journey planning exceeded its time budget")
