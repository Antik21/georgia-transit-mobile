package com.denis.georgiatransit.shared.presentation.ui

import kotlin.math.pow

/**
 * Makes route badges readable even when an upstream text color is malformed or has insufficient
 * contrast. The returned color is always opaque and meets the best available black/white contrast.
 */
fun contrastSafeRouteTextColor(backgroundArgb: Long, suppliedTextArgb: Long): Long {
    val backgroundLuminance = backgroundArgb.relativeLuminance()
    val suppliedContrast = contrastRatio(backgroundLuminance, suppliedTextArgb.relativeLuminance())
    if (suppliedContrast >= MinimumBadgeTextContrast) return suppliedTextArgb or OpaqueAlphaMask

    val blackContrast = contrastRatio(backgroundLuminance, 0.0)
    val whiteContrast = contrastRatio(backgroundLuminance, 1.0)
    return if (blackContrast >= whiteContrast) OpaqueBlack else OpaqueWhite
}

private fun Long.relativeLuminance(): Double {
    fun channel(shift: Int): Double {
        val value = ((this shr shift) and 0xFF).toDouble() / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun contrastRatio(first: Double, second: Double): Double =
    (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)

private const val MinimumBadgeTextContrast = 4.5
private const val OpaqueAlphaMask = 0xFF000000L
private const val OpaqueBlack = 0xFF000000L
private const val OpaqueWhite = 0xFFFFFFFFL
