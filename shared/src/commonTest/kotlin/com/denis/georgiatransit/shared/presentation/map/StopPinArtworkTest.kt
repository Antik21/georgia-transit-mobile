package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.StopId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

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

    @Test
    fun selectionChangesOnlyTheOutlineAndImageKey() {
        val marker = MapStopMarker(
            id = StopId("stop"),
            position = GeoPoint(41.0, 42.0),
            routeHighlight = StopRouteHighlightUi(
                style = StopRouteHighlightStyle.MultipleRoutes,
                backgroundArgb = MultiRouteStopBackgroundArgb,
                pinBadges = listOf(
                    StopPinBadge("10", 0xFF89500AL, DefaultStopTextArgb),
                    StopPinBadge("10A", 0xFF137C78L, DefaultStopTextArgb),
                ),
            ),
        )
        val selected = marker.copy(isSelected = true)
        val ordinaryPin = marker.pinArtwork
        val selectedPin = selected.pinArtwork

        assertEquals(DefaultStopTextArgb, ordinaryPin.outlineArgb)
        assertEquals(0xFF27AE7AL, selectedPin.outlineArgb)
        assertNotEquals(ordinaryPin.imageId, selectedPin.imageId)
        assertEquals(ordinaryPin.backgroundArgb, selectedPin.backgroundArgb)
        assertEquals(ordinaryPin.glyphArgb, selectedPin.glyphArgb)
        assertEquals(ordinaryPin.badges, selectedPin.badges)
        assertEquals(ordinaryPin.width, selectedPin.width)
        assertEquals(ordinaryPin.height, selectedPin.height)
        assertEquals(marker.pinScale, selected.pinScale)
        assertEquals(ordinaryPin.imageId, selected.copy(isSelected = false).pinArtwork.imageId)
    }

    private fun artwork(routeCount: Int) = StopPinArtwork(
        backgroundArgb = MultiRouteStopBackgroundArgb,
        glyphArgb = DefaultStopTextArgb,
        routeBadges = (1..routeCount).map { StopPinBadge(it.toString(), MultiRouteStopBackgroundArgb, DefaultStopTextArgb) },
    )
}
