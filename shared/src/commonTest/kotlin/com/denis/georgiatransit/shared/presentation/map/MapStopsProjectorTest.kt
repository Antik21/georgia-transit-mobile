package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MapStopsProjectorTest {
    @Test
    fun nearbyStopRetainsMembershipFromEachSelectedRouteAtLowZoom() {
        val first = route("3", 0xFF003B70)
        val second = route("4", 0xFF723020)
        val stop = TransitStop(
            id = StopId("stop-1"),
            providerId = ProviderId("stop-1"),
            code = "1",
            name = LocalizedText.fromLegacy("Central stop"),
            position = GeoPoint(42.271577, 42.700961),
            routeIds = emptyList(),
            mode = TransitMode.Bus,
        )
        val projector = MapStopsProjector(
            rawStops = listOf(stop),
            userLocationStops = listOf(stop),
            selectedRouteStops = listOf(
                stop.copy(routeIds = listOf(first.id)),
                stop.copy(routeIds = listOf(second.id)),
                stop.copy(routeIds = listOf(first.id)),
            ),
            selectedStopId = null,
            selectedSnapshot = null,
            selection = RouteSelectionProjector.resolve(listOf(first, second), setOf(first.id, second.id)),
            locale = TransitLocale.English,
            sourceRevision = 1L,
        )

        val marker = projector.visibleMarkers(userLocation = null, zoom = 14.0).single()
        assertEquals(stop.position, marker.position)
        assertFalse(marker.isOutsideSelectedRoutes)
        val routes = listOf(first.id, second.id)
        assertEquals(routes, marker.routeHighlight.matchingRouteIds)
        assertEquals(routes, projector.nearbyStopItems().single().routeHighlight.matchingRouteIds)
        assertEquals(routes, projector.stopSnapshot(stop.id)?.routeIds)
    }

    private fun route(id: String, color: Long) = TransitRoute(
        id = RouteId(id),
        cityId = CityId("kutaisi"),
        shortName = id,
        name = id,
        colorArgb = color,
    )
}
