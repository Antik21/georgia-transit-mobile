package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.presentation.ui.RouteColorAvailability
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import kotlinx.collections.immutable.PersistentList

/** Pure projection of stop sources and committed route styling into map/nearby UI. */
internal class MapStopsProjector(
    private val rawStops: List<TransitStop>,
    private val userLocationStops: List<TransitStop>,
    private val selectedRouteStops: List<TransitStop>,
    private val selectedStopId: StopId?,
    private val selectedSnapshot: TransitStop?,
    private val selection: RouteSelectionProjection,
    private val locale: TransitLocale,
    private val sourceRevision: Long,
) {
    private val selectedRoutesByStopId = selectedRouteStops
        .groupBy(TransitStop::id, TransitStop::routeIds)
        .mapValues { (_, routes) -> routes.flatten().distinct() }

    fun visibleMarkers(userLocation: GeoPoint?, zoom: Double): PersistentList<MapStopMarker> {
        val baseStops = (rawStops + userLocationStops + selectedRouteStops).distinctBy(TransitStop::id)
        val selectedFallback = selectedSnapshot
            ?.takeIf { selected -> selected.id == selectedStopId && baseStops.none { it.id == selected.id } }
        val selectedRouteIds = selection.byId.keys
        val markers = (baseStops + listOfNotNull(selectedFallback)).map { source ->
            val stop = source.withSelectedRouteIds()
            MapStopMarker(
                id = stop.id,
                position = stop.position,
                accessibilityLabel = stop.accessibilityLabel(locale),
                isSelected = stop.id == selectedStopId,
                routeHighlight = routeHighlightFor(stop),
                isOutsideSelectedRoutes = selectedRouteIds.isNotEmpty() && stop.routeIds.none { it in selectedRouteIds },
            )
        }
        return visibleStopMarkers(
            markers = markers,
            userLocation = userLocation,
            zoom = zoom,
        )
    }
    fun publishTo(state: ViewState, render: MapRenderState): ViewState = state.copy(
        renderState = render,
        selectedStop = selectedStopUi(),
        // List items carry the source revision; stale ones are rejected on tap.
        nearbyStops = nearbyStopItems(),
    )

    fun selectedStopUi(): SelectedStopUi? {
        val selected = selectedStopId ?: return null
        val stop = stopSnapshot(selected) ?: return null
        val displayName = stop.displayName(locale).takeIf(String::isNotBlank) ?: return null
        return SelectedStopUi(selected, displayName)
    }

    fun stopSnapshot(stopId: StopId): TransitStop? {
        val stop = rawStops.firstOrNull { it.id == stopId }
            ?: selectedRouteStops.firstOrNull { it.id == stopId }
            ?: selectedSnapshot?.takeIf { it.id == stopId }
        return stop?.withSelectedRouteIds()
    }

    // Nearby catalog entries may omit route membership supplied by the direction stop lists.
    private fun TransitStop.withSelectedRouteIds(): TransitStop {
        val selected = selectedRoutesByStopId[id] ?: return this
        if (routeIds.containsAll(selected)) return this
        return copy(routeIds = (routeIds + selected).distinct())
    }

    /** Nearby results remain whole; this adds presentation metadata without route-stop I/O. */
    private fun routeHighlightFor(stop: TransitStop): StopRouteHighlightUi {
        val stopRouteIds = stop.routeIds.toSet()
        val matching = selection.routes.filter { route ->
            route.colorAvailability == RouteColorAvailability.Assigned && route.routeId in stopRouteIds
        }
        return when (matching.size) {
            0 -> StopRouteHighlightUi()
            1 -> matching.single().let { route ->
                StopRouteHighlightUi(
                    matchingRouteIds = listOf(route.routeId),
                    matchingRouteLabels = listOf(route.displayLabel),
                    style = StopRouteHighlightStyle.SingleRoute,
                    backgroundArgb = route.backgroundArgb,
                    textArgb = route.textArgb,
                )
            }
            else -> StopRouteHighlightUi(
                matchingRouteIds = matching.map { route -> route.routeId },
                matchingRouteLabels = matching.map { route -> route.displayLabel },
                style = StopRouteHighlightStyle.MultipleRoutes,
                backgroundArgb = MultiRouteStopBackgroundArgb,
                pinBadges = matching.map { route ->
                    StopPinBadge(route.displayLabel, route.backgroundArgb, route.textArgb)
                },
                textArgb = contrastSafeRouteTextColor(MultiRouteStopBackgroundArgb, 0xFFFFFFFFL),
            )
        }
    }

    fun nearbyStopItems(): List<NearbyStopUi> = rawStops.map { stop ->
        NearbyStopUi(
            id = stop.id,
            name = stop.displayName(locale),
            isSelected = stop.id == selectedStopId,
            sourceRevision = sourceRevision,
            routeHighlight = routeHighlightFor(stop.withSelectedRouteIds()),
            position = stop.position,
        )
    }

    private fun TransitStop.displayName(locale: TransitLocale): String {
        return name.forLocale(locale).ifBlank { code }
    }

    private fun TransitStop.accessibilityLabel(locale: TransitLocale): String {
        return displayName(locale)
    }

}
