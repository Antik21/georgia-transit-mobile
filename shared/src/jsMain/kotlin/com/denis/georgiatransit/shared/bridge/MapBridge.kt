@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelStore
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.VehicleId
import com.denis.georgiatransit.shared.presentation.cityselection.citySelectionDisplayName
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.map.Action
import com.denis.georgiatransit.shared.presentation.map.MapCameraCommand
import com.denis.georgiatransit.shared.presentation.map.MapContentState
import com.denis.georgiatransit.shared.presentation.map.MapPlatformEvent
import com.denis.georgiatransit.shared.presentation.map.MapPolyline
import com.denis.georgiatransit.shared.presentation.map.MapStopCluster
import com.denis.georgiatransit.shared.presentation.map.MapStopMarker
import com.denis.georgiatransit.shared.presentation.map.MapVehicleMarker
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.MapViewport
import com.denis.georgiatransit.shared.presentation.map.MapZoomDirection
import com.denis.georgiatransit.shared.presentation.map.MapZoomStep
import com.denis.georgiatransit.shared.presentation.map.NearbyStopUi
import com.denis.georgiatransit.shared.presentation.map.NavigationEffect
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryLegendUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalTimeUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetState
import com.denis.georgiatransit.shared.presentation.map.SideEffect
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetUi
import com.denis.georgiatransit.shared.presentation.map.VehicleLayerState
import com.denis.georgiatransit.shared.presentation.map.VehicleRouteAccessibilityUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@JsExport
class StopItem(
    val id: String,
    val name: String,
    val sourceRevision: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@JsExport
class ArrivalItem(
    val route: String?,
    val headsign: String,
    val timeKind: String,
    val minutes: Int?,
    val source: String,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
)

@JsExport
class ArrivalsState(
    val stopId: String,
    val stopName: String,
    val status: String,
    val stale: Boolean,
    val refreshing: Boolean,
    val rows: Array<ArrivalItem>,
    val routes: Array<StopRouteBadge> = emptyArray(),
)

@JsExport
class MapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val revision: String,
    val animated: Boolean = false,
)

@JsExport
class MapZoom(val totalDelta: Double)

@JsExport
class TransitCredit(val label: String, val url: String)

@JsExport
class StopMapState(
    val cityName: String,
    val content: String,
    val stops: Array<StopItem>,
    val arrivals: ArrivalsState?,
    val cityId: String = "",
    val canChangeCity: Boolean = true,
    val camera: MapCamera? = null,
    val styleUrl: String? = null,
    val attribution: Array<TransitCredit> = emptyArray(),
    val canOpenRoutes: Boolean = false,
    val routePolylines: Array<RoutePolyline> = emptyArray(),
    val routePolylineRevision: String = "0",
    val routeLegends: Array<RouteLegend> = emptyArray(),
    val vehicleStatus: String = "hidden",
    val vehicleRoutes: Array<VehicleRouteSummary> = emptyArray(),
    val location: LocationStatus? = null,
    val userLocation: UserLocation? = null,
    val stopLayer: MapStopLayer? = null,
    val zoomStep: MapZoom? = null,
)

private data class VehicleSlice(
    val revision: Long,
    val badgeRevision: Long,
    val vehicles: List<MapVehicleMarker>,
)

private data class StopSlice(
    val cityName: String,
    val content: MapContentState,
    val stops: List<NearbyStopUi>,
    val sheet: StopArrivalsSheetUi?,
    val canChangeCity: Boolean,
    val camera: MapCameraCommand?,
    val styleUrl: String?,
    val attribution: List<TransitAttribution>,
    val canOpenRoutes: Boolean,
    val routePolylines: List<MapPolyline>,
    val routePolylineRevision: Long,
    val routeLegends: List<RouteGeometryLegendUi>,
    val vehicleLayerState: VehicleLayerState,
    val vehicleRoutes: List<VehicleRouteAccessibilityUi>,
    val location: LocationState,
    val userLocation: UserLocationFix?,
    val mapStops: List<MapStopMarker>,
    val mapStopClusters: List<MapStopCluster>,
    val stopSourceRevision: Long?,
    val zoomStep: MapZoomStep?,
)

