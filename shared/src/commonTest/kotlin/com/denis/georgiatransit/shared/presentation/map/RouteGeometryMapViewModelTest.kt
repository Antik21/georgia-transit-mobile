package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.model.TransitShape
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
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Session mutation coverage for the route legend's Remove action; no native adapter is involved. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteGeometryMapViewModelTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun removeGeometryAtomicallyReplacesSessionSelectionAndDropsEveryRemovedRouteLineAndLegend() = runMapTest {
        val city = TransitCity(
            id = CityId("geometry-city"),
            name = "Geometry City",
            center = GeoPoint(41.7151, 44.8271),
            defaultZoom = 13.0,
            capabilities = CityCapabilities(
                stops = false,
                vehicles = false,
                arrivals = false,
                routeShapes = true,
                journeyPlanning = false,
            ),
        )
        val first = route(city.id, "first")
        val second = route(city.id, "second")
        val repository = GeometryRepository(listOf(first, second))
        val session = RuntimeTransitSession().also {
            it.restoreCitySelection(city, linkedSetOf(first.id, second.id))
        }
        val coordinator = RouteGeometryCoordinator(repository)
        val viewModel = MapViewModel(
            repository = repository,
            session = session,
            locationSession = RuntimeLocationSession(scope = this),
            routeGeometryCoordinator = coordinator,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runMapTest.runCurrent()
            viewModel.dispatchAction(Action.RealtimeVisibilityChanged(true))
            this@runMapTest.runCurrent()
            repository.completeAll()
            this@runMapTest.runCurrent()
            assertEquals(4, requireNotNull(viewModel.container.stateFlow.value.renderState).polylines.size)
            assertEquals(listOf(first.id, second.id), viewModel.container.stateFlow.value.routeGeometryLegends.map(RouteGeometryLegendUi::routeId))

            viewModel.dispatchAction(Action.RemoveRouteGeometry(first.id))
            this@runMapTest.runCurrent()

            assertEquals(setOf(second.id), session.selectedRouteIds.value)
            val afterRemove = viewModel.container.stateFlow.value
            assertEquals(listOf(second.id), afterRemove.routeGeometryLegends.map(RouteGeometryLegendUi::routeId))
            assertEquals(2, requireNotNull(afterRemove.renderState).polylines.size)
            assertEquals(setOf(second.id), afterRemove.renderState.polylines.map(MapPolyline::routeId).toSet())
            coordinator.clear()
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun route(cityId: CityId, suffix: String): TransitRoute = TransitRoute(
        id = RouteId("opaque-route-$suffix"),
        cityId = cityId,
        shortName = suffix,
        name = "Route $suffix",
        colorArgb = 0xFF0057B8,
        directions = listOf(
            TransitDirection(DirectionId("opaque-direction-$suffix-out"), label("Out"), label("Out")),
            TransitDirection(DirectionId("opaque-direction-$suffix-return"), label("Return"), label("Return")),
        ),
    )

    private fun label(value: String) = LocalizedText(value, value, value)

    private class GeometryRepository(
        private val routeList: List<TransitRoute>,
    ) : TransitRepository {
        private data class ShapeRequest(val routeId: RouteId, val directionId: DirectionId)

        private val pending = mutableMapOf<ShapeRequest, CompletableDeferred<TransitLoadResult<TransitShape>>>()

        override fun cities() = emptyList<TransitCity>()

        override fun routes(cityId: CityId): List<TransitRoute> = routeList.filter { it.cityId == cityId }

        override suspend fun directionShape(
            cityId: CityId,
            routeId: RouteId,
            directionId: DirectionId,
        ): TransitLoadResult<TransitShape> = pending.getOrPut(ShapeRequest(routeId, directionId), ::CompletableDeferred).await()

        fun completeAll() {
            pending.values.forEach { reply ->
                reply.complete(
                    TransitLoadResult.Data(
                        TransitShape("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5, Instant.fromEpochMilliseconds(0)),
                        TransitFreshness.Network,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun runMapTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    block()
}
