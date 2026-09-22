package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.route
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanonicalRouteStylePolicyTest {
    @Test
    fun `same opaque route ID keeps one style regardless of provider display input`() {
        val first = CanonicalRouteStylePolicy.apply(route.copy(color = "#000000", textColor = "#FFFFFF"))
        val second = CanonicalRouteStylePolicy.apply(route.copy(color = "#FFFFFF", textColor = "#000000"))

        assertEquals("#C2410C", first.color)
        assertEquals("#FFFFFF", first.textColor)
        assertEquals(first.color, second.color)
        assertEquals(first.textColor, second.textColor)
        assertNotEquals(route.color, first.color)
    }

    @Test
    fun `expanded palette exposes eighteen map and text contrast validated styles`() {
        val styles = (0..10_000)
            .asSequence()
            .map { index ->
                CanonicalRouteStylePolicy.apply(route.copy(id = "test:provider:route:$index"))
            }
            .map { styled -> styled.color to styled.textColor }
            .toSet()

        assertEquals(18, CanonicalRouteStylePolicy.paletteSize)
        assertEquals(CanonicalRouteStylePolicy.paletteSize, styles.size)
        styles.forEach { (background, text) ->
            assertEquals("#FFFFFF", text)
            assertTrue(contrast(background, text) >= MinimumTextContrast)
            assertTrue(contrast(background, MapBackground) >= MinimumMapContrast)
        }
    }

    private fun contrast(first: String, second: String): Double {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun String.luminance(): Double {
        val channels = removePrefix("#").chunked(2).map { component -> component.toInt(16) / 255.0 }
        fun linear(value: Double): Double =
            if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(channels[0]) + 0.7152 * linear(channels[1]) + 0.0722 * linear(channels[2])
    }

    private companion object {
        const val MapBackground = "#E7F1EB"
        const val MinimumTextContrast = 4.5
        const val MinimumMapContrast = 3.0
    }
}