/** Owns the Map entry and its subscriptions; dispose when the host leaves the entry. */
@JsExport
class MapBridge internal constructor(
    private val viewModel: MapViewModel,
    private val store: ViewModelStore,
    private val city: TransitCity? = null,
    private val languageTag: String = "en",
    onNavigation: ((NavigationEffect) -> Unit)? = null,
    private val locationSession: LocationSession? = null,
    private val onDisposed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subscription: Job? = null
    private var vehicleSubscription: Job? = null
    private var disposed = false
    val planner = JourneyPlannerBridge(viewModel, scope) { disposed }
    private var locationHandler: ((LocationCommand) -> Unit)? = null
    private val pendingLocationCommands = ArrayDeque<LocationPlatformCommand>()

    init {
        // Orbit delivers each side effect to one collector only, so every effect kind shares this one.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.container.sideEffectFlow.collect { effect ->
                when (effect) {
                    is NavigationEffect -> onNavigation?.invoke(effect)
                    is SideEffect.HandleLocation -> deliverLocationCommand(effect.command)
                }
            }
        }
    }

    private fun deliverLocationCommand(command: LocationPlatformCommand) {
        val handler = locationHandler
        if (handler == null) pendingLocationCommands.addLast(command) else notifyHost { handler(command.toJs()) }
    }

    /** The host executes each command once and reports back through the `location*` methods. */
    fun subscribeLocationCommands(onCommand: (LocationCommand) -> Unit) {
        check(!disposed)
        locationHandler = onCommand
        while (pendingLocationCommands.isNotEmpty()) {
            val command = pendingLocationCommands.removeFirst()
            notifyHost { onCommand(command.toJs()) }
        }
    }

    fun myLocation() { if (!disposed) viewModel.dispatchAction(Action.MyLocationClicked) }
    fun zoomIn() { if (!disposed) viewModel.dispatchAction(Action.ZoomClicked(MapZoomDirection.In)) }
    fun zoomOut() { if (!disposed) viewModel.dispatchAction(Action.ZoomClicked(MapZoomDirection.Out)) }
    fun refreshLocation() { if (!disposed) viewModel.dispatchAction(Action.RefreshLocation) }

    fun locationPermission(state: String, precise: Boolean, canRetry: Boolean) =
        dispatchLocation(permissionEvent(state, precise, canRetry))

    fun locationFix(requestId: String, latitude: Double, longitude: Double, accuracyMeters: Double, capturedAt: Double) =
        dispatchLocation(fixEvent(requestId, latitude, longitude, accuracyMeters, capturedAt))

    fun locationFailed(requestId: String, failure: String) = dispatchLocation(failureEvent(requestId, failure))
    fun locationCancelled(requestId: String) = dispatchLocation(cancelledEvent(requestId))

    private fun dispatchLocation(event: LocationPlatformEvent?) {
        if (event != null && !disposed) viewModel.dispatchAction(Action.LocationEventReceived(event))
    }

    fun subscribe(onState: (StopMapState) -> Unit) {
        check(!disposed)
        subscription?.cancel()
        val newSubscription = scope.launch(start = CoroutineStart.LAZY) {
            var projectedRevision: Long? = null
            var projectedPolylines = emptyArray<RoutePolyline>()
            var projectedStopKey: Triple<Long?, List<MapStopMarker>, List<MapStopCluster>>? = null
            var projectedStops: MapStopLayer? = null
            viewModel.container.stateFlow
                .map { state ->
                    val render = state.renderState
                    StopSlice(
                        state.cityName,
                        state.contentState,
                        state.nearbyStops,
                        state.stopArrivalsSheet,
                        state.canChangeCity,
                        render?.camera,
                        render?.bffStyleUrl,
                        state.attribution,
                        state.routesAvailable,
                        render?.polylines.orEmpty(),
                        render?.polylineSourceRevision ?: 0L,
                        state.routeGeometryLegends,
                        state.vehicleLayerState,
                        state.vehicleRoutes,
                        state.location,
                        render?.userLocation,
                        render?.stops.orEmpty(),
                        render?.stopClusters.orEmpty(),
                        render?.stopSourceRevision,
                        render?.zoomStep,
                    )
                }
                .distinctUntilChanged()
                .collect { slice ->
                    if (projectedRevision != slice.routePolylineRevision) {
                        projectedRevision = slice.routePolylineRevision
                        projectedPolylines = slice.routePolylines.map { it.toJs() }.toTypedArray()
                    }
                    // The renderer compares this layer by identity, so it is rebuilt only when stop content changes.
                    val stopKey = Triple(slice.stopSourceRevision, slice.mapStops, slice.mapStopClusters)
                    if (projectedStopKey != stopKey) {
                        projectedStopKey = stopKey
                        projectedStops = slice.stopSourceRevision?.let { stopLayerOf(it, slice.mapStops, slice.mapStopClusters) }
                    }
                    notifyHost { onState(slice.toJs(city, languageTag, projectedPolylines, projectedStops)) }
                }
        }
        subscription = newSubscription
        newSubscription.start()
    }

    /** Vehicle frames arrive at animation cadence, so they bypass [subscribe] and its DOM rebuild. */
    fun subscribeVehicles(onVehicles: (VehicleLayer) -> Unit) {
        check(!disposed)
        vehicleSubscription?.cancel()
        val newSubscription = scope.launch(start = CoroutineStart.LAZY) {
            viewModel.container.stateFlow
                .map { state ->
                    val render = state.renderState
                    VehicleSlice(render?.vehicleSourceRevision ?: 0L, render?.vehicleBadgeRevision ?: 0L, render?.vehicles.orEmpty())
                }
                .distinctUntilChanged()
                .collect { slice ->
                    val layer = VehicleLayer(
                        revision = slice.revision.toString(),
                        badgeRevision = slice.badgeRevision.toString(),
                        vehicles = slice.vehicles.map { it.toJs() }.toTypedArray(),
                    )
                    notifyHost { onVehicles(layer) }
                }
        }
        vehicleSubscription = newSubscription
        newSubscription.start()
    }

    fun selectVehicle(id: String, revision: String) {
        val sourceRevision = revision.toLongOrNull() ?: return
        if (!disposed) {
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.VehicleTapped(VehicleId(id), sourceRevision)))
        }
    }

    fun setVisible(visible: Boolean) {
        if (!disposed) viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visible))
    }

    fun updateViewport(latitude: Double, longitude: Double, radiusMeters: Int, zoom: Double) {
        if (!disposed && radiusMeters > 0) {
            val viewport = MapViewport(GeoPoint(latitude, longitude), radiusMeters, zoom)
            viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.ViewportSettled(viewport)))
        }
    }

    fun selectStop(id: String, sourceRevision: String) {
        val revision = sourceRevision.toLongOrNull() ?: return
        if (!disposed) viewModel.dispatchAction(Action.StopSelected(StopId(id), revision))
    }

    /** A map pin tap: unlike [selectStop] it is checked against the visible markers and may zoom into a group. */
    fun tapStop(id: String, sourceRevision: String) {
        val revision = sourceRevision.toLongOrNull() ?: return
        if (!disposed) viewModel.dispatchAction(Action.MapEventReceived(MapPlatformEvent.StopTapped(StopId(id), revision)))
    }

    fun dismissArrivals() { if (!disposed) viewModel.dispatchAction(Action.StopArrivalsDismissed) }
    fun retryArrivals() { if (!disposed) viewModel.dispatchAction(Action.RetryStopArrivals) }
    fun retryNearby() { if (!disposed) viewModel.dispatchAction(Action.RetryNearby) }
    fun unsubscribe() {
        subscription?.cancel()
        subscription = null
        vehicleSubscription?.cancel()
        vehicleSubscription = null
        planner.unsubscribe()
    }

    fun focusRoute(id: String) {
        if (!disposed) viewModel.dispatchAction(Action.RouteGeometryFocused(RouteId(id)))
    }

    fun retryRoute(id: String) {
        if (!disposed) viewModel.dispatchAction(Action.RetryRouteGeometry(RouteId(id)))
    }

    fun removeRoute(id: String) {
        if (!disposed) viewModel.dispatchAction(Action.RemoveRouteGeometry(RouteId(id)))
    }

    internal fun requestRouteSelection() {
        if (!disposed) viewModel.dispatchAction(Action.RoutesClicked)
    }

    internal fun requestCitySelection() {
        if (!disposed) viewModel.dispatchAction(Action.ChangeCityClicked)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        locationHandler = null
        pendingLocationCommands.clear()
        // The session outlives this entry; an orphaned request would block every later one.
        locationSession?.let { session -> session.state.value.activeRequestId?.let(session::cancelRequest) }
        scope.cancel()
        store.clear()
        onDisposed()
    }
}

