package com.denis.georgiatransit.shared.presentation.routes

import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.model.CityCapabilities
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelTest {
    @Test
    fun authoritativeCatalogUsesNaturalShortNameOrderAndReconcilesOnlyMissingSelections() = runTest {
        val city = city()
        val route2 = route(city.id, "provider:route:z", "2", "Two")
        val route10 = route(city.id, "provider:route:y", "10", "Ten")
        val routeA1 = route(city.id, "provider:route:x", "a1", "A one")
        val routeA2 = route(city.id, "provider:route:w", "A2", "A two")
        val routeA10 = route(city.id, "provider:route:v", "A10", "A ten")
        val removed = RouteId("provider:route:removed")
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(
                    RefreshResponse.Result(
                        TransitLoadResult.Data(
                            listOf(routeA10, route2, routeA2, route10, routeA1),
                            TransitFreshness.Network,
                        ),
                    ),
                ),
            ),
        )
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            it.selectRoutes(city.id, linkedSetOf(route10.id, removed))
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            val state = viewModel.container.stateFlow.value
            assertEquals(listOf("2", "10", "a1", "A2", "A10"), state.routes.map(RouteItemUiModel::shortName))
            assertEquals(setOf(route10.id), state.selectedIds)
            assertEquals(listOf(route10.id), session.selectedRouteIds.value.toList())
            assertEquals(1, repository.refreshRequests.size)
            assertEquals(RouteListRequest(city.id, mode = null), repository.refreshRequests.single())
            assertEquals(0, repository.snapshotCalls, "The route screen must render only the refreshed authoritative catalogue.")
            assertIs<CatalogState.Available>(state.catalog)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cancelEmitsDismissalWithoutMutatingTheCurrentAuthoritativeSelection() = runTest {
        val city = city()
        val route = route(city.id, "provider:route:1", "1", "Route one")
        val repository = CountingRepository(
            responses = ArrayDeque(listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(route), TransitFreshness.Network)))),
        )
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            it.selectRoutes(city.id, linkedSetOf(route.id))
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            viewModel.dispatchAction(Action.CancelClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.Dismissed)
            assertEquals(listOf(route.id), session.selectedRouteIds.value.toList())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun visuallyEqualShortNameTiesPreserveAuthoritativeOrderInsteadOfUsingOpaqueIds() = runTest {
        val city = city()
        val firstFromCatalog = route(city.id, "provider:route:z", "7", "Same")
        val secondFromCatalog = route(city.id, "provider:route:a", "7", "Same")
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(
                    RefreshResponse.Result(
                        TransitLoadResult.Data(
                            listOf(firstFromCatalog, secondFromCatalog),
                            TransitFreshness.Network,
                        ),
                    ),
                ),
            ),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            assertEquals(
                listOf(firstFromCatalog.id, secondFromCatalog.id),
                viewModel.container.stateFlow.value.routes.map(RouteItemUiModel::id),
                "Equivalent public labels keep the BFF catalogue order; opaque identifiers are not a sort fallback.",
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun localSearchDoesNotFetchAndPreservesTheAuthoritativeSelectionOrder() = runTest {
        val city = city()
        val airport = route(city.id, "provider:route:airport", "10", "Airport Express")
        val station = route(city.id, "provider:route:station", "2", "Central Station")
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(airport, station), TransitFreshness.Network))),
            ),
        )
        val selection = linkedSetOf(airport.id, station.id)
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            it.selectRoutes(city.id, selection)
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()
            val initial = viewModel.container.stateFlow.value
            assertEquals(listOf("2", "10"), initial.routes.map(RouteItemUiModel::shortName))
            assertEquals(selection.toList(), initial.selectedIds.toList())

            viewModel.dispatchAction(Action.SearchChanged("airport"))
            this@runTest.runCurrent()

            val filtered = viewModel.container.stateFlow.value
            assertEquals(listOf(airport.id), filtered.visibleRoutes.map(RouteItemUiModel::id))
            assertEquals(initial.routes, filtered.routes, "Search is filtering only; it must not replace the catalogue.")
            assertEquals(selection.toList(), filtered.selectedIds.toList())
            assertEquals(selection.toList(), session.selectedRouteIds.value.toList())
            assertEquals(1, repository.refreshRequests.size, "Typing into local search must not start a repository request.")
            assertEquals(0, repository.snapshotCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun localeChangesRelabelCityRouteAndHeadsignWithFallbackWithoutFetchingAgain() = runTest {
        val city = city(
            name = "Legacy city",
            localizedName = LocalizedText(ru = "Город", en = "City", ka = "ქალაქი"),
        )
        val route = route(
            cityId = city.id,
            rawId = "provider:route:metro",
            shortName = "M1",
            name = "Legacy route",
            longName = LocalizedText(ru = "Русский маршрут", en = "English route", ka = ""),
            directions = listOf(
                TransitDirection(
                    id = DirectionId("provider:direction:1"),
                    name = LocalizedText(ru = "Русское имя", en = "English fallback", ka = "ქართული სახელი"),
                    headsign = LocalizedText(ru = "Русское направление", en = "", ka = "ქართული მიმართულება"),
                ),
            ),
            mode = TransitMode.Metro,
        )
        val repository = CountingRepository(
            responses = ArrayDeque(listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(route), TransitFreshness.Network)))),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()
            assertRouteLabels(viewModel, city = "City", name = "English route", direction = "Русское направление")

            viewModel.dispatchAction(Action.LocaleChanged("ru"))
            this@runTest.runCurrent()
            assertRouteLabels(viewModel, city = "Город", name = "Русский маршрут", direction = "Русское направление")

            viewModel.dispatchAction(Action.LocaleChanged("ka"))
            this@runTest.runCurrent()
            assertRouteLabels(viewModel, city = "ქალაქი", name = "English route", direction = "ქართული მიმართულება")

            viewModel.dispatchAction(Action.LocaleChanged("en"))
            this@runTest.runCurrent()
            assertRouteLabels(viewModel, city = "City", name = "English route", direction = "Русское направление")
            assertEquals(1, repository.refreshRequests.size, "Locale changes remap the in-memory catalogue only.")
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun noSelectedCityFailsClosedWithoutTouchingTheRepository() = runTest {
        val repository = CountingRepository()
        val viewModel = RoutesViewModel(repository, RuntimeTransitSession())

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()

            val state = viewModel.container.stateFlow.value
            assertEquals(CatalogState.Unavailable, state.catalog)
            assertTrue(state.routes.isEmpty())
            assertTrue(state.selectedIds.isEmpty())
            assertEquals(0, repository.refreshRequests.size)
            assertEquals(0, repository.snapshotCalls)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun routesDisabledForCityFailsClosedClearsSelectionAndDoesNotFetch() = runTest {
        val city = city(routesAvailable = false)
        val selected = RouteId("provider:route:selected")
        val repository = CountingRepository()
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            it.selectRoutes(city.id, setOf(selected))
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()

            val state = viewModel.container.stateFlow.value
            assertEquals(CatalogState.Unavailable, state.catalog)
            assertEquals(emptySet(), state.selectedIds)
            assertEquals(emptySet(), session.selectedRouteIds.value)
            assertEquals(0, repository.refreshRequests.size)
            assertEquals(0, repository.snapshotCalls)

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            assertEquals(0, repository.refreshRequests.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun loadingAndEmptyCatalogStatesRemainExplicit() = runTest {
        val city = city()
        val gate = CompletableDeferred<TransitLoadResult<List<TransitRoute>>>()
        val repository = CountingRepository(
            responses = ArrayDeque(listOf(RefreshResponse.Await(gate))),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(CatalogState.Loading, viewModel.container.stateFlow.value.catalog)

            gate.complete(TransitLoadResult.Empty(TransitFreshness.Network))
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(CatalogState.Empty(TransitFreshness.Network), viewModel.container.stateFlow.value.catalog)
            assertTrue(viewModel.container.stateFlow.value.routes.isEmpty())
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun thrownRefreshExceptionShowsOfflineNoCacheAndRetryRefreshesAuthoritativeCatalog() = runTest {
        val city = city()
        val route = route(city.id, "provider:route:1", "1", "Route one")
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(
                    RefreshResponse.Throw,
                    RefreshResponse.Result(TransitLoadResult.Data(listOf(route), TransitFreshness.Network)),
                ),
            ),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()
            assertEquals(
                CatalogState.OfflineNoCache(
                    TransitFailure.Transport("Route catalogue request failed"),
                    canRetry = true,
                ),
                viewModel.container.stateFlow.value.catalog,
            )
            assertTrue(viewModel.container.stateFlow.value.routes.isEmpty())

            viewModel.dispatchAction(Action.RetryClicked)
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            val state = viewModel.container.stateFlow.value
            assertEquals(CatalogState.Available(TransitFreshness.Network), state.catalog)
            assertEquals(listOf(route.id), state.routes.map(RouteItemUiModel::id))
            assertEquals(2, repository.refreshRequests.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun staleOfflineCatalogRemainsAvailableWithoutDuplicatingCachePolicyTests() = runTest {
        val city = city()
        val route = route(city.id, "provider:route:stale", "1", "Route one")
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(
                    RefreshResponse.Result(
                        TransitLoadResult.Data(
                            listOf(route),
                            TransitFreshness.StaleOffline,
                            TransitFailure.Transport("offline"),
                        ),
                    ),
                ),
            ),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            val state = viewModel.container.stateFlow.value
            assertEquals(CatalogState.Available(TransitFreshness.StaleOffline), state.catalog)
            assertEquals(listOf(route.id), state.visibleRoutes.map(RouteItemUiModel::id))
            assertEquals(1, repository.refreshRequests.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun refreshCancellationIsRethrownInsteadOfBecomingAnOfflineCatalogError() = runTest {
        val city = city()
        val repository = CountingRepository(
            responses = ArrayDeque(listOf(RefreshResponse.Cancel)),
        )
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            repository.refreshStarted.await()
            repository.cancellationThrown.await()
            val refreshJob = repository.refreshCallerJob.await()
            refreshJob.join()
            this@runTest.runCurrent()

            assertEquals(CatalogState.Loading, viewModel.container.stateFlow.value.catalog)
            assertEquals(1, repository.refreshRequests.size)
            assertEquals(1, repository.cancellationCount)
            assertTrue(
                refreshJob.isCancelled,
                "The refresh child must complete cancelled; swallowing CancellationException would complete it normally.",
            )
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun cityChangeDuringRefreshIgnoresOldResultAndClearsThePreviousCitySelection() = runTest {
        val firstCity = city(id = "first", name = "First")
        val secondCity = city(id = "second", name = "Second")
        val firstRoute = route(firstCity.id, "provider:first:route", "1", "First route")
        val secondRoute = route(secondCity.id, "provider:second:route", "2", "Second route")
        val firstGate = CompletableDeferred<TransitLoadResult<List<TransitRoute>>>()
        val secondGate = CompletableDeferred<TransitLoadResult<List<TransitRoute>>>()
        val repository = CountingRepository(
            responses = ArrayDeque(
                listOf(
                    RefreshResponse.AwaitIgnoringCancellation(firstGate),
                    RefreshResponse.Await(secondGate),
                ),
            ),
        )
        val session = RuntimeTransitSession().also {
            it.selectCity(firstCity)
            it.selectRoutes(firstCity.id, linkedSetOf(firstRoute.id))
        }
        val viewModel = RoutesViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(CatalogState.Loading, viewModel.container.stateFlow.value.catalog)

            session.selectCity(secondCity)
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(secondCity.id, viewModel.container.stateFlow.value.cityId)
            assertEquals(CatalogState.Loading, viewModel.container.stateFlow.value.catalog)
            assertEquals(emptySet(), session.selectedRouteIds.value)

            firstGate.complete(TransitLoadResult.Data(listOf(firstRoute), TransitFreshness.Network))
            this@runTest.runCurrent()
            assertEquals(secondCity.id, viewModel.container.stateFlow.value.cityId)
            assertTrue(viewModel.container.stateFlow.value.routes.isEmpty(), "A cancelled first-city result must be ignored.")
            expectNoItems()

            secondGate.complete(TransitLoadResult.Data(listOf(secondRoute), TransitFreshness.Network))
            this@runTest.runCurrent()
            awaitItem()
            val state = viewModel.container.stateFlow.value
            assertEquals(secondCity.id, state.cityId)
            assertEquals(listOf(secondRoute.id), state.routes.map(RouteItemUiModel::id))
            assertEquals(emptySet(), state.selectedIds)
            assertEquals(2, repository.refreshRequests.size)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun draftTogglesStayLocalAcrossSessionEchoAndCancelDiscardsOnlyTheDraft() = runTest {
        val city = city()
        val routeA = route(city.id, "opaque:a", "1", "Route A")
        val routeB = route(city.id, "opaque:b", "2", "Route B")
        val routeC = route(city.id, "opaque:c", "3", "Route C")
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            assertTrue(it.selectRoutes(city.id, setOf(routeA.id)))
        }
        val viewModel = RoutesViewModel(
            CountingRepository(
                responses = ArrayDeque(
                    listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(routeA, routeB, routeC), TransitFreshness.Network))),
                ),
            ),
            session,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()
            assertEquals(setOf(routeA.id), viewModel.container.stateFlow.value.selectedIds)

            viewModel.dispatchAction(Action.RouteToggled(routeB.id))
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(setOf(routeA.id, routeB.id), viewModel.container.stateFlow.value.selectedIds)
            assertEquals(setOf(routeA.id), session.selectedRouteIds.value, "Draft changes must not restart Map work.")

            assertTrue(session.selectRoutes(city.id, setOf(routeA.id, routeC.id)))
            this@runTest.runCurrent()
            assertEquals(
                setOf(routeA.id, routeB.id),
                viewModel.container.stateFlow.value.selectedIds,
                "An ordinary committed-session echo cannot replace the entry draft.",
            )

            viewModel.dispatchAction(Action.CancelClicked)
            this@runTest.runCurrent()
            expectSideEffect(NavigationEffect.Dismissed)
            assertEquals(setOf(routeA.id, routeC.id), session.selectedRouteIds.value)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun confirmCommitsExactReplacementOnceAndDoubleTapEmitsOnce() = runTest {
        val city = city()
        val routeA = route(city.id, "opaque:a", "1", "Route A")
        val routeB = route(city.id, "opaque:b", "2", "Route B")
        val session = RuntimeTransitSession().also {
            it.selectCity(city)
            assertTrue(it.selectRoutes(city.id, setOf(routeA.id)))
        }
        val viewModel = RoutesViewModel(
            CountingRepository(
                responses = ArrayDeque(
                    listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(routeA, routeB), TransitFreshness.Network))),
                ),
            ),
            session,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            viewModel.dispatchAction(Action.RouteToggled(routeA.id))
            this@runTest.runCurrent()
            awaitItem()
            viewModel.dispatchAction(Action.RouteToggled(routeB.id))
            this@runTest.runCurrent()
            awaitItem()
            assertEquals(setOf(routeB.id), viewModel.container.stateFlow.value.selectedIds)
            assertEquals(setOf(routeA.id), session.selectedRouteIds.value)

            viewModel.dispatchAction(Action.ConfirmClicked)
            viewModel.dispatchAction(Action.ConfirmClicked)
            this@runTest.runCurrent()

            awaitItem()
            assertTrue(viewModel.container.stateFlow.value.isConfirming)
            assertEquals(setOf(routeB.id), session.selectedRouteIds.value)
            expectSideEffect(NavigationEffect.Confirmed)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun emptyConfirmationAtomicallyClearsTheCommittedSet() = runTest {
        val city = city()
        val routeA = route(city.id, "opaque:a", "1", "Route A")
        val routeB = route(city.id, "opaque:b", "2", "Route B")
        val emptySession = RuntimeTransitSession().also {
            it.selectCity(city)
            assertTrue(it.selectRoutes(city.id, setOf(routeA.id)))
        }
        val emptyViewModel = RoutesViewModel(
            CountingRepository(
                responses = ArrayDeque(
                    listOf(RefreshResponse.Result(TransitLoadResult.Data(listOf(routeA, routeB), TransitFreshness.Network))),
                ),
            ),
            emptySession,
        )
        emptyViewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            emptyViewModel.dispatchAction(Action.RouteToggled(routeA.id))
            this@runTest.runCurrent()
            awaitItem()
            emptyViewModel.dispatchAction(Action.ConfirmClicked)
            this@runTest.runCurrent()

            awaitItem()
            assertTrue(emptySession.selectedRouteIds.value.isEmpty())
            expectSideEffect(NavigationEffect.Confirmed)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun selectionCapBlocksTheEleventhUnselectedRouteWhileDeselectionFreesASlot() = runTest {
        val city = city()
        val routes = (0..RouteSelectionPolicy.MaximumSelectedRoutes).map { index ->
            route(city.id, "opaque:$index", index.toString(), "Route $index")
        }
        val session = RuntimeTransitSession().also { it.selectCity(city) }
        val viewModel = RoutesViewModel(
            CountingRepository(
                responses = ArrayDeque(
                    listOf(RefreshResponse.Result(TransitLoadResult.Data(routes, TransitFreshness.Network))),
                ),
            ),
            session,
        )

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            awaitItem()
            awaitItem()

            routes.take(RouteSelectionPolicy.MaximumSelectedRoutes).forEach { route ->
                viewModel.dispatchAction(Action.RouteToggled(route.id))
            }
            this@runTest.runCurrent()
            assertEquals(RouteSelectionPolicy.MaximumSelectedRoutes, viewModel.container.stateFlow.value.selectedIds.size)
            assertTrue(viewModel.container.stateFlow.value.isSelectionLimitReached)
            assertTrue(session.selectedRouteIds.value.isEmpty())

            val blocked = routes.last()
            viewModel.dispatchAction(Action.RouteToggled(blocked.id))
            this@runTest.runCurrent()
            assertFalse(blocked.id in viewModel.container.stateFlow.value.selectedIds)

            val removed = routes.first()
            viewModel.dispatchAction(Action.RouteToggled(removed.id))
            viewModel.dispatchAction(Action.RouteToggled(blocked.id))
            this@runTest.runCurrent()
            assertEquals(RouteSelectionPolicy.MaximumSelectedRoutes, viewModel.container.stateFlow.value.selectedIds.size)
            assertFalse(removed.id in viewModel.container.stateFlow.value.selectedIds)
            assertTrue(blocked.id in viewModel.container.stateFlow.value.selectedIds)
            cancelAndIgnoreRemainingItems()
        }
    }

    private fun assertRouteLabels(
        viewModel: RoutesViewModel,
        city: String,
        name: String,
        direction: String,
    ) {
        val state = viewModel.container.stateFlow.value
        val item = state.routes.single()
        assertEquals(city, state.cityName)
        assertEquals(name, item.name)
        assertEquals(direction, item.direction)
        assertEquals(TransitMode.Metro, item.mode, "The screen localizes the mode label from this stable enum.")
    }

    private fun city(
        id: String = "demo",
        routesAvailable: Boolean = true,
        name: String = "Demo",
        localizedName: LocalizedText = LocalizedText.fromLegacy(name),
    ) = TransitCity(
        id = CityId(id),
        name = name,
        center = GeoPoint(41.7, 44.8),
        capabilities = CityCapabilities(
            stops = true,
            vehicles = true,
            arrivals = true,
            routeShapes = true,
            journeyPlanning = true,
            routes = routesAvailable,
        ),
        localizedName = localizedName,
    )

    private fun route(
        cityId: CityId,
        rawId: String,
        shortName: String,
        name: String,
        longName: LocalizedText = LocalizedText.fromLegacy(name),
        directions: List<TransitDirection> = emptyList(),
        mode: TransitMode = TransitMode.Bus,
    ) = TransitRoute(
        id = RouteId(rawId),
        cityId = cityId,
        shortName = shortName,
        name = name,
        colorArgb = 0xFF0057B8,
        longName = longName,
        directions = directions,
        mode = mode,
    )

    private sealed interface RefreshResponse {
        data class Result(val value: TransitLoadResult<List<TransitRoute>>) : RefreshResponse
        data class Await(val deferred: CompletableDeferred<TransitLoadResult<List<TransitRoute>>>) : RefreshResponse
        /** Models a provider response that arrives even after its consumer cancelled the request. */
        data class AwaitIgnoringCancellation(
            val deferred: CompletableDeferred<TransitLoadResult<List<TransitRoute>>>,
        ) : RefreshResponse
        data object Cancel : RefreshResponse
        data object Throw : RefreshResponse
    }

    private class CountingRepository(
        private val snapshot: List<TransitRoute> = emptyList(),
        private val responses: ArrayDeque<RefreshResponse> = ArrayDeque(),
    ) : TransitRepository {
        var snapshotCalls = 0
            private set
        val refreshStarted = CompletableDeferred<Unit>()
        val cancellationThrown = CompletableDeferred<Unit>()
        val refreshCallerJob = CompletableDeferred<Job>()
        val refreshRequests = mutableListOf<RouteListRequest>()
        var cancellationCount = 0
            private set

        override fun cities(): List<TransitCity> = emptyList()

        override fun routes(cityId: CityId): List<TransitRoute> {
            snapshotCalls += 1
            return snapshot
        }

        override suspend fun refreshRoutes(request: RouteListRequest): TransitLoadResult<List<TransitRoute>> {
            refreshRequests += request
            refreshCallerJob.complete(requireNotNull(currentCoroutineContext()[Job]))
            refreshStarted.complete(Unit)
            return when (val response = responses.removeFirstOrNull()) {
                is RefreshResponse.Result -> response.value
                is RefreshResponse.Await -> response.deferred.await()
                is RefreshResponse.AwaitIgnoringCancellation -> withContext(NonCancellable) {
                    response.deferred.await()
                }
                RefreshResponse.Cancel -> {
                    cancellationCount += 1
                    cancellationThrown.complete(Unit)
                    throw CancellationException("test cancellation")
                }
                RefreshResponse.Throw -> throw IllegalStateException("network unavailable")
                null -> TransitLoadResult.Data(snapshot, TransitFreshness.CacheValid)
            }
        }
    }
}
