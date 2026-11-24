package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class JourneyBoardingBusMotionTest {
    private val clock = TestClock()
    private val motion = JourneyBoardingBusMotion(
        JourneyBoarding(StopId("stop"), route, plannedVehicleId = bus), path = null, clock,
    )

    @Test
    fun aFreshObservationAfterAGapRestartsMotionWithoutAnimatingAcrossTheGap() {
        val original = GeoPoint(41.64, 41.63)
        val resumed = original.copy(latitude = 41.641)
        val following = original.copy(latitude = 41.642)
        motion.accept(page(original))
        assertEquals(original, motion.frame()?.position)

        clock.elapsed = 24_000
        motion.accept(page(following, observedAt = clock.instantAt(-1_000), vehicleObservedAt = clock.wallNow()))
        assertEquals(original, motion.frame()?.position, "A newer vehicle timestamp cannot override an older route page")

        motion.accept(page(resumed))
        assertEquals(resumed, motion.frame()?.position)
        assertNull(motion.frame()?.bearing, "The skipped interval must not supply a heading")
        assertFalse(motion.isMoving())

        motion.accept(page(original, observedAt = clock.instantAt(16_000)))
        assertEquals(resumed, motion.frame()?.position, "An older route page must not displace the recovered marker")

        clock.elapsed = 32_000
        motion.accept(page(following))
        assertEquals(resumed, motion.frame()?.position)
        assertTrue(motion.isMoving())
        clock.elapsed += VEHICLE_INTERPOLATION_DURATION_MILLIS / 2
        val midpoint = assertNotNull(motion.frame()).position
        assertTrue(midpoint.latitude > resumed.latitude && midpoint.latitude < following.latitude)
    }

    @Test
    fun aNewPageCannotRebaselineWithAnOlderIndividualObservation() {
        val original = GeoPoint(41.64, 41.63)
        val outdated = original.copy(latitude = 41.641)
        motion.accept(page(original))

        clock.elapsed = 24_000
        motion.accept(page(outdated, vehicleObservedAt = clock.instantAt(-1_000)))

        assertNotEquals(outdated, motion.frame()?.position)
    }

    private fun page(position: GeoPoint, observedAt: Instant = clock.wallNow(), vehicleObservedAt: Instant? = null) =
        TransitLoadResult.Data(
            VehiclePage(
                items = listOf(
                    TransitVehicle(bus, route, null, position, null, null, vehicleObservedAt, null, VehiclePositionKind.Gps),
                ),
                observedAt = observedAt,
                maxAgeSeconds = 48,
                stale = false,
            ),
            TransitFreshness.Network,
        )

    private class TestClock : VehicleRealtimeClock {
        var elapsed = 0L
        fun instantAt(millis: Long) = Instant.fromEpochMilliseconds(1_900_000_000_000L + millis)
        override fun wallNow() = instantAt(elapsed)
        override fun monotonicNowMillis() = elapsed
    }

    private companion object {
        val bus = VehicleId("bus")
        val route = RouteId("route")
    }
}
