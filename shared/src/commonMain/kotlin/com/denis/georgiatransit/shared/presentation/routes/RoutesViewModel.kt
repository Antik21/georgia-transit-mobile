package com.denis.georgiatransit.shared.presentation.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.RouteId
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
                )
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.RouteToggled -> onRouteToggled(action.routeId)
            Action.ConfirmClicked -> onConfirmClicked()
        }
    }

    private fun onRouteToggled(routeId: RouteId) = intent {
        if (state.routes.none { it.id == routeId }) return@intent
        val updated = state.selectedIds.toMutableSet().apply {
            if (!add(routeId)) remove(routeId)
        }
        reduce { state.copy(selectedIds = updated) }
    }

    private fun onConfirmClicked() = intent {
        session.selectRoutes(state.selectedIds)
        postSideEffect(NavigationEffect.BackToMap)
    }
}

