package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable

@Immutable
data class ViewState(
    val cityName: String = "",
    val viewport: MapViewport? = null,
    val selectedRouteNames: List<String> = emptyList(),
)

sealed interface Action {
    data object RoutesClicked : Action
    data object ChangeCityClicked : Action
}

sealed interface SideEffect

sealed interface NavigationEffect : SideEffect {
    data object OpenRoutes : NavigationEffect
    data object OpenCitySelection : NavigationEffect
}
