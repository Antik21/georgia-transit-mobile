package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class JourneyMapProjectionTest {
    @Test
    fun closedPlannerWithoutPinLeavesTheSessionRenderUntouched() {
        assertSame(base, base.withJourneyPlanner(JourneyPlannerUi()))
    }

    @Test
    fun pinAloneAddsAnOverlayAndKeepsSessionVehicles() {
        val projected = base.withJourneyPlanner(JourneyPlannerUi(pin = pin))

        assertEquals(MapJourneyOverlay(pin = pin), projected.journey)
        assertEquals(base.vehicles, projected.vehicles)
        assertEquals(base.vehicleSourceRevision, projected.vehicleSourceRevision)
    }

    @Test
    fun drawnPlanKeepsOnlyItsNearestBusesOnTheVehicleLayer() {
        val projected = base.withJourneyPlanner(planned(buses(bus("bus-1", 41.64), bus("bus-2", 41.65))))

        val overlay = checkNotNull(projected.journey)
        assertEquals(render.lines, overlay.lines)
        assertEquals(origin, overlay.origin)
        assertEquals(destination, overlay.destination)
        assertEquals(7L, overlay.fitRevision)
        assertEquals(listOf("bus-1", "bus-2"), projected.vehicles.map { it.stableId })
        val first = projected.vehicles.first()
        assertEquals(RouteId("10a"), first.routeId)
        assertEquals(DirectionId("0"), first.directionId)
        assertEquals("10A", first.routeLabel)
        assertEquals(0xFF1565C0L, first.routeColorArgb)
        assertEquals(0xFFFFFFFFL, first.routeTextColorArgb)
        assertEquals(VehiclePositionKind.Gps, first.positionKind)
        assertEquals(TransitFreshness.Network, first.freshness)
        assertTrue(projected.vehicleSourceRevision < 0 && projected.vehicleBadgeRevision < 0)
    }

    @Test
    fun mapPickingSuspendsCameraFitsWithoutHidingThePlan() {
        val projected = base.withJourneyPlanner(planned(buses = null).copy(mapPick = JourneyPlanSide.Destination))

        val overlay = checkNotNull(projected.journey)
        assertTrue(overlay.fitSuspended)
        assertEquals(render.lines, overlay.lines)
    }

    @Test
    fun movedBusChangesTheSourceRevisionButNotTheBadgeRevision() {
        val before = base.withJourneyPlanner(planned(buses(bus("bus-1", 41.64))))
        val after = base.withJourneyPlanner(planned(buses(bus("bus-1", 41.641))))

        assertNotEquals(before.vehicleSourceRevision, after.vehicleSourceRevision)
        assertEquals(before.vehicleBadgeRevision, after.vehicleBadgeRevision)
    }

    @Test
    fun planWithoutBusesClearsTheVehicleLayer() {
        val projected = base.withJourneyPlanner(planned(buses = null))

        assertTrue(projected.vehicles.isEmpty())
        assertNotEquals(base.vehicleSourceRevision, projected.vehicleSourceRevision)
    }

    @Test
    fun openPlannerWithoutLinesDrawsEndpointsOnly() {
        val planner = JourneyPlannerUi(isOpen = true, render = JourneyPlanRenderUi(origin = origin), cameraFitRevision = 3)

        val projected = base.withJourneyPlanner(planner)

        assertEquals(MapJourneyOverlay(origin = origin, fitRevision = 3), projected.journey)
        assertEquals(base.vehicles, projected.vehicles)
    }

    private fun planned(buses: JourneyBusesUi?) = JourneyPlannerUi(
        isOpen = true,
        render = render,
        buses = buses,
        cameraFitRevision = 7,
    )

    private fun buses(vararg vehicles: JourneyBusUi) = JourneyBusesUi(
        optionId = JourneyId("option"),
        boarding = JourneyBoarding(StopId("stop"), RouteId("10a"), DirectionId("0")),
        etaMinutes = vehicles.mapIndexed { index, _ -> 3 + index * 8 },
        vehicles = vehicles.toList(),
    )

    private fun bus(id: String, latitude: Double) = JourneyBusUi(
        id = VehicleId(id),
        position = GeoPoint(latitude, 41.63),
        bearingDegrees = 90.0,
        etaMinutes = 3,
        route = JourneyRouteBadgeUi(RouteId("10a"), "10A", 0xFF1565C0L, 0xFFFFFFFFL),
    )

    private companion object {
        val origin = GeoPoint(41.6400, 41.6300)
        val destination = GeoPoint(41.6500, 41.6400)
        val pin = GeoPoint(41.6450, 41.6350)
        val render = JourneyPlanRenderUi(
            lines = persistentListOf(
                JourneyPlanLineUi(JourneyPlanLineKind.Walk, persistentListOf(origin, GeoPoint(41.641, 41.631)), JourneyWalkLineArgb),
                JourneyPlanLineUi(JourneyPlanLineKind.Transit, persistentListOf(GeoPoint(41.641, 41.631), destination), 0xFF1565C0L),
            ),
            origin = origin,
            destination = destination,
        )
        val base = MapRenderState(
            camera = MapCameraCommand(center = origin, zoom = 13.0, revision = 1),
            vehicles = persistentListOf(
                MapVehicleMarker(
                    id = VehicleId("session-bus"),
                    routeId = RouteId("7"),
                    directionId = null,
                    position = destination,
                    routeColorArgb = 0xFF2E7D32L,
                    bearingDegrees = null,
                    positionKind = VehiclePositionKind.Gps,
                    freshness = TransitFreshness.Network,
                    routeLabel = "7",
                    routeTextColorArgb = 0xFFFFFFFFL,
                    isStale = false,
                    frameRevision = 4,
                ),
            ),
            vehicleSourceRevision = 4,
            vehicleBadgeRevision = 2,
        )
    }
}
