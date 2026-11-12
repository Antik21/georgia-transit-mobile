package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyBusHeadingsTest {
    // An out-and-back loop on one street: east along the north kerb, back west along the south kerb.
    private val loop = checkNotNull(
        PreparedVehicleRoutePath.from(
            MapPolyline(
                ROUTE, DirectionId("demo:fixture:direction:technical"),
                persistentListOf(
                    GeoPoint(41.64003, 41.600), GeoPoint(41.64003, 41.610),
                    GeoPoint(41.64000, 41.610), GeoPoint(41.64000, 41.600), GeoPoint(41.64003, 41.600),
                ),
                routeColorArgb = 0, freshness = TransitFreshness.Network,
            ),
        ),
    )
    private val westboundStop = GeoPoint(41.64000, 41.603)

    @Test
    fun aParkedBusWithoutFeedBearingFacesTheWayItApproachesTheBoardingStop() {
        val headings = JourneyBusHeadings(loop, westboundStop, departureBearing = WEST)

        val heading = headings.update(listOf(bus(41.606)))[BUS]

        assertFacing(WEST, heading)
    }

    @Test
    fun theStopBranchFollowsTheRideDirectionNotTheNearestKerb() {
        val headings = JourneyBusHeadings(loop, GeoPoint(41.64002, 41.603), departureBearing = WEST)

        assertFacing(WEST, headings.update(listOf(bus(41.606)))[BUS])
    }

    @Test
    fun observedMovementWinsOverTheStopApproach() {
        val headings = JourneyBusHeadings(loop, westboundStop, departureBearing = WEST)
        headings.update(listOf(bus(41.605)))

        assertFacing(EAST, headings.update(listOf(bus(41.606)))[BUS])
    }

    @Test
    fun withoutAPathTheHeadingComesFromMovementAndSurvivesAStop() {
        val headings = JourneyBusHeadings(path = null, boardingStop = null, departureBearing = null)
        assertNull(headings.update(listOf(bus(41.606)))[BUS])

        assertFacing(WEST, headings.update(listOf(bus(41.605)))[BUS])
        assertFacing(WEST, headings.update(listOf(bus(41.605)))[BUS])
    }

    @Test
    fun aFeedBearingIsTheLastResort() {
        val headings = JourneyBusHeadings(path = null, boardingStop = null, departureBearing = null)

        assertEquals(42.0, headings.update(listOf(bus(41.606, bearing = 42.0)))[BUS])
    }

    private fun assertFacing(expected: Double, actual: Double?) {
        assertNotNull(actual)
        val difference = abs(expected - actual) % 360.0
        assertTrue(minOf(difference, 360.0 - difference) < 10.0, "expected ≈$expected°, got $actual°")
    }

    private fun bus(longitude: Double, bearing: Double? = null) = TransitVehicle(
        id = BUS, routeId = ROUTE, directionId = null, position = GeoPoint(41.64001, longitude), bearing = bearing,
        nextStopId = null, observedAt = null, ageSeconds = null, positionKind = VehiclePositionKind.Gps,
    )

    private companion object {
        const val EAST = 90.0
        const val WEST = 270.0
        val ROUTE = RouteId("demo:fixture:route:loop")
        val BUS = VehicleId("demo:fixture:vehicle:loop-1")
    }
}
