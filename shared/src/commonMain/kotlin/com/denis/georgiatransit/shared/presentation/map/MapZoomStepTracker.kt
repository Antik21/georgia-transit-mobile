package com.denis.georgiatransit.shared.presentation.map

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Native-adapter helper that turns [MapZoomStep] changes into an absolute target zoom. */
class MapZoomStepTracker internal constructor(
    private val minZoom: Double,
    private val maxZoom: Double,
    private val timeSource: TimeSource,
) {
    constructor(minZoom: Double, maxZoom: Double) : this(minZoom, maxZoom, TimeSource.Monotonic)

    private var lastTotal: Double? = null
    private var pendingTarget: Double? = null
    private var pendingSince: TimeMark? = null

    /** Null when the sum is unchanged and for the sum present when the adapter attaches. */
    fun targetZoom(step: MapZoomStep?, currentZoom: Double): Double? {
        val total = step?.totalDelta ?: 0.0
        val previous = lastTotal
        lastTotal = total
        if (previous == null || previous == total) return null
        val delta = total - previous
        if (!delta.isFinite() || !currentZoom.isFinite()) return null
        // Taps during our own animation build on its target; interrupting it reports an interim zoom.
        val base = pendingTarget?.takeIf { pendingSince?.elapsedNow()?.let { it < AnimationWindow } == true }
        return ((base ?: currentZoom) + delta).coerceIn(minZoom, maxZoom).also {
            pendingTarget = it
            pendingSince = timeSource.markNow()
        }
    }

    companion object {
        const val ANIMATION_MILLIS = 250
        private val AnimationWindow: Duration = (ANIMATION_MILLIS + 150).milliseconds
    }
}
