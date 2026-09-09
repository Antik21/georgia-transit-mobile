package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * DEN-69's behaviour is deliberately tested at the common boundary. The fake controls completion
 * and cancellation so these assertions do not infer native geometry from screen semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteGeometryCoordinatorTest {
    @Test
    fun decoderPreservesKnownPrecisionFiveAndSixCoordinateOrder() {
        val expected = listOf(
            GeoPoint(38.5, -120.2),
            GeoPoint(40.7, -120.95),
            GeoPoint(43.252, -126.453),
        )

        assertContentEquals(
            expected,
            assertIs<EncodedPolylineDecodeResult.Success>(
                decodeEncodedPolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5),
            ).points,
        )
        assertContentEquals(
            expected,
            assertIs<EncodedPolylineDecodeResult.Success>(
                decodeEncodedPolyline("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", precision = 6),
            ).points,
        )
    }

    @Test
    fun decoderRejectsInvalidPrecisionEmptyTruncatedMalformedOverflowAndUnsafeGeometry() {
        val tooManyPoints = buildList {
            repeat(20_001) { index ->
                add(GeoPoint(if (index % 2 == 0) 0.0 else 0.00001, 0.0))
            }
        }
        val cases = listOf(
            decodeEncodedPolyline("_p~iF~ps|U", precision = -1),
            decodeEncodedPolyline("_p~iF~ps|U", precision = 9),
            decodeEncodedPolyline("", precision = 5),
            decodeEncodedPolyline("_p~iF", precision = 5),
            decodeEncodedPolyline("\u0000", precision = 5),
            // Unsigned all-ones decodes to Long.MIN_VALUE; coordinate accumulation must fail closed.
            decodeEncodedPolyline("~~~~~~~~~~~~N?A?", precision = 0),
            decodeEncodedPolyline("~~~~~~~~~~~~?", precision = 0),
            decodeEncodedPolyline(encodePolyline(listOf(GeoPoint(91.0, 0.0), GeoPoint(91.1, 0.0)), 5), precision = 5),
            decodeEncodedPolyline(encodePolyline(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)), 5), precision = 5),
            decodeEncodedPolyline(encodePolyline(tooManyPoints, 5), precision = 5),
            decodeEncodedPolyline("?".repeat(512_001), precision = 5),
        )

        cases.forEach { result -> assertIs<EncodedPolylineDecodeResult.Invalid>(result) }
    }

    @Test
    fun canonicalRouteCatalogAndDirectionOrderProduceTwentyUniqueOrderedLinesWithoutDuplicateLoads() = runCoordinatorTest {
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository)
        val routes = (1..10).map { route(it) }
        val snapshots = mutableListOf<RouteGeometrySnapshot>()

        coordinator.update(this, cityId, true, routes, true, snapshots::add)
        runCurrent()
        val expectedKeys = routes.flatMap { route -> route.directions.map { direction -> ShapeKey(cityId, route.id, direction.id) } }
        assertEquals(expectedKeys, repository.calls)
        assertEquals(20, repository.calls.toSet().size)

        // The repeated collector emission has the same session/city/lifecycle input and must not
        // create another request for any key.
        coordinator.update(this, cityId, true, routes, true, snapshots::add)
        runCurrent()
        assertEquals(expectedKeys, repository.calls)

        expectedKeys.forEach { repository.complete(it, goodShape()) }
        runCurrent()

        val ready = snapshots.last()
        assertEquals(expectedKeys.map { it.routeId to it.directionId }, ready.polylines.map { it.routeId to it.directionId })
        assertEquals(20, ready.polylines.size)
        assertTrue(ready.legends.all { it.state == RouteGeometryLegendState.Ready })
        coordinator.clear()
    }

    @Test
    fun focusAndSelectionReplacementKeepOnlyTheCurrentRoutesInOneSnapshot() = runCoordinatorTest {
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository)
        val first = route(1)
        val second = route(2)
        val snapshots = mutableListOf<RouteGeometrySnapshot>()

        coordinator.update(this, cityId, true, listOf(first, second), true, snapshots::add)
        runCurrent()
        repository.calls.toList().forEach { repository.complete(it, goodShape()) }
        runCurrent()
        val beforeFocus = snapshots.last()

        coordinator.focus(second.id)
        val focused = snapshots.last()
        assertTrue(focused.polylines.filter { it.routeId == second.id }.all(MapPolyline::isEmphasized))
        assertTrue(focused.polylines.filter { it.routeId == first.id }.none(MapPolyline::isEmphasized))
        assertTrue(focused.polylines.filter { it.routeId == second.id }.all { it.strokeWidth == 6.0 && it.opacity == 1.0 })
        assertTrue(focused.polylines.filter { it.routeId == first.id }.all { it.strokeWidth == 4.0 && it.opacity == 1.0 })
        assertNotEquals(beforeFocus.polylineSourceRevision, focused.polylineSourceRevision)

        // This is the coordinator-side atomic counterpart of the session's replacement mutation:
        // a single returned snapshot removes every direction and legend for the deleted route.
        val replacement = coordinator.update(this, cityId, true, listOf(second), true, snapshots::add)
        assertEquals(listOf(second.id), replacement.legends.map(RouteGeometryLegendUi::routeId))
        assertEquals(2, replacement.polylines.size)
        assertTrue(replacement.polylines.all { it.routeId == second.id })
        coordinator.clear()
    }

    @Test
    fun partialAndInvalidDirectionKeepTheOtherDirectionVisibleAndRetryOnlyTheMissingOne() = runCoordinatorTest {
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository)
        val selected = route(1)
        val snapshots = mutableListOf<RouteGeometrySnapshot>()

        coordinator.update(this, cityId, true, listOf(selected), true, snapshots::add)
        runCurrent()
        val outbound = repository.calls[0]
        val inbound = repository.calls[1]
        repository.complete(outbound, goodShape())
        repository.complete(inbound, malformedShape())
        runCurrent()

        val partial = snapshots.last()
        assertEquals(listOf(outbound.directionId), partial.polylines.map { it.directionId })
        assertEquals(RouteGeometryLegendState.Partial, partial.legends.single().state)
        assertEquals(1, partial.legends.single().successfulDirections)
        assertTrue(partial.legends.single().canRetry)

        coordinator.retry(this, selected.id)
        runCurrent()
        assertEquals(1, repository.calls.count { it == outbound })
        assertEquals(2, repository.calls.count { it == inbound })
        repository.complete(inbound, TransitLoadResult.Failure(TransitFailure.Transport("offline")))
        runCurrent()
        val stillPartial = snapshots.last()
        assertEquals(RouteGeometryLegendState.Partial, stillPartial.legends.single().state)
        assertEquals(listOf(outbound.directionId), stillPartial.polylines.map { it.directionId })
        coordinator.clear()
    }

    @Test
    fun routeGeometryCapabilityOffMakesAnUnavailableLegendWithoutShapeRequests() = runCoordinatorTest {
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository)
        val snapshot = coordinator.update(this, cityId, false, listOf(route(1)), true) { error("Disabled geometry must not publish") }

        runCurrent()
        assertTrue(repository.calls.isEmpty())
        assertTrue(snapshot.polylines.isEmpty())
        assertEquals(RouteGeometryLegendState.Unavailable, snapshot.legends.single().state)
        assertFalse(snapshot.legends.single().canRetry)
        coordinator.clear()
    }

    @Test
    fun staleCitySelectionCapabilityAndLifecycleResultsNeverPublish() = runCoordinatorTest {
        val repository = ControlledShapeRepository(nonCooperative = true)
        val coordinator = RouteGeometryCoordinator(repository)
        val first = route(1)
        val second = route(2)

        coordinator.update(this, cityId, true, listOf(first), true) { }
        runCurrent()
        val cityAKeys = repository.calls.toList()

        val cityChanged = coordinator.update(this, CityId("other-city"), true, listOf(second), true) { }
        runCurrent()
        val cityBKeys = repository.calls.filter { it.cityId == CityId("other-city") }
        cityAKeys.forEach { repository.complete(it, goodShape()) }
        runCurrent()
        assertTrue(cityChanged.polylines.none { it.routeId == first.id })
        assertEquals(listOf(second.id), cityChanged.legends.map(RouteGeometryLegendUi::routeId))

        // A committed selection replacement followed by a lifecycle/capability change removes
        // the old source before late, non-cooperative BFF completions are accepted.
        coordinator.update(this, CityId("other-city"), true, emptyList(), true) { }
        coordinator.update(this, CityId("other-city"), false, listOf(second), true) { }
        val disabled = coordinator.update(this, CityId("other-city"), false, listOf(second), false) { }
        runCurrent()
        cityBKeys.forEach { repository.complete(it, goodShape()) }
        runCurrent()
        assertTrue(disabled.polylines.isEmpty())
        assertEquals(RouteGeometryLegendState.Unavailable, disabled.legends.single().state)
        coordinator.clear()
    }

    @Test
    fun cooperativeBackgroundForegroundRestartsWhileNonCooperativeWorkNeverOverlapsItsReplacement() = runCoordinatorTest {
        val selected = route(1).copy(directions = route(1).directions.take(1))

        val cooperativeRepository = ControlledShapeRepository()
        val cooperative = RouteGeometryCoordinator(cooperativeRepository)
        cooperative.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        cooperative.update(this, cityId, true, listOf(selected), false) { }
        runCurrent()
        cooperative.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        assertEquals(2, cooperativeRepository.calls.size)
        cooperative.clear()

        val nonCooperativeRepository = ControlledShapeRepository(nonCooperative = true)
        val nonCooperative = RouteGeometryCoordinator(nonCooperativeRepository)
        nonCooperative.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        val key = nonCooperativeRepository.calls.single()
        nonCooperative.update(this, cityId, true, listOf(selected), false) { }
        nonCooperative.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        assertEquals(1, nonCooperativeRepository.calls.size)
        nonCooperativeRepository.complete(key, goodShape())
        runCurrent()
        assertEquals(2, nonCooperativeRepository.calls.size)
        assertEquals(1, nonCooperativeRepository.maximumConcurrentRequests)
        nonCooperativeRepository.complete(nonCooperativeRepository.calls.last(), goodShape())
        runCurrent()
        nonCooperative.clear()
    }

    @Test
    fun validMemoryCacheReusesForTwentyFourHoursAndExpiredOrFailedEntriesRefetch() = runCoordinatorTest {
        val clock = MutableClock()
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository, clock)
        val selected = route(1)

        coordinator.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        repository.calls.toList().forEach { repository.complete(it, goodShape()) }
        runCurrent()
        assertEquals(2, repository.calls.size)

        coordinator.update(this, cityId, true, emptyList(), true) { }
        coordinator.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        assertEquals(2, repository.calls.size, "A valid entry-local cache must serve re-selection")

        clock.nowMillis = CacheTtlMillis
        coordinator.update(this, cityId, true, emptyList(), true) { }
        coordinator.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        assertEquals(4, repository.calls.size, "Expired entries must be fetched again")
        coordinator.clear()

        val failures = ControlledShapeRepository()
        val noFailureCache = RouteGeometryCoordinator(failures)
        noFailureCache.update(this, cityId, true, listOf(selected), true) { }
        runCurrent()
        failures.calls.toList().forEach { failures.complete(it, TransitLoadResult.Failure(TransitFailure.Timeout())) }
        runCurrent()
        noFailureCache.retry(this, selected.id)
        runCurrent()
        assertEquals(4, failures.calls.size, "Failures must not enter the completed shape cache")
        noFailureCache.clear()
    }

    @Test
    fun continuouslyVisibleHardTtlRevalidatesOnceWhileKeepingReadyGeometryVisible() = runCoordinatorTest {
        val clock = MutableClock()
        val repository = ControlledShapeRepository()
        val coordinator = RouteGeometryCoordinator(repository, clock)
        val selected = route(1)
        val snapshots = mutableListOf<RouteGeometrySnapshot>()

        coordinator.update(this, cityId, true, listOf(selected), true, snapshots::add)
        runCurrent()
        repository.calls.toList().forEach { repository.complete(it, goodShape()) }
        runCurrent()
        assertEquals(2, snapshots.last().polylines.size)

        clock.nowMillis = CacheTtlMillis
        advanceTimeBy(CacheTtlMillis)
        runCurrent()
        assertEquals(4, repository.calls.size)
        assertEquals(2, snapshots.last().polylines.size, "Refresh must retain the previously Ready source")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(4, repository.calls.size, "One expiry creates exactly one revalidation per direction")
        coordinator.clear()
    }

    @Test
    fun providerAndFallbackColorsAreDeterministicDistinctAndMeetMapContrastForTenLines() = runCoordinatorTest {
        val routes = (1..10).map { index ->
            route(
                index = index,
                // The first is an accepted provider color; repeated valid and light invalid values
                // make the resolver exercise both collision fallback and contrast rejection.
                color = when (index) {
                    1, 2 -> 0xFF0057B8
                    3 -> 0xFFF5F5F5
                    else -> 0xFF0057B8
                },
            )
        }
        val first = RouteGeometryCoordinator(ControlledShapeRepository()).update(this, cityId, true, routes, false) { }
        val second = RouteGeometryCoordinator(ControlledShapeRepository()).update(this, cityId, true, routes, false) { }
        val colors = first.legends.map(RouteGeometryLegendUi::colorArgb)

        assertEquals(colors, second.legends.map(RouteGeometryLegendUi::colorArgb))
        assertEquals(10, colors.toSet().size)
        assertTrue(colors.all { contrastAgainstMap(it) >= 3.0 })
        colors.forEachIndexed { index, color ->
            colors.drop(index + 1).forEach { other ->
                assertTrue(
                    rgbDistanceSquared(color, other) >= MinimumRgbDistanceSquared,
                    "Every assigned pair must meet the renderer's RGB distinguishability floor",
                )
            }
        }
        assertTrue(first.legends.all { it.colorAvailability == RouteGeometryColorAvailability.Assigned })

        // #FF5A21B6 is an accessible provider color. #FF5B21B6 is also accessible, but only one
        // RGB unit away, so it must be rejected as a provider collision and probed past by the
        // fallback palette rather than emitting an ambiguous near-duplicate line.
        val nearCollision = listOf(
            route(index = 101, color = 0xFF5A21B6),
            route(index = 102, color = 0xFF5B21B6),
        )
        val collisionSnapshot = RouteGeometryCoordinator(ControlledShapeRepository()).update(
            this,
            cityId,
            true,
            nearCollision,
            false,
        ) { }
        val provider = collisionSnapshot.legends[0]
        val fallback = collisionSnapshot.legends[1]
        assertEquals(0xFF5A21B6, provider.colorArgb)
        assertNotEquals(0xFF5B21B6, fallback.colorArgb)
        assertEquals(RouteGeometryColorAvailability.Assigned, fallback.colorAvailability)
        assertTrue(rgbDistanceSquared(provider.colorArgb, fallback.colorArgb) >= MinimumRgbDistanceSquared)

        val overflow = RouteGeometryCoordinator(ControlledShapeRepository()).update(
            this,
            cityId,
            true,
            routes + route(11),
            false,
        ) { }
        assertEquals(RouteGeometryLegendState.PaletteOverflow, overflow.legends.last().state)
        assertEquals(RouteGeometryColorAvailability.PaletteOverflow, overflow.legends.last().colorAvailability)
    }

    private fun route(
        index: Int,
        city: CityId = cityId,
        color: Long = 0xFF0057B8,
    ): TransitRoute = TransitRoute(
        id = RouteId("opaque-route-$index"),
        cityId = city,
        shortName = "R$index",
        name = "Route $index",
        colorArgb = color,
        directions = listOf(
            TransitDirection(DirectionId("opaque-direction-$index-out"), labels("Outbound"), labels("Outbound")),
            TransitDirection(DirectionId("opaque-direction-$index-return"), labels("Return"), labels("Return")),
        ),
    )

    private fun labels(value: String) = LocalizedText(value, value, value)

    private fun goodShape(): TransitLoadResult.Data<TransitShape> = TransitLoadResult.Data(
        TransitShape("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5, Instant.fromEpochMilliseconds(0)),
        TransitFreshness.Network,
    )

    private fun malformedShape(): TransitLoadResult.Data<TransitShape> = TransitLoadResult.Data(
        TransitShape("_p~iF", 5, Instant.fromEpochMilliseconds(0)),
        TransitFreshness.Network,
    )

    /** Test-only encoder makes the malformed/oversized decoder cases readable without a provider fixture. */
    private fun encodePolyline(points: List<GeoPoint>, precision: Int): String = buildString {
        val scale = 10.0.pow(precision)
        var previousLatitude = 0L
        var previousLongitude = 0L
        points.forEach { point ->
            val latitude = (point.latitude * scale).toLong()
            val longitude = (point.longitude * scale).toLong()
            appendEncodedComponent(latitude - previousLatitude)
            appendEncodedComponent(longitude - previousLongitude)
            previousLatitude = latitude
            previousLongitude = longitude
        }
    }

    private fun StringBuilder.appendEncodedComponent(delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv().toULong() else (delta shl 1).toULong()
        while (value >= 0x20u) {
            append(((value and 0x1Fu).toInt() or 0x20).plus(63).toChar())
            value = value shr 5
        }
        append(value.toInt().plus(63).toChar())
    }

    private fun contrastAgainstMap(color: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        return (maxOf(luminance, MapBackgroundLuminance) + 0.05) / (minOf(luminance, MapBackgroundLuminance) + 0.05)
    }

    private fun rgbDistanceSquared(first: Long, second: Long): Long {
        val red = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val green = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return red * red + green * green + blue * blue
    }

    private class MutableClock(var nowMillis: Long = 0L) : RouteGeometryClock {
        override fun nowEpochMillis(): Long = nowMillis
    }

    private data class ShapeKey(val cityId: CityId, val routeId: RouteId, val directionId: DirectionId)

    private class ControlledShapeRepository(
        private val nonCooperative: Boolean = false,
    ) : TransitRepository {
        val calls = mutableListOf<ShapeKey>()
        var maximumConcurrentRequests = 0
            private set
        private var activeRequests = 0
        private val pending = mutableMapOf<ShapeKey, ArrayDeque<CompletableDeferred<TransitLoadResult<TransitShape>>>>()

        override fun cities() = emptyList<com.denis.georgiatransit.shared.domain.model.TransitCity>()

        override fun routes(cityId: CityId) = emptyList<TransitRoute>()

        override suspend fun directionShape(
            cityId: CityId,
            routeId: RouteId,
            directionId: DirectionId,
        ): TransitLoadResult<TransitShape> {
            val key = ShapeKey(cityId, routeId, directionId)
            val reply = CompletableDeferred<TransitLoadResult<TransitShape>>()
            calls += key
            pending.getOrPut(key, ::ArrayDeque).addLast(reply)
            activeRequests++
            maximumConcurrentRequests = maxOf(maximumConcurrentRequests, activeRequests)
            return try {
                if (nonCooperative) withContext(NonCancellable) { reply.await() } else reply.await()
            } finally {
                activeRequests--
            }
        }

        fun complete(key: ShapeKey, result: TransitLoadResult<TransitShape>) {
            val reply = pending[key]?.removeFirstOrNull()
                ?: error("No pending shape request for $key")
            check(reply.complete(result)) { "Shape request for $key was already completed" }
        }
    }

    private companion object {
        val cityId = CityId("test-city")
        const val CacheTtlMillis = 24L * 60L * 60L * 1_000L
        const val MapBackgroundLuminance = 0.8589768
        const val MinimumRgbDistanceSquared = 2_500L
    }
}

private fun runCoordinatorTest(block: suspend TestScope.() -> Unit) = runTest { block() }
