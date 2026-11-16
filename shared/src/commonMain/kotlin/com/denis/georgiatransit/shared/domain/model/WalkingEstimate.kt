package com.denis.georgiatransit.shared.domain.model

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Display estimates preserve short walks and round longer distances up to 100 m. */
internal object WalkingEstimate {
    fun meters(distance: Double): Int {
        val nonNegative = distance.coerceAtLeast(0.0)
        return if (nonNegative < 50.0) nonNegative.roundToInt() else (ceil(nonNegative / 100.0) * 100.0).toInt()
    }

    fun minutes(meters: Int): Int = ceil(meters / 1.3 / 60.0).toInt()
}
