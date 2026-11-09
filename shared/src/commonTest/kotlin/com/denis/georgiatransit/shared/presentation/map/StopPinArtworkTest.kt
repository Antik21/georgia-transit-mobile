package com.denis.georgiatransit.shared.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopPinArtworkTest {
    @Test
    fun threeRoutesLeaveTheLastCellEmpty() {
        val pin = artwork(routeCount = 3)

        assertEquals(listOf("1", "2", "3"), pin.badges.map(StopPinBadge::label))
        assertEquals(pin.badgeX(0), pin.badgeX(2))
        assertTrue(pin.badgeY(2) > pin.badgeY(0))
    }

    @Test
    fun fourthCellCountsAllRemainingRoutes() {
        val pin = artwork(routeCount = 6)

        assertEquals(listOf("1", "2", "3", "+3"), pin.badges.map(StopPinBadge::label))
        assertEquals(pin.badgeX(1), pin.badgeX(3))
        assertEquals(pin.badgeY(2), pin.badgeY(3))
    }

    private fun artwork(routeCount: Int) = StopPinArtwork(
        backgroundArgb = MultiRouteStopBackgroundArgb,
        glyphArgb = DefaultStopTextArgb,
        routeBadges = (1..routeCount).map { StopPinBadge(it.toString(), MultiRouteStopBackgroundArgb, DefaultStopTextArgb) },
    )
}
