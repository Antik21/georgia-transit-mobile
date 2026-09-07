package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationState

@Immutable
data class ViewState(
    val cityName: String = "",
    val viewport: MapViewport? = null,
    val selectedRouteNames: List<String> = emptyList(),
    val attribution: List<TransitAttribution> = emptyList(),
    val location: LocationState = LocationState(),
)

sealed interface Action {
    data object RoutesClicked : Action
    data object ChangeCityClicked : Action
    data object MyLocationClicked : Action
    data class LocationEventReceived(val event: LocationPlatformEvent) : Action
}

sealed interface SideEffect {
    data class HandleLocation(val command: LocationPlatformCommand) : SideEffect
}

sealed interface NavigationEffect : SideEffect {
    data object OpenRoutes : NavigationEffect
    data object OpenCitySelection : NavigationEffect
}
