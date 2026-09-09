package com.denis.georgiatransit.shared.presentation.ui

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import kotlin.math.pow

/**
 * A canonical, presentation-only route selection projection. Its input must be the active city's
 * catalogue in authoritative BFF order; opaque route IDs are never sorted here or by consumers.
 *
 * Provider colours are untrusted display input. A route gets its own accessible colour only when
 * the provider value is opaque, readable on the local map, and distinct from every earlier
 * selected catalogue route. Otherwise the deterministic fallback palette is used. Exhaustion is
 * explicit so renderers can fail closed rather than drawing ambiguous route identity.
 */
@Immutable
data class RouteSelectionProjection(
    val routes: List<ResolvedRouteSelection> = emptyList(),
) {
    val byId: Map<RouteId, ResolvedRouteSelection> get() = routes.associateBy(ResolvedRouteSelection::routeId)
}

@Immutable
data class ResolvedRouteSelection(
    val routeId: RouteId,
    val displayLabel: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val colorAvailability: RouteColorAvailability,
)

enum class RouteColorAvailability {
    Assigned,
    PaletteOverflow,
}

object RouteSelectionProjector {
    /**
     * Resolves only IDs that occur once in the active catalogue and preserves the catalogue's
     * order. Duplicate catalogue IDs are invalid provider data, so neither copy is actionable.
     */
    fun resolve(catalogRoutes: List<TransitRoute>, selectedIds: Set<RouteId>): RouteSelectionProjection {
        if (selectedIds.isEmpty()) return RouteSelectionProjection()
        val duplicateIds = catalogRoutes.groupingBy(TransitRoute::id).eachCount()
            .filterValues { count -> count > 1 }
            .keys
        val assigned = linkedSetOf<Long>()
        val routes = catalogRoutes.asSequence()
            .filter { route ->
                route.id.value.isNotBlank() && route.id in selectedIds && route.id !in duplicateIds
            }
            .mapIndexed { index, route ->
                if (index >= RouteSelectionPolicy.MaximumSelectedRoutes) {
                    return@mapIndexed ResolvedRouteSelection(
                        routeId = route.id,
                        displayLabel = route.displayLabel(),
                        backgroundArgb = UnavailableRouteColor,
                        textArgb = contrastSafeRouteTextColor(UnavailableRouteColor, route.textColorArgb),
                        colorAvailability = RouteColorAvailability.PaletteOverflow,
                    )
                }
                val provider = route.colorArgb.takeIf(::isAccessibleProviderColor)
                    ?.takeIf { candidate -> assigned.all { assignedColor -> isDistinct(candidate, assignedColor) } }
                val assignedColor = provider ?: fallbackPaletteColor(route.id, assigned)
                if (assignedColor == null) {
                    ResolvedRouteSelection(
                        routeId = route.id,
                        displayLabel = route.displayLabel(),
                        backgroundArgb = UnavailableRouteColor,
                        textArgb = contrastSafeRouteTextColor(UnavailableRouteColor, route.textColorArgb),
                        colorAvailability = RouteColorAvailability.PaletteOverflow,
                    )
                } else {
                    assigned += assignedColor
                    ResolvedRouteSelection(
                        routeId = route.id,
                        displayLabel = route.displayLabel(),
                        backgroundArgb = assignedColor,
                        textArgb = contrastSafeRouteTextColor(assignedColor, route.textColorArgb),
                        colorAvailability = RouteColorAvailability.Assigned,
                    )
                }
            }
            .toList()
        return RouteSelectionProjection(routes)
    }

    private fun fallbackPaletteColor(routeId: RouteId, assigned: Set<Long>): Long? {
        val start = (routeId.value.stableColorHash().toUInt().toLong() % AccessibleRoutePalette.size).toInt()
        return AccessibleRoutePalette.indices.asSequence()
            .map { index -> AccessibleRoutePalette[(start + index) % AccessibleRoutePalette.size] }
            .firstOrNull { candidate -> candidate !in assigned && assigned.all { isDistinct(candidate, it) } }
    }

    private fun isAccessibleProviderColor(color: Long): Boolean =
        (color ushr 24) == 0xFFL && contrastRatio(color.luminance(), MapBackgroundLuminance) >= MinimumMapContrast

    private fun isDistinct(first: Long, second: Long): Boolean {
        val red = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val green = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return red * red + green * green + blue * blue >= MinimumRgbDistanceSquared
    }

    private fun String.stableColorHash(): Int = fold(17) { hash, character -> 31 * hash + character.code }

    private fun Long.luminance(): Double {
        fun channel(shift: Int): Double {
            val value = ((this shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrastRatio(first: Double, second: Double): Double =
        (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}

fun TransitRoute.displayLabel(): String = shortName.trim().ifBlank { name.trim().ifBlank { "—" } }

const val UnavailableRouteColor = 0xFF3F3F46L

private const val MapBackgroundLuminance = 0.8589768 // #E7F1EB local preview land fill.
private const val MinimumMapContrast = 3.0
private const val MinimumRgbDistanceSquared = 2_500L

private val AccessibleRoutePalette = listOf(
    0xFF5B21B6L, 0xFF1D4ED8L, 0xFF0F766EL, 0xFFBE123CL, 0xFF334155L,
    0xFF7F1D1DL, 0xFF14532DL, 0xFF1E3A8AL, 0xFF854D0EL, 0xFF3F6212L,
    0xFFC2410CL, 0xFFA21CAFL, 0xFF0369A1L,
).also(::validatePalette)

private fun validatePalette(palette: List<Long>): List<Long> {
    check(palette.all { color -> (color ushr 24) == 0xFFL })
    return palette
}
