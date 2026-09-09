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
import com.denis.georgiatransit.shared.presentation.ui.RouteColorAvailability

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
    /** Routes are a capability-gated catalogue, not an implicit property of every map city. */
    val routesAvailable: Boolean = false,
    val renderState: MapRenderState? = null,
    val contentState: MapContentState = MapContentState.Unavailable,
    val baseLayerState: MapBaseLayerState = MapBaseLayerState.LocalPreview,
    val selectedRouteNames: List<String> = emptyList(),
    /** Common route-shape progress and interaction state; no provider failure text reaches UI. */
    val routeGeometryLegends: List<RouteGeometryLegendUi> = emptyList(),
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
    val backgroundArgb: Long = 0xFF3F3F46L,
    val textArgb: Long = 0xFFFFFFFFL,
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
    val routeHighlight: StopRouteHighlightUi = StopRouteHighlightUi(),
)

/** Common marker metadata; adapters receive values only and never infer route membership. */
@Immutable
data class StopRouteHighlightUi(
    val matchingRouteIds: List<RouteId> = emptyList(),
    val matchingRouteLabels: List<String> = emptyList(),
    val style: StopRouteHighlightStyle = StopRouteHighlightStyle.None,
    val backgroundArgb: Long = DefaultStopBackgroundArgb,
    val textArgb: Long = DefaultStopTextArgb,
) {
    val matchingRouteCount: Int get() = matchingRouteIds.size
    val isHighlighted: Boolean get() = style != StopRouteHighlightStyle.None
    /** Read directly by native adapters; the selected-stop layer remains a higher priority. */
    val markerRadius: Double get() = if (isHighlighted) HighlightedStopRadius else DefaultStopRadius
    val markerStrokeWidth: Double get() = if (isHighlighted) HighlightedStopStrokeWidth else DefaultStopStrokeWidth
}

enum class StopRouteHighlightStyle {
    None,
    SingleRoute,
    MultipleRoutes,
}

@Immutable
data class StopRouteBadgeUi(
    val routeId: RouteId,
    val routeLabel: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val colorAvailability: RouteColorAvailability,
    /** Only committed Map selection gets this marker; draft changes stay in Routes. */
    val isSelected: Boolean,
)

/** A selected-stop snapshot that is safe to render without exposing opaque provider identifiers. */
@Immutable
data class StopArrivalsSheetUi(
    val stopId: StopId,
    val stopName: String,
    val stopCode: String? = null,
    /** Typed, catalogue-ordered badges for every known route passing the selected stop. */
    val passingRoutes: List<StopRouteBadgeUi> = emptyList(),
    /** Kept for existing textual consumers; it mirrors [passingRoutes] when this VM builds state. */
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
    val routeId: RouteId? = null,
    /** Uses the same projected style as an active line, vehicle badge, and sheet header badge. */
    val routeBadge: StopRouteBadgeUi? = null,
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

const val DefaultStopBackgroundArgb = 0xFF2A9D8FL
const val DefaultStopTextArgb = 0xFFFFFFFFL
const val MultiRouteStopBackgroundArgb = 0xFF455A64L
const val DefaultStopRadius = 5.0
const val HighlightedStopRadius = 7.0
const val DefaultStopStrokeWidth = 2.0
const val HighlightedStopStrokeWidth = 3.0

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
    data class RouteGeometryFocused(val routeId: RouteId) : Action
    data class RetryRouteGeometry(val routeId: RouteId) : Action
    data class RemoveRouteGeometry(val routeId: RouteId) : Action
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
