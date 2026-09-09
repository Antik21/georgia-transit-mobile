package com.denis.georgiatransit.shared.presentation.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteBadgeColorsTest {
    @Test
    fun suppliedTextColorAtTheContrastBoundaryIsAcceptedAndMadeOpaque() {
        assertEquals(
            0xFF767676,
            contrastSafeRouteTextColor(
                backgroundArgb = 0xFFFFFFFF,
                suppliedTextArgb = 0x00767676,
            ),
        )
    }

    @Test
    fun suppliedTextColorBelowTheContrastBoundaryFallsBackToTheMostLegibleColor() {
        assertEquals(
            0xFF000000,
            contrastSafeRouteTextColor(
                backgroundArgb = 0xFFFFFFFF,
                suppliedTextArgb = 0xFF777777,
            ),
        )
        assertEquals(
            0xFFFFFFFF,
            contrastSafeRouteTextColor(
                backgroundArgb = 0xFF000000,
                suppliedTextArgb = 0xFF000000,
            ),
        )
    }

    @Test
    fun fallbackChoosesBlackWhenItHasMoreContrastThanWhite() {
        assertEquals(
            0xFF000000,
            contrastSafeRouteTextColor(
                backgroundArgb = 0xFF777777,
                suppliedTextArgb = 0xFFFFFFFF,
            ),
        )
    }
}
