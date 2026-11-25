package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class JourneyBoardingPassageTest {
    private val boarding = JourneyBoarding(StopId("stop"), RouteId("route"), DirectionId("direction"),
        GeoPoint(41.645, 41.63), 0.0, VehicleId("bus"))
    private val path = PreparedVehicleRoutePath.from(MapPolyline(boarding.routeId, boarding.directionId,
        persistentListOf(GeoPoint(41.64, 41.63), GeoPoint(41.65, 41.63)), 0, TransitFreshness.Network))!!
    private val tracker = JourneyBoardingPassage(boarding)
    private fun fix(lat: Double, time: Long) = TransitVehicle(boarding.plannedVehicleId!!, boarding.routeId, null,
        GeoPoint(lat, 41.63), null, null, Instant.fromEpochMilliseconds(time), 0, VehiclePositionKind.Gps)
    private fun observe(bus: TransitVehicle, stale: Boolean = false, now: Long = bus.observedAt!!.toEpochMilliseconds()) =
        tracker.observe(VehiclePage(listOf(bus), bus.observedAt!!, 30, stale), path, now)

    @Test
    fun onlyFreshOrderedGpsFromTheChosenBusConfirmsForwardPassage() {
        val before = fix(41.644, 10_000)
        val after = fix(41.646, 30_000)
        assertFalse(observe(before))
        assertFalse(observe(after.copy(id = VehicleId("other"))))
        assertFalse(observe(after.copy(directionId = DirectionId("return"))))
        assertFalse(observe(after.copy(positionKind = VehiclePositionKind.Estimated)))
        assertFalse(observe(after, stale = true))
        assertFalse(observe(after, now = 70_000))
        assertFalse(observe(after.copy(observedAt = before.observedAt)))
        assertFalse(observe(fix(41.6451, 20_000)), "GPS jitter at the stop is not departure")
        assertTrue(observe(after))
    }

    @Test
    fun reverseMovementOrASingleDownstreamPositionCannotConfirmPassage() {
        assertFalse(observe(fix(41.646, 10_000)))
        assertFalse(observe(fix(41.644, 30_000)))
        assertFalse(observe(fix(41.646, 180_000)), "The approach observation expired")
    }
}
