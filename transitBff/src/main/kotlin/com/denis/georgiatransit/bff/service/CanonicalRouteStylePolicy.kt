package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.Route
import java.nio.charset.StandardCharsets

/**
 * Owns the public route identity colours returned by the BFF. Provider colours remain validated
 * adapter input, but never decide the mobile-visible style: the same opaque route ID always maps
 * to the same palette entry, independent of locale, catalogue order, or a user's selection.
 */
internal object CanonicalRouteStylePolicy {
    internal val paletteSize: Int get() = palette.size

    fun apply(route: Route): Route {
        val style = palette[route.id.stablePaletteIndex(palette.size)]
        return route.copy(color = style.background, textColor = style.text)
    }

    private fun String.stablePaletteIndex(size: Int): Int {
        var hash = FnvOffsetBasis
        toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            hash = (hash xor (byte.toInt() and 0xFF)) * FnvPrime
        }
        return (hash.toUInt().toLong() % size).toInt()
    }

    private data class RouteStyle(val background: String, val text: String)

    private val palette = listOf(
        RouteStyle("#5B21B6", "#FFFFFF"),
        RouteStyle("#1D4ED8", "#FFFFFF"),
        RouteStyle("#0F766E", "#FFFFFF"),
        RouteStyle("#BE123C", "#FFFFFF"),
        RouteStyle("#334155", "#FFFFFF"),
        RouteStyle("#7F1D1D", "#FFFFFF"),
        RouteStyle("#14532D", "#FFFFFF"),
        RouteStyle("#1E3A8A", "#FFFFFF"),
        RouteStyle("#854D0E", "#FFFFFF"),
        RouteStyle("#3F6212", "#FFFFFF"),
        RouteStyle("#C2410C", "#FFFFFF"),
        RouteStyle("#A21CAF", "#FFFFFF"),
        RouteStyle("#0369A1", "#FFFFFF"),
        RouteStyle("#DC2626", "#FFFFFF"),
        RouteStyle("#2563EB", "#FFFFFF"),
        RouteStyle("#4F46E5", "#FFFFFF"),
        RouteStyle("#9333EA", "#FFFFFF"),
        RouteStyle("#DB2777", "#FFFFFF"),
    )

    private const val FnvOffsetBasis = -0x7ee3623b // 32-bit FNV-1a 0x811C9DC5.
    private const val FnvPrime = 0x01000193
}
