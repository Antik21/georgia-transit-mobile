package com.denis.georgiatransit.shared.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopPinArtworkTest {
    @Test
    fun twoRouteBadgesStackVertically() {
        val pin = artwork(routeCount = 2)

        assertEquals(listOf("1", "2"), pin.badges.map(StopPinBadge::label))
        assertTrue(pin.badgeY(0) < pin.badgeY(1))
    }

    @Test
    fun overflowCountsAllRoutesExceptTheOneClosestToThePin() {
        val pin = artwork(routeCount = 4)

        assertEquals(listOf("+3", "1"), pin.badges.map(StopPinBadge::label))
        assertEquals(MultiRouteStopBackgroundArgb, pin.badges.first().backgroundArgb)
        assertTrue(pin.badgeY(0) < pin.badgeY(1))
    }

    private fun artwork(routeCount: Int) = StopPinArtwork(
        backgroundArgb = MultiRouteStopBackgroundArgb,
        glyphArgb = DefaultStopTextArgb,
        routeBadges = (1..routeCount).map { StopPinBadge(it.toString(), MultiRouteStopBackgroundArgb, DefaultStopTextArgb) },
    )
}
