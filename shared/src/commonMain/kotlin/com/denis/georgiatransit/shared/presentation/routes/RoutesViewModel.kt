package com.denis.georgiatransit.shared.presentation.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class RoutesViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            val city = session.selectedCity.value
            reduce {
                ViewState(
                    cityName = city?.name.orEmpty(),
                    routes = city?.let { repository.routes(it.id) }.orEmpty().map {
                        RouteItemUiModel(it.id, it.shortName, it.name, it.colorArgb)
                    },
                    selectedIds = session.selectedRouteIds.value,
                    catalog = if (city == null) {
                        CatalogState.Error(
                            failure = TransitFailure.Configuration("A selected city is required to load routes"),
                            canRetry = false,
                        )
                    } else {
                        CatalogState.Loading
                    },
                )
            }
            city?.let { selectedCity ->
                val result = repository.refreshRoutes(RouteListRequest(selectedCity.id))
                reduce { state.withCatalogResult(result) }
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.RouteToggled -> onRouteToggled(action.routeId)
            Action.RetryClicked -> onRetryClicked()
            Action.BackClicked -> onBackClicked()
            Action.ConfirmClicked -> onConfirmClicked()
        }
    }

    private fun onRouteToggled(routeId: RouteId) = intent {
        if (state.catalog !is CatalogState.Available) return@intent
        if (state.routes.none { it.id == routeId }) return@intent
        val updated = state.selectedIds.toMutableSet().apply {
            if (!add(routeId)) remove(routeId)
        }
        reduce { state.copy(selectedIds = updated) }
    }

    private fun onRetryClicked() = intent {
        val catalog = state.catalog as? CatalogState.Error ?: return@intent
        if (!catalog.canRetry) return@intent
        val city = session.selectedCity.value ?: return@intent
        reduce { state.copy(catalog = CatalogState.Loading) }
        val result = repository.refreshRoutes(RouteListRequest(city.id))
        reduce { state.withCatalogResult(result) }
    }

    private fun onConfirmClicked() = intent {
        session.selectRoutes(state.selectedIds)
        postSideEffect(NavigationEffect.BackToMap)
    }

    private fun onBackClicked() = intent {
        postSideEffect(NavigationEffect.BackToMap)
    }

    private fun ViewState.withCatalogResult(
        result: TransitLoadResult<List<com.denis.georgiatransit.shared.domain.model.TransitRoute>>,
    ): ViewState = when (result) {
        is TransitLoadResult.Data -> copy(
            routes = result.value.map { RouteItemUiModel(it.id, it.shortName, it.name, it.colorArgb) },
            selectedIds = selectedIds.intersect(result.value.mapTo(mutableSetOf()) { it.id }),
            catalog = CatalogState.Available(result.freshness),
        )
        is TransitLoadResult.Empty -> copy(
            routes = emptyList(),
            selectedIds = emptySet(),
            catalog = CatalogState.Empty(result.freshness),
        )
        is TransitLoadResult.Failure -> copy(
            catalog = CatalogState.Error(result.error, result.error.canRetryCatalogRefresh()),
        )
    }

    private fun TransitFailure.canRetryCatalogRefresh(): Boolean = when (this) {
        is TransitFailure.Transport,
        is TransitFailure.Timeout,
        is TransitFailure.RateLimited,
        is TransitFailure.UpstreamBadResponse,
        is TransitFailure.UpstreamUnavailable,
        is TransitFailure.UpstreamTimeout,
        is TransitFailure.Internal,
        -> true

        else -> false
    }
}