private fun StopSlice.toJs(
    city: TransitCity?,
    languageTag: String,
    polylines: Array<RoutePolyline>,
    stopLayer: MapStopLayer?,
): StopMapState = StopMapState(
    cityName = city?.localizedName?.citySelectionDisplayName(languageTag, cityName) ?: cityName,
    content = when (content) {
        MapContentState.Loading -> "loading"
        MapContentState.Empty -> "empty"
        MapContentState.RetryableError -> "retryableError"
        MapContentState.Unavailable -> "unavailable"
        is MapContentState.Offline -> if (content.isStale) "offlineStale" else "offline"
        MapContentState.Ready -> "ready"
    },
    stops = stops.map { stop ->
        StopItem(stop.id.value, stop.name, stop.sourceRevision.toString(), stop.position?.latitude, stop.position?.longitude)
    }.toTypedArray(),
    arrivals = sheet?.toJs(),
    cityId = city?.id?.value.orEmpty(),
    canChangeCity = canChangeCity,
    camera = camera?.let { MapCamera(it.center.latitude, it.center.longitude, it.zoom, it.revision.toString(), it.animated) },
    styleUrl = styleUrl,
    attribution = attribution.toCredits(languageTag),
    canOpenRoutes = canOpenRoutes,
    routePolylines = polylines,
    routePolylineRevision = routePolylineRevision.toString(),
    routeLegends = routeLegends.map { it.toJs() }.toTypedArray(),
    vehicleStatus = vehicleLayerState.toJs(),
    vehicleRoutes = vehicleRoutes.map { it.toJs() }.toTypedArray(),
    location = location.toJs(),
    userLocation = userLocation?.toJs(),
    stopLayer = stopLayer,
    zoomStep = zoomStep?.let { MapZoom(it.totalDelta) },
)

