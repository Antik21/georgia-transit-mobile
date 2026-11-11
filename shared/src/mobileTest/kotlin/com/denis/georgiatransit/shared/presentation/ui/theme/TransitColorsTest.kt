package com.denis.georgiatransit.shared.presentation.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitColorsTest {
    @Test
    fun geoTransitPaletteKeepsItsBrandValues() {
        assertEquals(Color(0xFF8E1B2D), TransitColors.Primary)
        assertEquals(Color(0xFFFF6B5F), TransitColors.Accent)
        assertEquals(Color(0xFFF7F7F5), TransitColors.Background)
        assertEquals(Color(0xFF18191B), TransitColors.Dark)
        assertEquals(Color(0xFFA8ADB4), TransitColors.SecondaryGray)
        assertEquals(Color(0xFF7C8188), TransitColors.LightOutline)
        assertEquals(Color(0xFF27AE7A), TransitColors.Success)
        assertEquals(TransitColors.Background, TransitColors.LaunchBackground)
    }
}
