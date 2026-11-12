package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.JourneyLabel
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneyLabelUiTest {
    @Test
    fun everyLabelHasAFixedColourWithAccessibleWhiteText() {
        assertEquals(JourneyLabel.entries.toSet(), JourneyLabelUi.entries.map { it.label }.toSet())
        for (label in JourneyLabelUi.entries) {
            assertEquals(0xFFFFFFFFL, label.textArgb)
            val ratio = contrast(label.backgroundArgb, label.textArgb)
            assertTrue(ratio >= 4.5, "$label has only $ratio:1")
        }
        assertEquals(JourneyLabelUi.entries.size, JourneyLabelUi.entries.map { it.backgroundArgb }.toSet().size)
    }

    @Test
    fun labelColoursStayDistinctFromTheWalkLineAndTheEndpointMarkers() {
        // The web A/B markers (#2E7D32, #8E1B2D) and the walk line must not read as a label.
        val reserved = listOf(JourneyWalkLineArgb, 0xFF2E7D32L, 0xFF8E1B2DL)
        for (label in JourneyLabelUi.entries) {
            for (other in reserved) assertTrue(distance(label.backgroundArgb, other) > 60.0, "$label is too close to ${other.toString(16)}")
        }
    }

    @Test
    fun displayOrderIgnoresServerOrderAndUnknownDuplicates() {
        val labels = listOf(JourneyLabel.NoTransfers, JourneyLabel.Fastest, JourneyLabel.Optimal, JourneyLabel.Fastest)

        assertEquals(listOf(JourneyLabelUi.Optimal, JourneyLabelUi.Fastest, JourneyLabelUi.NoTransfers), JourneyLabelUi.of(labels))
    }

    private fun contrast(first: Long, second: Long): Double {
        val (lighter, darker) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun distance(first: Long, second: Long): Double {
        val channels = listOf(16, 8, 0).map { shift -> ((first shr shift) and 0xFF) - ((second shr shift) and 0xFF) }
        return kotlin.math.sqrt(channels.sumOf { (it * it).toDouble() })
    }
}
