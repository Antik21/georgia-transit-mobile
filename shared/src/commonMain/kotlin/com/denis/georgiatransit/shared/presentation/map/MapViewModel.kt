package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.flow.combine
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class MapViewModel(
    repository: TransitRepository,
    session: TransitSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            combine(session.selectedCity, session.selectedRouteIds) { city, selectedIds ->
                val routeNames = city?.let { selectedCity ->
                    repository.routes(selectedCity.id).filter { it.id in selectedIds }.map { it.shortName }
                }.orEmpty()
                ViewState(cityName = city?.name.orEmpty(), selectedRouteNames = routeNames)
            }.collect { newState -> reduce { newState } }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            Action.RoutesClicked -> intent { postSideEffect(NavigationEffect.OpenRoutes) }
            Action.ChangeCityClicked -> intent { postSideEffect(NavigationEffect.OpenCitySelection) }
        }
    }
}