private fun StopArrivalsSheetUi.toJs(): ArrivalsState = ArrivalsState(
    stopId = stopId.value,
    stopName = stopName,
    status = when (state) {
        StopArrivalsSheetState.Loading -> "loading"
        StopArrivalsSheetState.NoArrivals -> "noArrivals"
        StopArrivalsSheetState.PartialData -> "partialData"
        StopArrivalsSheetState.Offline -> "offline"
        StopArrivalsSheetState.UpstreamError -> "upstreamError"
        StopArrivalsSheetState.Unavailable -> "unavailable"
        StopArrivalsSheetState.Ready -> "ready"
    },
    stale = isStale,
    refreshing = isRefreshing,
    routes = passingRoutes.map { it.toJs() }.toTypedArray(),
    rows = rows.map { row ->
        ArrivalItem(
            route = row.routeShortName,
            headsign = row.headsign,
            timeKind = when (row.time) {
                StopArrivalTimeUi.Arriving -> "arriving"
                is StopArrivalTimeUi.Minutes -> "minutes"
                StopArrivalTimeUi.Unavailable -> "unavailable"
            },
            minutes = (row.time as? StopArrivalTimeUi.Minutes)?.value,
            source = row.source.name,
            routeColor = row.routeBadge?.backgroundArgb?.toCssColor(),
            routeTextColor = row.routeBadge?.textArgb?.toCssColor(),
        )
    }.toTypedArray(),
)
