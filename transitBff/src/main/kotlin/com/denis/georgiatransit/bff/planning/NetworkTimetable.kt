package com.denis.georgiatransit.bff.planning

import java.time.Instant

internal const val NotServed = Long.MIN_VALUE

/**
 * One projected pass of a live vehicle along a pattern. [times] holds epoch seconds per position
 * and [NotServed] where this pass does not stop; served positions are one contiguous range.
 */
class LiveTrip(val vehicleId: String, val times: LongArray) {
    val firstPosition: Int = times.indexOfFirst { it != NotServed }
    val lastPosition: Int = times.indexOfLast { it != NotServed }

    init {
        require(firstPosition >= 0) { "A live trip must serve at least one position" }
        for (position in firstPosition until lastPosition) {
            require(times[position] != NotServed && times[position + 1] != NotServed) { "Live trip coverage must be contiguous" }
            require(times[position + 1] >= times[position]) { "Live trip times must not decrease" }
        }
    }
}

/**
 * Departures for one pattern. [rideOffsetSeconds] is the provider's ride-time model from position
 * zero, used for headway-estimated rides; a null service means the pattern is not offered.
 */
class PatternService(
    val liveTrips: List<LiveTrip>,
    val headwaySeconds: Long,
    val rideOffsetSeconds: DoubleArray,
) {
    init {
        require(headwaySeconds > 0) { "Headway must be positive" }
    }
}

class NetworkTimetable(
    val observedAt: Instant,
    /** Live departures after this epoch second are outside the ETA horizon and never used. */
    val liveHorizonEndEpochSecond: Long,
    val stale: Boolean,
    val services: List<PatternService?>,
)
