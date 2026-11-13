package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JourneyEndpointExpansionTest {
    private val point = JourneyEndpoint.MapPoint(GeoPoint(41.64, 41.63))

    @Test
    fun theMissingSideOpensAndBothChosenCollapseEverything() {
        assertEquals(JourneyPlanSide.Destination, journeyExpandedSideFor(point, null))
        assertEquals(JourneyPlanSide.Origin, journeyExpandedSideFor(null, JourneyEndpoint.CurrentLocation))
        assertNull(journeyExpandedSideFor(point, JourneyEndpoint.CurrentLocation))
        assertNull(journeyExpandedSideFor(null, null), "A cleared planner starts collapsed")
    }
}
