package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkingEstimateFormatTest {
    @Test
    fun distanceUsesReadableRoundingAndLocaleDecimalSeparator() {
        assertEquals(WalkingDistanceFormat.UnderFiftyMeters, formatWalkingEstimate(49.9, 1, TransitLocale.English).distance)
        assertEquals(WalkingDistanceFormat.Meters(50), formatWalkingEstimate(50.0, 1, TransitLocale.English).distance)
        assertEquals(WalkingDistanceFormat.Meters(950), formatWalkingEstimate(974.0, 1, TransitLocale.English).distance)
        assertEquals(WalkingDistanceFormat.KilometersTenths("1.0"), formatWalkingEstimate(1_000.0, 1, TransitLocale.English).distance)
        assertEquals(WalkingDistanceFormat.KilometersTenths("1,3"), formatWalkingEstimate(1_250.0, 1, TransitLocale.Russian).distance)
        assertEquals(WalkingDistanceFormat.KilometersTenths("1,3"), formatWalkingEstimate(1_250.0, 1, TransitLocale.Georgian).distance)
        assertEquals(WalkingDistanceFormat.KilometersTenths("10.0"), formatWalkingEstimate(9_999.0, 1, TransitLocale.English).distance)
        assertEquals(WalkingDistanceFormat.KilometersWhole(10), formatWalkingEstimate(10_000.0, 1, TransitLocale.English).distance)
    }

    @Test
    fun durationAlwaysRoundsUpAndNeverDisplaysZero() {
        assertEquals(WalkingDurationFormat.Minutes(1), formatWalkingEstimate(0.0, 0, TransitLocale.English).duration)
        assertEquals(WalkingDurationFormat.Minutes(2), formatWalkingEstimate(0.0, 61, TransitLocale.English).duration)
        assertEquals(WalkingDurationFormat.HoursMinutes(1, 1), formatWalkingEstimate(0.0, 3_601, TransitLocale.English).duration)
    }
}
