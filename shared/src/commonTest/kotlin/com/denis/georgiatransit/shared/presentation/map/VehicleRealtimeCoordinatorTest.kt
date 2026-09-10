package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitVehicle
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.domain.model.VehiclePage
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/**
 * Boundary tests for the pure realtime reducer. These use only handwritten normalized BFF values;
 * no provider contract or live service is involved.
 */
class VehicleRealtimeCoordinatorTest {
    @Test
    fun exactOpaqueIdInterpolatesOnlyAtTheDistanceSpeedAndObservationBoundaries() {
        val first = applied(page(items = listOf(vehicle("opaque-id", point = origin))))
        val acceptedAtSpeedBoundary = applied(
            page(
                items = listOf(vehicle("opaque-id", point = metersNorth(24.99), observedAt = now + seconds(1))),
                observedAt = now + seconds(1),
            ),
            state = first,
        )
        assertEquals(metersNorth(24.99), acceptedAtSpeedBoundary.tracks.single().to)

        val acceptedAtBoundary = applied(
            page(
                items = listOf(vehicle("opaque-id", point = metersNorth(249.9), observedAt = now + seconds(20))),
                observedAt = now + seconds(20),
            ),
            state = first,
            monotonicNowMillis = 500,
        )
        val moved = acceptedAtBoundary.tracks.single()

        assertEquals(VehicleId("opaque-id"), moved.id)
        assertEquals(origin, moved.from)
        assertEquals(metersNorth(249.9), moved.to)
        assertEquals(500L, moved.interpolationStartedAtMonotonicMillis)
        assertTrue(!moved.isStale)

        val equalObservation = applied(
            page(items = listOf(vehicle("opaque-id", point = metersNorth(5), observedAt = now + seconds(20))), observedAt = now + seconds(20)),
            state = acceptedAtBoundary,
        )
        assertEquals(metersNorth(249.9), equalObservation.tracks.single().to)
        assertTrue(equalObservation.tracks.single().isStale)

        val overSpeed = applied(
            page(
                items = listOf(vehicle("opaque-id", point = metersNorth(25.1), observedAt = now + seconds(1))),
                observedAt = now + seconds(1),
            ),
            state = first,
        )
        assertEquals(origin, overSpeed.tracks.single().to)
        assertTrue(overSpeed.tracks.single().isStale)

        val tooFar = applied(
            page(
                items = listOf(vehicle("opaque-id", point = metersNorth(251), observedAt = now + seconds(20))),
                observedAt = now + seconds(20),
            ),
            state = first,
        )
        assertEquals(origin, tooFar.tracks.single().to)
        assertTrue(tooFar.tracks.single().isStale)

        val overTwentySeconds = applied(
            page(
                items = listOf(vehicle("opaque-id", point = metersNorth(1), observedAt = now + seconds(21))),
                observedAt = now + seconds(21),
            ),
            state = first,
        )
        assertEquals(origin, overTwentySeconds.tracks.single().to)
        assertTrue(overTwentySeconds.tracks.single().isStale)
    }

