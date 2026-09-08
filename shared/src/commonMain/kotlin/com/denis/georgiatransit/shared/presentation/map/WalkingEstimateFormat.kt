package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitLocale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Locale-neutral structure; Compose resources own all visible wording and units. */
internal data class WalkingEstimateFormat(
    val distance: WalkingDistanceFormat,
    val duration: WalkingDurationFormat,
)

internal sealed interface WalkingDistanceFormat {
    data object UnderFiftyMeters : WalkingDistanceFormat
    data class Meters(val value: Int) : WalkingDistanceFormat
    /** Already uses the locale-correct decimal separator, with exactly one decimal place. */
    data class KilometersTenths(val value: String) : WalkingDistanceFormat
    data class KilometersWhole(val value: Int) : WalkingDistanceFormat
}

internal sealed interface WalkingDurationFormat {
    data class Minutes(val value: Int) : WalkingDurationFormat
    data class HoursMinutes(val hours: Int, val minutes: Int) : WalkingDurationFormat
}

internal fun formatWalkingEstimate(
    distanceMeters: Double,
    durationSeconds: Long,
    locale: TransitLocale,
): WalkingEstimateFormat {
    val safeDistance = distanceMeters.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val distance = when {
        safeDistance < 50.0 -> WalkingDistanceFormat.UnderFiftyMeters
        safeDistance < 1_000.0 -> WalkingDistanceFormat.Meters((safeDistance / 50.0).roundToInt() * 50)
        safeDistance < 10_000.0 -> {
            val tenths = (safeDistance / 100.0).roundToInt()
            val separator = if (locale == TransitLocale.English) '.' else ','
            WalkingDistanceFormat.KilometersTenths("${tenths / 10}$separator${tenths % 10}")
        }
        else -> WalkingDistanceFormat.KilometersWhole((safeDistance / 1_000.0).roundToInt())
    }
    val totalMinutes = ceil(durationSeconds.coerceAtLeast(1L) / 60.0).toInt().coerceAtLeast(1)
    val duration = if (totalMinutes < 60) {
        WalkingDurationFormat.Minutes(totalMinutes)
    } else {
        WalkingDurationFormat.HoursMinutes(totalMinutes / 60, totalMinutes % 60)
    }
    return WalkingEstimateFormat(distance, duration)
}
