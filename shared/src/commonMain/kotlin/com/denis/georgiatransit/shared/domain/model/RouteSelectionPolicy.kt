package com.denis.georgiatransit.shared.domain.model

/** Product-wide guardrail for rendering and polling several route overlays at once. */
object RouteSelectionPolicy {
    const val MaximumSelectedRoutes = 10

    /**
     * Opaque identifiers are never parsed or rebuilt. A malformed durable selection is discarded
     * as a whole rather than retaining an arbitrary subset whose provenance cannot be verified.
     */
    fun sanitized(routeIds: Set<RouteId>): Set<RouteId> =
        routeIds.takeIf(::isValid)?.toSet().orEmpty()

    fun isValid(routeIds: Set<RouteId>): Boolean =
        routeIds.size <= MaximumSelectedRoutes && routeIds.none { it.value.isBlank() }
}
