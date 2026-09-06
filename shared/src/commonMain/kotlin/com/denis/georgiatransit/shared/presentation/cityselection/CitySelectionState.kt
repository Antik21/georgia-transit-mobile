package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationState

@Immutable
data class ViewState(
    val cities: List<CityItemUiModel> = emptyList(),
    val selectedCityId: CityId? = null,
    val location: LocationState = LocationState(),
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
    data object LocationClicked : Action
    data class LocationEventReceived(val event: LocationPlatformEvent) : Action
}

sealed interface SideEffect {
    data class HandleLocation(val command: LocationPlatformCommand) : SideEffect
}

sealed interface NavigationEffect : SideEffect {
    data object OpenMap : NavigationEffect
}
