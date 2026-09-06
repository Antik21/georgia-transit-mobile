package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.viewmodel.container

class CitySelectionViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            reduce {
                ViewState(
                    cities = repository.cities().map { city ->
                        CityItemUiModel(
                            id = city.id,
                            name = city.name,
                            isEnabled = city.capabilities.stops,
                            isExperimental = city.capabilities.experimental,
                        )
                    },
                    selectedCityId = session.selectedCity.value?.id,
                )
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.CityClicked -> onCityClicked(action.cityId)
            Action.ContinueClicked -> onContinueClicked()
        }
    }

    private fun onCityClicked(cityId: CityId) = intent {
        if (state.cities.any { it.id == cityId && it.isEnabled }) reduce { state.copy(selectedCityId = cityId) }
    }

    private fun onContinueClicked() = intent {
        val selectedId = state.selectedCityId ?: return@intent
        val city = repository.cities().firstOrNull { it.id == selectedId } ?: return@intent
        session.selectCity(city)
        postSideEffect(NavigationEffect.OpenMap)
    }
}

