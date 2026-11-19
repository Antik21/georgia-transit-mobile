package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.ProviderId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
import com.denis.georgiatransit.shared.domain.model.TransitStop
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapRouteFramingTest {
    private val kutaisi = TransitCity(
        id = CityId("kutaisi"),
        name = "Kutaisi",
        center = GeoPoint(42.271608, 42.705448),
        capabilities = CityCapabilities(stops = true, vehicles = false, arrivals = false, routeShapes = true, journeyPlanning = false),
    )
    private val outbound = DirectionId("out")
    private val inbound = DirectionId("in")
    private val stopPoint = GeoPoint(42.29, 42.59)
    private val linePoints = mapOf(
        outbound to listOf(GeoPoint(42.20, 42.61), GeoPoint(42.26, 42.72)),
        inbound to listOf(GeoPoint(42.26, 42.71), GeoPoint(42.21, 42.62)),
    )
    private val expectedBounds = MapRouteBounds(GeoPoint(42.20, 42.59), GeoPoint(42.29, 42.72))

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun waitsForBothDirectionsAndStopsWhileMapIsVisibleThenFramesOnlyOnce() = runTest {
        withFrameFixture {
            showMap(false)
            select()
            complete(outbound)
            complete(inbound)
            assertNull(camera().routeBounds)
            showMap(true)
            assertNull(camera().routeBounds)
            completeStops(outbound)
            assertNull(camera().routeBounds)
            completeStops(inbound)
            val framed = camera()
            assertEquals(expectedBounds, framed.routeBounds)
            viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.Georgian))
            scope.runCurrent()
            assertEquals(framed, camera())
        }
    }

    @Test
    fun deselectionAndManualCameraMovementCancelAWaitingFit() = runTest {
        withFrameFixture {
            select()
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.UserCameraMoveStarted))
            scope.runCurrent()
            complete(outbound)
            complete(inbound)
            completeStops(outbound)
            completeStops(inbound)
            assertNull(camera().routeBounds)

            viewModel.dispatchAction(Action.RemoveRouteGeometry(route.id))
            scope.runCurrent()
            select()
            assertEquals(expectedBounds, camera().routeBounds)
            val framedRevision = camera().revision
            viewModel.dispatchAction(Action.RemoveRouteGeometry(route.id))
            scope.runCurrent()
            assertNull(camera().routeBounds)
            assertEquals(framedRevision, camera().revision)
        }
    }

    @Test
    fun keepsBatumiOnItsOriginalCamera() = runTest {
        withFrameFixture(city = kutaisi.copy(id = CityId("batumi"))) {
            val before = camera()
            select()
            complete(outbound)
            complete(inbound)
            completeStops(outbound)
            completeStops(inbound)
            assertEquals(before, camera())
        }
    }

    @Test
    fun keepsRenderersWithoutBoundsSupportOnTheirOriginalCamera() = runTest {
        withFrameFixture(supportsBounds = false) {
            val before = camera()
            select()
            complete(outbound)
            complete(inbound)
            completeStops(outbound)
            completeStops(inbound)
            assertEquals(before, camera())
        }
    }

    private suspend fun TestScope.withFrameFixture(
        city: TransitCity = kutaisi,
        supportsBounds: Boolean = true,
        exercise: suspend FrameFixture.() -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = FrameFixture(this, city, supportsBounds)
        fixture.viewModel.test(this) {
            runOnCreate()
            runCurrent()
            fixture.showMap(true)
            fixture.exercise()
            fixture.showMap(false)
            cancelAndIgnoreRemainingItems()
        }
    }

    private inner class FrameFixture(val scope: TestScope, val city: TransitCity, val supportsBounds: Boolean) {
        val route = TransitRoute(
            id = RouteId("route-25"),
            cityId = city.id,
            shortName = "25",
            name = "Route 25",
            colorArgb = 0xFF0057B8,
            directions = listOf(outbound, inbound).map { id ->
                TransitDirection(id, LocalizedText.fromLegacy(id.value), LocalizedText.fromLegacy(id.value))
            },
        )
        private val shapes = linePoints.mapValues { CompletableDeferred<TransitLoadResult<TransitShape>>() }
        private val stops = linePoints.mapValues { CompletableDeferred<TransitLoadResult<List<TransitStop>>>() }
        private val repository = object : TransitRepository {
            override fun cities() = listOf(city)
            override fun routes(cityId: CityId) = listOf(route)
            override suspend fun directionShape(cityId: CityId, routeId: RouteId, directionId: DirectionId) =
                shapes.getValue(directionId).await()
            override suspend fun directionStops(cityId: CityId, routeId: RouteId, directionId: DirectionId, locale: TransitLocale) =
                stops.getValue(directionId).await()
        }
        private val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = MapViewModel(repository, session, RuntimeLocationSession(scope))

        fun showMap(visible: Boolean) {
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visible, routeBoundsSupported = supportsBounds))
            scope.runCurrent()
        }

        fun select() {
            session.selectRoutes(city.id, setOf(route.id))
            scope.runCurrent()
        }

        fun complete(id: DirectionId) {
            shapes.getValue(id).complete(TransitLoadResult.Data(
                TransitShape(encode(linePoints.getValue(id)), 5, Instant.fromEpochMilliseconds(0)),
                TransitFreshness.Network,
            ))
            scope.runCurrent()
        }

        fun completeStops(id: DirectionId) {
            val stop = TransitStop(
                id = StopId("stop-${id.value}"),
                providerId = ProviderId("test"),
                code = "1",
                name = LocalizedText.fromLegacy("Stop"),
                position = stopPoint,
                routeIds = listOf(route.id),
                mode = TransitMode.Bus,
            )
            stops.getValue(id).complete(TransitLoadResult.Data(listOf(stop), TransitFreshness.Network))
            scope.runCurrent()
        }

        fun camera(): MapCameraCommand = assertNotNull(viewModel.container.stateFlow.value.renderState).camera
    }

    private fun encode(points: List<GeoPoint>): String = buildString {
        var latitude = 0
        var longitude = 0
        fun appendDelta(delta: Int) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 0x20) {
                append(((0x20 or (value and 0x1f)) + 63).toChar())
                value = value shr 5
            }
            append((value + 63).toChar())
        }
        for (point in points) {
            val nextLatitude = kotlin.math.round(point.latitude * 100_000).toInt()
            val nextLongitude = kotlin.math.round(point.longitude * 100_000).toInt()
            appendDelta(nextLatitude - latitude)
            appendDelta(nextLongitude - longitude)
            latitude = nextLatitude
            longitude = nextLongitude
        }
    }
}
