package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource
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
    /** Independent stop-arrivals content; nearby viewport refreshes never replace this sheet. */
    val stopArrivalsSheet: StopArrivalsSheetUi? = null,
    /** Independent from nearby-stop loading: vehicle polling must never replace stop feedback. */
    val vehicleLayerState: VehicleLayerState = VehicleLayerState.Hidden,
    val vehicleRoutes: List<VehicleRouteAccessibilityUi> = emptyList(),
)

/** Product-level realtime availability. No transport or provider details reach the UI. */
@Immutable
sealed interface VehicleLayerState {
    @Immutable data object Hidden : VehicleLayerState
    @Immutable data object Loading : VehicleLayerState
    @Immutable data object Live : VehicleLayerState
    /** A last valid frame is visible but no longer fresh. */
    @Immutable data object Stale : VehicleLayerState
    /** The next normal polling interval will try again; existing tracks are held stale. */
    @Immutable data object Retryable : VehicleLayerState
    /** Realtime is disabled or the BFF rejected this input until selection changes. */
    @Immutable data object Unavailable : VehicleLayerState
    /** Selected routes disagree; route-level accessibility rows retain every individual phase. */
    @Immutable data object Mixed : VehicleLayerState
}

/** Compact non-map equivalent for route badges, including stable typed route ownership. */
@Immutable
data class VehicleRouteAccessibilityUi(
    val routeId: RouteId,
    val routeLabel: String,
    val vehicleCount: Int,
    val layerState: VehicleLayerState,
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
    val sourceRevision: Long = 0L,
)

/** A selected-stop snapshot that is safe to render without exposing opaque provider identifiers. */
@Immutable
data class StopArrivalsSheetUi(
    val stopId: StopId,
    val stopName: String,
    val stopCode: String? = null,
    val passingRouteShortNames: List<String> = emptyList(),
    /** True when the stop or page references routes that the common route catalogue cannot name. */
    val hasUnavailableRouteDetails: Boolean = false,
    val rows: List<StopArrivalRowUi> = emptyList(),
    val state: StopArrivalsSheetState = StopArrivalsSheetState.Loading,
    val pageSource: ArrivalSourceUi? = null,
    /** Separate from page/source state: stale data must never appear current. */
    val isStale: Boolean = false,
    /** Kept separate so a poll retains the rendered header and row order. */
    val isRefreshing: Boolean = false,
    /** Independent walking feedback; arrivals are never blocked or replaced by this state. */
    val walkingEstimate: WalkingEstimateUi = WalkingEstimateUi.Unavailable(WalkingEstimateUnavailableReason.NoAccurateFix),
)

@Immutable
sealed interface WalkingEstimateUi {
    @Immutable data object Loading : WalkingEstimateUi
    @Immutable data class Ready(
        val source: WalkingEstimateSource,
        val distanceMeters: Double,
        val durationSeconds: Long,
        val locale: TransitLocale,
    ) : WalkingEstimateUi
    @Immutable data class Unavailable(val reason: WalkingEstimateUnavailableReason) : WalkingEstimateUi
}

/** User-facing reason without leaking provider/network internals into the sheet. */
@Immutable
enum class WalkingEstimateUnavailableReason {
    PermissionRequired,
    PermissionDenied,
    SettingsRequired,
    Restricted,
    ServicesDisabled,
    LocationUnavailable,
    NoAccurateFix,
}

@Immutable
data class StopArrivalRowUi(
    /** Null deliberately renders a localized unavailable label, never an opaque route ID. */
    val routeShortName: String?,
    val headsign: String,
    val time: StopArrivalTimeUi,
    val source: ArrivalSourceUi,
)

@Immutable
sealed interface StopArrivalTimeUi {
    @Immutable data object Arriving : StopArrivalTimeUi
    @Immutable data class Minutes(val value: Int) : StopArrivalTimeUi
    @Immutable data object Unavailable : StopArrivalTimeUi
}

/** Explicit presentation labels; [Approximate] is never rendered as an official source. */
@Immutable
enum class ArrivalSourceUi { OfficialRealtime, AggregatorRealtime, Schedule, Approximate }

/** Initial loading is intentionally distinct from all honest terminal/content states. */
@Immutable
sealed interface StopArrivalsSheetState {
    @Immutable data object Loading : StopArrivalsSheetState
    @Immutable data object NoArrivals : StopArrivalsSheetState
    @Immutable data object PartialData : StopArrivalsSheetState
    @Immutable data object Offline : StopArrivalsSheetState
    @Immutable data object UpstreamError : StopArrivalsSheetState
    @Immutable data object Unavailable : StopArrivalsSheetState
    @Immutable data object Ready : StopArrivalsSheetState
}

sealed interface Action {
    data object RoutesClicked : Action
    data object ChangeCityClicked : Action
    data object MyLocationClicked : Action
    data class LocationEventReceived(val event: LocationPlatformEvent) : Action
    data class MapEventReceived(val event: MapPlatformEvent) : Action
    data class StopSelected(val stopId: StopId, val sourceRevision: Long) : Action
    data object StopArrivalsDismissed : Action
    data class MapViewportInsetsChanged(val insets: MapViewportInsets) : Action
    data object RetryStopArrivals : Action
    data class LocaleChanged(val locale: TransitLocale) : Action
    data object RetryNearby : Action
    /** Emitted by the common composed Map entry and its host lifecycle; it never reflects panning. */
    data class RealtimeVisibilityChanged(val isVisibleAndStarted: Boolean) : Action
}

sealed interface SideEffect {
    data class HandleLocation(val command: LocationPlatformCommand) : SideEffect
}

sealed interface NavigationEffect : SideEffect {
    data object OpenRoutes : NavigationEffect
    data object OpenCitySelection : NavigationEffect
}
