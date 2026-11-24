package com.denis.georgiatransit.shared.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapViewportPolicyTest {
    @Test
    fun plannerAndPickerUseTheirCurrentHeightIncludingCollapsedAndClosedStates() {
        assertEquals(0.55, insets(550.0).bottomOcclusionFraction)
        assertEquals(0.12, insets(120.0).bottomOcclusionFraction)
        assertEquals(0.0, insets(0.0).bottomOcclusionFraction)
        assertEquals(0.2, insets(200.0).bottomOcclusionFraction)
    }

    @Test
    fun modalPositionIsMeasuredRelativeToTheMapAndReplacesTheFallback() {
        val actual = MapViewportPolicy.insetsForLayout(
            mapHeightPx = 1000,
            bottomOverlayHeightPx = 120.0,
            mapTopPx = 50.0,
            modalTopPx = 800.0,
            fallback = MapViewportInsets.StopArrivalsSheet,
        )
        assertEquals(0.25, actual.bottomOcclusionFraction)
        assertEquals(0.6, insets(600.0, modalTop = 800.0).bottomOcclusionFraction)
    }

    @Test
    fun unmeasuredModalKeepsTheExistingInsetAndOverlappingPanelsAreNotAdded() {
        assertEquals(0.42, insets(0.0, fallback = MapViewportInsets.StopArrivalsSheet).bottomOcclusionFraction)
        assertEquals(0.55, insets(550.0, fallback = MapViewportInsets.StopArrivalsSheet).bottomOcclusionFraction)
        assertEquals(
            MapViewportInsets.StopArrivalsSheet,
            MapViewportPolicy.insetsForLayout(0, 0.0, fallback = MapViewportInsets.StopArrivalsSheet),
        )
    }

    @Test
    fun offscreenPanelsAndInvalidSizesCannotProduceInvalidPadding() {
        assertEquals(0.0, insets(-50.0, modalTop = 1200.0).bottomOcclusionFraction)
        assertEquals(0.75, insets(2000.0).bottomOcclusionFraction)
        assertEquals(0.0, insets(Double.NaN, modalTop = Double.NaN).bottomOcclusionFraction)
    }

    private fun insets(
        overlayHeight: Double,
        modalTop: Double? = null,
        fallback: MapViewportInsets = MapViewportInsets.None,
    ) = MapViewportPolicy.insetsForLayout(1000, overlayHeight, modalTopPx = modalTop, fallback = fallback)
}
