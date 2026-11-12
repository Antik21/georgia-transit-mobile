package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.JourneyWaitBasis

internal sealed class SearchLabel {
    abstract val stop: Int
    abstract val arrival: Long
    abstract val walkMeters: Double
}

internal class AccessLabel(
    override val stop: Int,
    override val arrival: Long,
    override val walkMeters: Double,
) : SearchLabel()

internal class RideLabel(
    override val stop: Int,
    override val arrival: Long,
    val boarding: SearchLabel,
    val pattern: Int,
    val boardPosition: Int,
    val alightPosition: Int,
    val departure: Long,
    val waitBasis: JourneyWaitBasis,
    val vehicleId: String?,
) : SearchLabel() {
    override val walkMeters: Double get() = boarding.walkMeters
}

internal class TransferLabel(
    override val stop: Int,
    override val arrival: Long,
    val from: RideLabel,
    val legMeters: Double,
) : SearchLabel() {
    override val walkMeters: Double = from.walkMeters + legMeters
}

/** Pareto bag over (arrival, walking); transfers are separated by RAPTOR rounds. */
internal class LabelBag {
    val labels = ArrayList<SearchLabel>(2)

    fun isDominated(arrival: Long, walkMeters: Double): Boolean =
        labels.any { it.arrival <= arrival && it.walkMeters <= walkMeters + WalkToleranceMeters }

    fun add(label: SearchLabel): Boolean {
        if (isDominated(label.arrival, label.walkMeters)) return false
        labels.removeAll { label.arrival <= it.arrival && label.walkMeters <= it.walkMeters + WalkToleranceMeters }
        labels += label
        if (labels.size > MaximumBagSize) {
            labels.sortBy(SearchLabel::arrival)
            // Keep both extremes (earliest and least walking) and thin the middle.
            labels.removeAt(labels.size / 2)
        }
        return true
    }

    private companion object {
        const val WalkToleranceMeters = 1.0
        const val MaximumBagSize = 6
    }
}

/** One boarded vehicle pass (live or headway-estimated) while scanning a pattern. */
internal class RouteEntry(
    val boarding: SearchLabel,
    val boardPosition: Int,
    val departure: Long,
    val lastPosition: Int,
    val waitBasis: JourneyWaitBasis,
    val vehicleId: String?,
    private val liveTimes: LongArray?,
    private val rideOffsetSeconds: DoubleArray,
) {
    fun timeAt(position: Int): Long =
        liveTimes?.get(position)
            ?: (departure + Math.round(rideOffsetSeconds[position] - rideOffsetSeconds[boardPosition]))

    fun dominates(other: RouteEntry, position: Int): Boolean =
        timeAt(position) <= other.timeAt(position) &&
            boarding.walkMeters <= other.boarding.walkMeters + 1.0 &&
            lastPosition >= other.lastPosition
}
