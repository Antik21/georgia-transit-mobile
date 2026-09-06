package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId

@Immutable
data class ViewState(
    val cities: List<CityItemUiModel> = emptyList(),
    val selectedCityId: CityId? = null,
)

@Immutable
data class CityItemUiModel(
    val id: CityId,
    val name: String,
    val isEnabled: Boolean,
    val isExperimental: Boolean,
)

sealed interface Action {
    data class CityClicked(val cityId: CityId) : Action
    data object ContinueClicked : Action
}

sealed interface SideEffect

sealed interface NavigationEffect : SideEffect {
    data object OpenMap : NavigationEffect
}

