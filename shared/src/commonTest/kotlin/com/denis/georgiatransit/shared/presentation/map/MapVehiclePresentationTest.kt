package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjector
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class MapVehiclePresentationTest {
    @Test
    fun resumedVehicleKeepsItsTravelHeadingAgainstPolylineOrderOnEveryReturn() {
        val map = VehicleMap()
        map.establishEastboundTravel()

        repeat(2) {
            map.presentation.pause()
            assertTrue(map.presentation.vehicleMarkers.isEmpty())
            map.acceptPosition(longitude = 0.0012, elapsedMillis = 300)
            map.assertBearing(90.0)
            map.acceptPosition(longitude = 0.0012)
            map.assertBearing(90.0)
        }

        map.acceptPosition(longitude = 0.0008)
        map.assertBearing(90.0)
        map.acceptPosition(longitude = 0.0004)
        map.assertBearing(270.0)
    }

    @Test
    fun expiredHeadingIsNotRestoredOnReturn() {
        val map = VehicleMap()
        map.establishEastboundTravel()
        map.presentation.pause()

        map.acceptPosition(longitude = 0.0012, elapsedMillis = 31_000)

        assertNull(map.presentation.vehicleMarkers.single().bearingDegrees)
    }

    @Test
    fun firstMovementEstablishesHeadingEvenAgainstPolylineOrder() {
        val map = VehicleMap()
        map.acceptPosition(longitude = 0.0004)
        assertNull(map.presentation.vehicleMarkers.single().bearingDegrees)
        map.acceptPosition(longitude = 0.00041)
        assertNull(map.presentation.vehicleMarkers.single().bearingDegrees)
        map.acceptPosition(longitude = 0.0008)
        map.assertBearing(90.0)
    }

    @Test
    fun firstPositionUsesFeedBearingEvenAgainstPolylineOrder() {
        val map = VehicleMap()
        map.acceptPosition(longitude = 0.0012, bearing = 85.0)
        map.assertBearing(85.0)
        map.acceptPosition(longitude = 0.0012, bearing = 85.0)
        map.assertBearing(85.0)
        map.acceptPosition(longitude = 0.00119, bearing = 85.0)
        map.assertBearing(85.0)
    }

    private class VehicleMap : VehicleRealtimeClock {
        private val city = TransitCity(
            id = CityId("city"), name = "City", center = GeoPoint(0.0, 0.0),
            capabilities = CityCapabilities(stops = false, vehicles = true, arrivals = false, routeShapes = true, journeyPlanning = false),
        )
        private val route = TransitRoute(RouteId("line-10"), city.id, "10", "Line 10", 0xFF0057B8)
        private val directionId = DirectionId("outbound")
        private var nowMillis = 0L
        val presentation = MapVehiclePresentation(this, DefaultVehicleRealtimeTickerPolicy).apply {
            val routes = listOf(route)
            val westboundGeometry = MapPolyline(
                route.id, directionId, persistentListOf(GeoPoint(0.0, 0.002), GeoPoint(0.0, 0.0)),
                route.colorArgb, TransitFreshness.Network,
            )
            updateInputs(
                city, routes, RouteSelectionProjector.resolve(routes, setOf(route.id)), true,
                RouteGeometrySnapshot(polylines = persistentListOf(westboundGeometry)),
            )
        }

        override fun wallNow(): Instant = Instant.parse("2026-01-01T00:00:00Z") + nowMillis.milliseconds
        override fun monotonicNowMillis(): Long = nowMillis

        fun establishEastboundTravel() {
            acceptPosition(longitude = 0.0004)
            acceptPosition(longitude = 0.0008)
            acceptPosition(longitude = 0.0012)
            assertBearing(90.0)
        }

        fun acceptPosition(longitude: Double, elapsedMillis: Long = 8_000L, bearing: Double? = null) {
            nowMillis += elapsedMillis
            val observedAt = wallNow()
            val bus = TransitVehicle(
                VehicleId("bus"), route.id, directionId, GeoPoint(0.0, longitude),
                bearing = bearing, nextStopId = null, observedAt = observedAt, ageSeconds = 0,
                positionKind = VehiclePositionKind.Gps,
            )
            val page = VehiclePage(listOf(bus), observedAt, maxAgeSeconds = 30, stale = false)
            presentation.acceptResult(route.id, TransitLoadResult.Data(page, TransitFreshness.Network))
            presentation.updateVehicleFrames(forceSourceCheck = true)
        }

        fun assertBearing(expected: Double) {
            assertEquals(expected, assertNotNull(presentation.vehicleMarkers.single().bearingDegrees), absoluteTolerance = 0.1)
        }
    }
}
