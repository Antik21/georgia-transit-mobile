package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.JourneyPage
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.TransitJourney
import com.denis.georgiatransit.shared.domain.model.TransitJourneySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class JourneyWalkingEstimateTest {
    @Test
    fun walkingDistancesRoundUpAndMinutesFollowTheDisplayedDistance() {
        val cases = listOf(
            Triple(0.1, 0, 0),
            Triple(1.0, 1, 1),
            Triple(34.2, 34, 1),
            Triple(49.0, 49, 1),
            Triple(49.9, 50, 1),
            Triple(50.0, 100, 2),
            Triple(50.1, 100, 2),
            Triple(100.0, 100, 2),
            Triple(112.0, 200, 3),
            Triple(200.1, 300, 4),
            Triple(539.0, 600, 8),
        )
        cases.forEach { (distance, expectedMeters, expectedMinutes) ->
            val option = option(distance)
            val walk = assertIs<JourneyLegUi.Walk>(option.legs.single())
            assertEquals(expectedMeters, walk.meters, "distance $distance")
            assertEquals(expectedMinutes, walk.minutes, "distance $distance")
            assertEquals(expectedMeters, option.walkingMeters, "total distance $distance")
        }
    }

    @Test
    fun missingDistanceKeepsThePlannerDuration() {
        val option = option(null)
        val walk = assertIs<JourneyLegUi.Walk>(option.legs.single())
        assertEquals(null, walk.meters)
        assertEquals(4, walk.minutes)
        assertEquals(null, option.walkingMeters)
    }

    private fun option(distance: Double?): JourneyOptionUi {
        val start = Instant.fromEpochMilliseconds(0)
        val end = Instant.fromEpochMilliseconds(240_000)
        val segment = TransitJourneySegment(start, end, JourneySegmentMode.Walk, distanceMeters = distance)
        val journey = TransitJourney(JourneyId("walk"), start, end, 0, emptyList(), segments = listOf(segment))
        return JourneyPlanUiMapper.options(JourneyPage(listOf(journey), start), emptyList()).single()
    }
}
