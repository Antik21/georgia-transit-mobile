package com.denis.georgiatransit.shared.domain.model

import kotlin.math.ceil

/** Display estimates use the planner's walking speed and round distances up to 100 m. */
internal object WalkingEstimate {
    fun meters(distance: Double): Int = (ceil(distance.coerceAtLeast(0.0) / 100.0) * 100.0).toInt()

    fun minutes(meters: Int): Int = ceil(meters / 1.3 / 60.0).toInt()
}