    @Test
    fun reidentifiedVehiclesUseDeterministicNearestOneToOneStitchingIndependentOfInputOrder() {
        val previous = VehicleRealtimeState(
            tracks = listOf(
                track("old-a", point = GeoPoint(0.0, 0.0)),
                track("old-b", point = GeoPoint(0.0, 0.001)),
            ).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val incoming = listOf(
            vehicle("new-b", point = GeoPoint(0.0, 0.0011), observedAt = now + seconds(10)),
            vehicle("new-a", point = GeoPoint(0.0, 0.0001), observedAt = now + seconds(10)),
        )

        val forward = applied(page(incoming, observedAt = now + seconds(10)), state = previous)
        val reversed = applied(page(incoming.reversed(), observedAt = now + seconds(10)), state = previous)

        assertEquals(forward, reversed)
        assertEquals(
            listOf(VehicleId("old-a"), VehicleId("old-b")),
            forward.tracks.map(VehicleTrack::id),
        )
        assertEquals(GeoPoint(0.0, 0.0001), forward.tracks[0].to)
        assertEquals(GeoPoint(0.0, 0.0011), forward.tracks[1].to)
        assertTrue(forward.tracks.all { it.interpolationStartedAtMonotonicMillis == null })
    }

    @Test
    fun reidentificationNeverCrossesRouteOrDirectionButHandlesAntimeridianAndPoleSafely() {
        val directionMismatch = VehicleRealtimeState(
            tracks = listOf(track("old", direction = directionA)).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val mismatched = applied(
            page(
                items = listOf(vehicle("new", direction = directionB, observedAt = now + seconds(10))),
                observedAt = now + seconds(10),
            ),
            state = directionMismatch,
        )
        // A fresh page replaces the route, but the new direction may never inherit the old opaque
        // identity. This guards against a false re-identification across direction variants.
        assertEquals(listOf(VehicleId("new")), mismatched.tracks.map(VehicleTrack::id))

        val antimeridian = VehicleRealtimeState(
            tracks = listOf(track("old-date-line", point = GeoPoint(0.0, 179.999))).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val antimeridianResult = applied(
            page(
                items = listOf(vehicle("new-date-line", point = GeoPoint(0.0, -179.999), observedAt = now + seconds(10))),
                observedAt = now + seconds(10),
            ),
            state = antimeridian,
        )
        assertEquals(VehicleId("old-date-line"), antimeridianResult.tracks.single().id)

        val polar = VehicleRealtimeState(
            tracks = listOf(track("old-polar", point = GeoPoint(89.999, 0.0))).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val polarResult = applied(
            page(
                items = listOf(vehicle("new-polar", point = GeoPoint(89.999, 90.0), observedAt = now + seconds(10))),
                observedAt = now + seconds(10),
            ),
            state = polar,
        )
        assertEquals(VehicleId("old-polar"), polarResult.tracks.single().id)
    }

    @Test
    fun duplicateInvalidWrongRouteRegressingAndFuturePagesFailClosedWithoutPartialWrites() {
        val state = applied(page(items = listOf(vehicle("known"))))
        val duplicate = reduction(
            page(items = listOf(vehicle("duplicate"), vehicle("duplicate"))),
            state = state,
        )
        assertPausedAndCleared(duplicate)

        val wrongRoute = reduction(page(items = listOf(vehicle("wrong-route", route = routeB))), state = state)
        assertPausedAndCleared(wrongRoute)

        val invalidCoordinate = reduction(
            page(items = listOf(vehicle("invalid", point = GeoPoint(91.0, 0.0)))),
            state = state,
        )
        assertPausedAndCleared(invalidCoordinate)

        val older = reduction(
            page(items = listOf(vehicle("known", observedAt = now - seconds(1))), observedAt = now - seconds(1)),
            state = state,
        )
        assertIs<VehiclePageReduction.IgnoredOlder>(older)
        assertEquals(state, older.state)

        val future = reduction(
            page(items = listOf(vehicle("future", observedAt = now + seconds(31))), observedAt = now + seconds(31)),
            state = state,
        )
        assertPausedAndCleared(future)
    }

    @Test
    fun staleEmptyRetainsWatermarkWhileFreshEmptyClearsAndFailureStatesStayRouteScoped() {
        val initial = applied(page(items = listOf(vehicle("kept"))))
        val staleEmpty = applied(page(emptyList(), observedAt = now + seconds(5), stale = true), state = initial)
        assertEquals(listOf(VehicleId("kept")), staleEmpty.tracks.map(VehicleTrack::id))
        assertEquals(now + seconds(5), staleEmpty.latestObservedAtByRoute[routeA])
        assertEquals(VehicleRoutePhase.Stale, staleEmpty.routePhases[routeA])

        val retryable = VehicleRealtimeReducer.handleFailure(staleEmpty, routeA, TransitFailure.Transport("offline"))
        assertEquals(VehicleRoutePhase.Retryable, retryable.routePhases[routeA])
        assertTrue(retryable.tracks.single().isStale)

        val unavailable = VehicleRealtimeReducer.handleFailure(retryable, routeA, TransitFailure.Configuration("closed"))
        assertTrue(unavailable.tracks.isEmpty())
        assertEquals(VehicleRoutePhase.Unavailable, unavailable.routePhases[routeA])
        assertTrue(routeA in unavailable.pausedRoutes)

        val freshEmpty = applied(page(emptyList(), observedAt = now + seconds(6)), state = initial)
        assertTrue(freshEmpty.tracks.isEmpty())
        assertEquals(VehicleRoutePhase.Live, freshEmpty.routePhases[routeA])
        assertEquals(now + seconds(6), freshEmpty.latestObservedAtByRoute[routeA])
    }

    @Test
    fun ttlClampsAtOneAndNinetySecondsAndUnsafeOverflowInputsAreRejected() {
        val oneSecond = applied(page(items = listOf(vehicle("one")), maxAgeSeconds = 0))
        assertTrue(VehicleRealtimeReducer.expire(oneSecond, now + millis(999)).tracks.isNotEmpty())
        assertTrue(VehicleRealtimeReducer.expire(oneSecond, now + seconds(1)).tracks.isEmpty())

        val ninetySeconds = applied(page(items = listOf(vehicle("ninety")), maxAgeSeconds = 9_999))
        assertTrue(VehicleRealtimeReducer.expire(ninetySeconds, now + seconds(89)).tracks.isNotEmpty())
        assertTrue(VehicleRealtimeReducer.expire(ninetySeconds, now + seconds(90)).tracks.isEmpty())

        val unsafe = reduction(
            page(items = listOf(vehicle("unsafe", observedAt = Instant.DISTANT_FUTURE)), observedAt = Instant.DISTANT_FUTURE),
            state = VehicleRealtimeState(),
        )
        assertPausedAndCleared(unsafe)
    }

    @Test
    fun oversizedPageAndCandidateBudgetAreRejectedOrHeldStaleBeforeUnboundedMatching() {
        val oversized = reduction(
            page((0..1_000).map { vehicle("over-$it") }),
            state = VehicleRealtimeState(),
        )
        assertPausedAndCleared(oversized)

        val densePrevious = (0 until 1_000).map { index ->
            track("old-$index", point = GeoPoint(89.999, 0.0))
        }
        val state = VehicleRealtimeState(
            tracks = densePrevious.toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
            routePhases = persistentMapOf(routeA to VehicleRoutePhase.Live),
        )
        val denseIncoming = (0 until 1_000).map { index ->
            vehicle("new-$index", point = GeoPoint(89.999, 0.0), observedAt = now + seconds(10))
        }
        val result = applied(page(denseIncoming, observedAt = now + seconds(10)), state = state)

        assertEquals(densePrevious.map(VehicleTrack::id), result.tracks.map(VehicleTrack::id))
        assertEquals(VehicleRoutePhase.Stale, result.routePhases[routeA])
        assertTrue(result.tracks.all(VehicleTrack::isStale))
    }

    @Test
    fun denseBucketKeepsNearestSpeedAdmissibleCandidateWithinTopKAndFramesClampAfterSchedulerJitter() {
        val previous = VehicleRealtimeState(
            tracks = listOf(track("old", point = origin)).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val incoming = (0 until 33).map { index ->
            vehicle(
                id = "new-${index.toString().padStart(2, '0')}",
                point = metersNorth(10.0 + index),
                observedAt = now + seconds(20),
            )
        }.reversed()
        val stitched = applied(page(incoming, observedAt = now + seconds(20)), state = previous)
        val retainedIdentity = stitched.tracks.single { it.id == VehicleId("old") }
        assertEquals(metersNorth(10.0), retainedIdentity.to)

        val interpolating = track(
            id = "frame",
            point = origin,
            to = metersNorth(100),
            interpolationStartedAtMonotonicMillis = 100,
        )
        assertEquals(origin, VehicleRealtimeReducer.frame(interpolating, 100))
        assertEquals(
            metersNorth(50),
            VehicleRealtimeReducer.frame(interpolating, 100 + VEHICLE_INTERPOLATION_DURATION_MILLIS / 2),
        )
        assertEquals(
            metersNorth(100),
            VehicleRealtimeReducer.frame(interpolating, 100 + VEHICLE_INTERPOLATION_DURATION_MILLIS + 3_000),
        )
    }

    @Test
    fun topKDoesNotDiscardTheThirtyThirdCandidateWhenCloserCandidatesFailTheOneMillisecondSpeedGate() {
        val previous = VehicleRealtimeState(
            tracks = listOf(track("retained", point = origin)).toPersistentList(),
            latestObservedAtByRoute = persistentMapOf(routeA to now),
        )
        val observedAt = now + seconds(10)
        val incoming = buildList {
            repeat(32) { index ->
                add(
                    vehicle(
                        id = "near-speed-ineligible-${index.toString().padStart(2, '0')}",
                        point = metersNorth(1),
                        observedAt = now + millis(1),
                    ),
                )
            }
            add(vehicle("farther-but-valid", point = metersNorth(10), observedAt = observedAt))
        }

        val stitched = applied(page(incoming, observedAt = observedAt), state = previous)
        val retained = stitched.tracks.single { it.id == VehicleId("retained") }
        assertEquals(metersNorth(10), retained.to)
        assertTrue(!retained.isStale)
    }

    @Test
    fun identicalLowGapHighLatitudeCandidatesRemainEligibleWithStableHaversineA() {
        listOf(88.0, -88.0).forEach { latitude ->
            val point = GeoPoint(latitude, 47.0)
            val previous = VehicleRealtimeState(
                tracks = listOf(track("retained-$latitude", point = point)).toPersistentList(),
                latestObservedAtByRoute = persistentMapOf(routeA to now),
            )

            val stitched = applied(
                page(
                    items = listOf(
                        vehicle(
                            id = "reidentified-$latitude",
                            point = point,
                            observedAt = now + millis(1),
                        ),
                    ),
                    observedAt = now + millis(1),
                ),
                state = previous,
            )

            assertEquals(VehicleId("retained-$latitude"), stitched.tracks.single().id)
            assertEquals(point, stitched.tracks.single().to)
        }
    }

    private fun reduction(
        page: VehiclePage,
        state: VehicleRealtimeState = VehicleRealtimeState(),
        monotonicNowMillis: Long = 0,
    ): VehiclePageReduction = VehicleRealtimeReducer.acceptPage(
        state = state,
        requestedRouteId = routeA,
        page = page,
        freshness = TransitFreshness.Network,
        wallReceivedAt = now,
        monotonicNowMillis = monotonicNowMillis,
    )

    private fun applied(
        page: VehiclePage,
        state: VehicleRealtimeState = VehicleRealtimeState(),
        monotonicNowMillis: Long = 0,
    ): VehicleRealtimeState = assertIs<VehiclePageReduction.Applied>(reduction(page, state, monotonicNowMillis)).state

    private fun assertPausedAndCleared(reduction: VehiclePageReduction) {
        val rejected = assertIs<VehiclePageReduction.Rejected>(reduction).state
        assertTrue(rejected.tracks.none { it.routeId == routeA })
        assertEquals(VehicleRoutePhase.Unavailable, rejected.routePhases[routeA])
        assertTrue(routeA in rejected.pausedRoutes)
    }

    private fun page(
        items: List<TransitVehicle>,
        observedAt: Instant = now,
        maxAgeSeconds: Int = 30,
        stale: Boolean = false,
    ) = VehiclePage(items = items, observedAt = observedAt, maxAgeSeconds = maxAgeSeconds, stale = stale)

    private fun vehicle(
        id: String,
        route: RouteId = routeA,
        direction: DirectionId? = directionA,
        point: GeoPoint = origin,
        observedAt: Instant? = now,
    ) = TransitVehicle(
        id = VehicleId(id),
        routeId = route,
        directionId = direction,
        position = point,
        bearing = 90.0,
        nextStopId = null,
        observedAt = observedAt,
        ageSeconds = 0,
        positionKind = VehiclePositionKind.Gps,
    )

    private fun track(
        id: String,
        route: RouteId = routeA,
        direction: DirectionId? = directionA,
        point: GeoPoint = origin,
        to: GeoPoint = point,
        interpolationStartedAtMonotonicMillis: Long? = null,
    ) = VehicleTrack(
        id = VehicleId(id),
        routeId = route,
        directionId = direction,
        from = point,
        to = to,
        bearingDegrees = 90.0,
        positionKind = VehiclePositionKind.Gps,
        observedAt = now,
        expiresAt = now + seconds(30),
        freshness = TransitFreshness.Network,
        isStale = false,
        interpolationStartedAtMonotonicMillis = interpolationStartedAtMonotonicMillis,
    )

    private fun metersNorth(meters: Number): GeoPoint = GeoPoint(meters.toDouble() / 111_195.0, 0.0)

    private fun seconds(value: Int) = value.toLong().toDuration(DurationUnit.SECONDS)
    private fun millis(value: Int) = value.toLong().toDuration(DurationUnit.MILLISECONDS)

    private companion object {
        val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val origin = GeoPoint(0.0, 0.0)
        val routeA = RouteId("route-a")
        val routeB = RouteId("route-b")
        val directionA = DirectionId("direction-a")
        val directionB = DirectionId("direction-b")
    }
}
