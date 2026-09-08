package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationState

/**
 * The map's product-data state. It is separate from location permission state so a map remains
 * usable without location access and adapters never infer data availability from native SDK state.
 */
@Immutable
sealed interface MapContentState {
    @Immutable data object Loading : MapContentState
    @Immutable data object Empty : MapContentState
    /** A transient failure for which retrying the same normalized viewport is safe. */
    @Immutable data object RetryableError : MapContentState
    @Immutable data object Unavailable : MapContentState
    @Immutable data class Offline(val isStale: Boolean) : MapContentState
    @Immutable data object Ready : MapContentState
}

/** Availability of the visual base layer, independent of nearby transit data. */
@Immutable
sealed interface MapBaseLayerState {
    /** No BFF-approved basemap asset contract exists yet, so the renderer is deliberately local. */
    @Immutable data object LocalPreview : MapBaseLayerState
}

@Immutable
data class ViewState(
    val cityName: String = "",
    val renderState: MapRenderState? = null,
    val contentState: MapContentState = MapContentState.Unavailable,
    val baseLayerState: MapBaseLayerState = MapBaseLayerState.LocalPreview,
    val selectedRouteNames: List<String> = emptyList(),
    val attribution: List<TransitAttribution> = emptyList(),
    val location: LocationState = LocationState(),
    val selectedStop: SelectedStopUi? = null,
    val nearbyStops: List<NearbyStopUi> = emptyList(),
)

@Immutable
data class SelectedStopUi(
    val id: StopId,
    val name: String,
)

@Immutable
data class NearbyStopUi(
    val id: StopId,
    val name: String,
    val isSelected: Boolean,
)

sealed interface Action {
    data object RoutesClicked : Action
    data object ChangeCityClicked : Action
    data object MyLocationClicked : Action
    data class LocationEventReceived(val event: LocationPlatformEvent) : Action
    data class MapEventReceived(val event: MapPlatformEvent) : Action
    data class StopSelected(val stopId: StopId) : Action
    data class LocaleChanged(val locale: TransitLocale) : Action
    data object RetryNearby : Action
}

sealed interface SideEffect {
    data class HandleLocation(val command: LocationPlatformCommand) : SideEffect
}

sealed interface NavigationEffect : SideEffect {
    data object OpenRoutes : NavigationEffect
    data object OpenCitySelection : NavigationEffect
}
