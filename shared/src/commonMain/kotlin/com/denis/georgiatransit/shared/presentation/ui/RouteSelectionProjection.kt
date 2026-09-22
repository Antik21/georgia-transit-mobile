package com.denis.georgiatransit.shared.presentation.ui

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitRoute

/**
 * A canonical, presentation-only route selection projection. Its input must be the active city's
 * catalogue in authoritative BFF order; opaque route IDs are never sorted here or by consumers.
 *
 * The BFF owns the canonical route background and text colours. This projection preserves those
 * values regardless of the selected route set so one route cannot change colour when another is
 * added or removed. Text contrast is still checked locally as a defensive rendering guard.
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
    SelectionOverflow,
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
                        colorAvailability = RouteColorAvailability.SelectionOverflow,
                    )
                }
                ResolvedRouteSelection(
                    routeId = route.id,
                    displayLabel = route.displayLabel(),
                    backgroundArgb = route.colorArgb,
                    textArgb = contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb),
                    colorAvailability = RouteColorAvailability.Assigned,
                )
            }
            .toList()
        return RouteSelectionProjection(routes)
    }
}

fun TransitRoute.displayLabel(): String = shortName.trim().ifBlank { name.trim().ifBlank { "—" } }

const val UnavailableRouteColor = 0xFF3F3F46L
