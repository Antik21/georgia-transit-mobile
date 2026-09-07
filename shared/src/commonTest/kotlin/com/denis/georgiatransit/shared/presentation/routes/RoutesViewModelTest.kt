package com.denis.georgiatransit.shared.presentation.routes

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun backEmitsBackToMapWithoutChangingRouteSelection() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val selectedIds = repository.routes(tbilisi.id).take(2).mapTo(linkedSetOf()) { it.id }
        val expectedRoutes = repository.routes(tbilisi.id).map { route ->
            RouteItemUiModel(route.id, route.shortName, route.name, route.colorArgb)
        }
        session.selectCity(tbilisi)
        session.selectRoutes(selectedIds)
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    routes = expectedRoutes,
                    selectedIds = selectedIds,
                ),
            )
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    routes = expectedRoutes,
                    selectedIds = selectedIds,
                    catalog = CatalogState.Available(TransitFreshness.CacheValid),
                ),
            )

            viewModel.dispatchAction(Action.BackClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.BackToMap)
            assertEquals(selectedIds, session.selectedRouteIds.value)
            assertEquals(selectedIds, viewModel.container.stateFlow.value.selectedIds)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun staleAvailableDataKeepsSelectionSafeAndOnlyAllowsKnownRouteSelection() = runTest {
        val city = city()
        val routes = routes(city.id)
        val repository = ResultsRepository(
            cached = routes,
            results = ArrayDeque(
                listOf(
                    TransitLoadResult.Data(routes, TransitFreshness.StaleOffline, TransitFailure.Transport("offline")),
                ),
            ),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cityName = city.name, routes = routeItems(routes)))
            expectState(
                ViewState(
                    cityName = city.name,
                    routes = routeItems(routes),
                    catalog = CatalogState.Available(TransitFreshness.StaleOffline),
                ),
            )

            viewModel.dispatchAction(Action.RouteToggled(routes.first().id))
            this@runTest.runCurrent()
            expectState {
                copy(selectedIds = setOf(routes.first().id))
            }
            viewModel.dispatchAction(Action.RouteToggled(RouteId("demo:fixture:route:unknown")))
            this@runTest.runCurrent()
            expectNoItems()
            assertEquals(setOf(routes.first().id), viewModel.container.stateFlow.value.selectedIds)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun emptyCatalogClearsRoutesAndExposesFreshness() = runTest {
        val city = city()
        val routes = routes(city.id)
        val emptyRepository = ResultsRepository(cached = emptyList(), results = ArrayDeque(listOf(TransitLoadResult.Empty(TransitFreshness.Network))))
        val emptySession = RuntimeTransitSession().also { it.selectCity(city) }
        RoutesViewModel(emptyRepository, emptySession).test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cityName = city.name))
            expectState(ViewState(cityName = city.name, catalog = CatalogState.Empty(TransitFreshness.Network)))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryableFailureKeepsCachedRoutesAndRetryReturnsToAvailable() = runTest {
        val city = city()
        val routes = routes(city.id)
        val repository = ResultsRepository(
            cached = routes,
            results = ArrayDeque(
                listOf(
                    TransitLoadResult.Failure(TransitFailure.Transport("offline")),
                    TransitLoadResult.Data(routes, TransitFreshness.Network),
                ),
            ),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cityName = city.name, routes = routeItems(routes)))
            expectState {
                copy(catalog = CatalogState.Error(TransitFailure.Transport("offline"), canRetry = true))
            }

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            expectState { copy(catalog = CatalogState.Loading) }
            expectState(
                ViewState(
                    cityName = city.name,
                    routes = routeItems(routes),
                    catalog = CatalogState.Available(TransitFreshness.Network),
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshedCatalogRemovesMissingSelectedRoutesBeforeConfirmPersistsSelection() = runTest {
        val city = city()
        val retained = routes(city.id).single()
        val removed = TransitRoute(
            id = RouteId("demo:fixture:route:removed"),
            cityId = city.id,
            shortName = "Old",
            name = "Removed route",
            colorArgb = 0xFF666666,
        )
        val cached = listOf(retained, removed)
        val selected = linkedSetOf(retained.id, removed.id)
        val repository = ResultsRepository(
            cached = cached,
            results = ArrayDeque(listOf(TransitLoadResult.Data(listOf(retained), TransitFreshness.Network))),
        )
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            it.selectRoutes(selected)
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cityName = city.name, routes = routeItems(cached), selectedIds = selected))
            expectState(
                ViewState(
                    cityName = city.name,
                    routes = routeItems(listOf(retained)),
                    selectedIds = setOf(retained.id),
                    catalog = CatalogState.Available(TransitFreshness.Network),
                ),
            )

            viewModel.dispatchAction(Action.ConfirmClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.BackToMap)
            assertEquals(setOf(retained.id), session.selectedRouteIds.value)
            assertEquals(setOf(retained.id), viewModel.container.stateFlow.value.selectedIds)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun noSelectedCityIsNonRetryable() = runTest {
        val noCity = RoutesViewModel(ResultsRepository(emptyList(), ArrayDeque()), RuntimeTransitSession())
        noCity.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            assertEquals(
                CatalogState.Error(
                    TransitFailure.Configuration("A selected city is required to load routes"),
                    canRetry = false,
                ),
                noCity.container.stateFlow.value.catalog,
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun city() = TransitCity(
        id = CityId("demo"),
        name = "Demo",
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(true, true, true, true, true),
    )

    private fun routes(cityId: CityId) = listOf(
        TransitRoute(
            RouteId("demo:fixture:route:blue"),
            cityId,
            "D1",
            "Blue",
            0xFF0057B8,
        ),
    )

    private fun routeItems(routes: List<TransitRoute>) = routes.map {
        RouteItemUiModel(it.id, it.shortName, it.name, it.colorArgb)
    }

    private class ResultsRepository(
        private val cached: List<TransitRoute>,
        private val results: ArrayDeque<TransitLoadResult<List<TransitRoute>>>,
    ) : TransitRepository {
        override fun cities(): List<TransitCity> = emptyList()
        override fun routes(cityId: CityId): List<TransitRoute> = cached
        override suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> =
            results.removeFirstOrNull() ?: TransitLoadResult.Data(cached, TransitFreshness.CacheValid)
    }

}
