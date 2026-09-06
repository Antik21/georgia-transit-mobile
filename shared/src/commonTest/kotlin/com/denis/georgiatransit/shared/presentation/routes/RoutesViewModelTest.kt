package com.denis.georgiatransit.shared.presentation.routes

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
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

            viewModel.dispatchAction(Action.BackClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.BackToMap)
            assertEquals(selectedIds, session.selectedRouteIds.value)
            assertEquals(selectedIds, viewModel.container.stateFlow.value.selectedIds)
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
    }
}
