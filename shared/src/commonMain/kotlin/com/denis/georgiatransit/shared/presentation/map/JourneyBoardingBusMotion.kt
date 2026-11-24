package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import kotlin.math.abs

/** Animates only the exact vehicle assigned to the first ride. */
internal class JourneyBoardingBusMotion(
    private val boarding: JourneyBoarding,
    private val path: PreparedVehicleRoutePath?,
    private val clock: VehicleRealtimeClock,
) {
    private var state = VehicleRealtimeState()
    private var animationTrack: VehicleTrack? = null
    private var routeMotion: VehicleRouteMotion? = null
    private var vehicle: TransitVehicle? = null
    private var headings = headingSource()

    fun accept(result: TransitLoadResult<VehiclePage>?) {
        val data = (result as? TransitLoadResult.Data)
            ?.takeUnless { it.value.stale || it.freshness == TransitFreshness.StaleOffline }
            ?: return clear()
        val page = data.value
        val incoming = page.items.firstOrNull {
            it.id == boarding.plannedVehicleId && it.routeId == boarding.routeId &&
                (it.ageSeconds ?: 0) <= page.maxAgeSeconds
        } ?: return clear()

        val now = clock.monotonicNowMillis()
        val wallNow = clock.wallNow()
        state = VehicleRealtimeReducer.expire(state, wallNow)
        val previous = state.tracks.singleOrNull()
        val reduction = VehicleRealtimeReducer.acceptPage(
            state, boarding.routeId, page.copy(items = listOf(incoming)), data.freshness, wallNow, now,
        )
        state = when (reduction) {
            is VehiclePageReduction.Applied -> reduction.state
            is VehiclePageReduction.IgnoredOlder -> return
            is VehiclePageReduction.Rejected -> reduction.state
        }
        val track = state.tracks.singleOrNull()?.takeUnless { it.isStale }
        if (track == null) {
            clearFrame()
            return
        }
        if (track == previous && animationTrack != null) return
        vehicle = incoming.copy(bearing = headings.update(listOf(incoming))[incoming.id])
        val currentAnimation = animationTrack
        if (currentAnimation != null && previous?.to == track.to) {
            animationTrack = currentAnimation.copy(expiresAt = track.expiresAt)
            return
        }
        val start = frame(now)?.position ?: track.from
        val animate = track.interpolationStartedAtMonotonicMillis != null && start != track.to
        animationTrack = track.copy(from = start, interpolationStartedAtMonotonicMillis = now.takeIf { animate })
        routeMotion = if (animate && path != null) {
            VehicleRoutePathInterpolator.motion(start, track.to, boarding.directionId, listOf(path), routeMotion)
        } else null
    }

    fun frame(now: Long = clock.monotonicNowMillis()): TransitVehicle? {
        val bus = vehicle ?: return null
        val track = animationTrack?.takeIf { it.expiresAt > clock.wallNow() } ?: return null
        val fraction = track.interpolationFraction(now)
        val motion = routeMotion
        return bus.copy(
            position = motion?.positionAt(fraction) ?: VehicleRealtimeReducer.frame(track, now),
            bearing = motion?.takeIf { abs(it.deltaMeters) >= MIN_JOURNEY_HEADING_MOVEMENT_METERS }
                ?.bearingAt(fraction) ?: bus.bearing,
        )
    }

    fun isMoving(): Boolean = animationTrack?.let { track ->
        track.interpolationStartedAtMonotonicMillis != null && track.interpolationFraction(clock.monotonicNowMillis()) < 1.0
    } == true

    private fun clear() {
        state = VehicleRealtimeState()
        clearFrame()
    }

    private fun clearFrame() {
        animationTrack = null
        vehicle = null
        routeMotion = null
        headings = headingSource()
    }

    private fun headingSource() = JourneyBusHeadings(path, boarding.stopPosition, boarding.departureBearing)
}
