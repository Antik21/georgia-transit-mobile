package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import kotlin.test.Test
import kotlin.test.assertEquals

class MapStopVisibilityTest {
    @Test
    fun lowZoomShowsOnlySelectedRouteNearbyAndOpenStops() {
        val location = GeoPoint(41.6500, 41.6400)
        val input = listOf(
            marker("ordinary-far", 41.6700, 41.6400),
            marker("nearby", 41.6589, 41.6400),
            marker("outside-radius", 41.6591, 41.6400),
            marker("selected-route", 41.6800, 41.6400, highlighted = true),
            marker("open", 41.6900, 41.6400, selected = true),
        )

        val result = visibleStopMarkers(input, userLocation = location, zoom = ALL_STOPS_MIN_ZOOM - 0.01)

        assertEquals(listOf("nearby", "open", "selected-route"), result.map(MapStopMarker::stableId))
    }

    @Test
    fun thresholdZoomShowsEveryValidStopWithoutClusters() {
        val input = listOf(
            marker("b", 41.7, 44.8),
            marker("", 41.7, 44.8),
            marker("invalid", 91.0, 44.8),
            marker("a", 41.8, 44.9),
        )

        val result = visibleStopMarkers(input, userLocation = null, zoom = ALL_STOPS_MIN_ZOOM)

        assertEquals(listOf(StopId("a"), StopId("b")), result.map(MapStopMarker::id))
    }

    @Test
    fun lowZoomWithoutSelectionOrLocationHidesOrdinaryStops() {
        val result = visibleStopMarkers(
            markers = listOf(marker("ordinary", 41.7, 44.8)),
            userLocation = null,
            zoom = 10.0,
        )

        assertEquals(emptyList(), result)
    }

    private fun marker(
        id: String,
        latitude: Double,
        longitude: Double,
        highlighted: Boolean = false,
        selected: Boolean = false,
    ) = MapStopMarker(
        id = StopId(id),
        position = GeoPoint(latitude, longitude),
        accessibilityLabel = id,
        isSelected = selected,
        routeHighlight = if (highlighted) {
            StopRouteHighlightUi(
                matchingRouteIds = listOf(RouteId("route")),
                style = StopRouteHighlightStyle.SingleRoute,
            )
        } else {
            StopRouteHighlightUi()
        },
    )
}
