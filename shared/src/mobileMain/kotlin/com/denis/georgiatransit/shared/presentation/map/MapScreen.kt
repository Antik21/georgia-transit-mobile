package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.map.sections.CityAttribution
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickPanel
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPlannerPanel
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPlannerSheet
import com.denis.georgiatransit.shared.presentation.map.sections.MapConnectionToast
import com.denis.georgiatransit.shared.presentation.map.sections.RouteGeometryLegend
import com.denis.georgiatransit.shared.presentation.map.sections.StopArrivalsSheet
import com.denis.georgiatransit.shared.presentation.map.sections.VehicleAccessibility
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.location_action_enable
import georgiatransit.shared.generated.resources.location_action_location_settings
import georgiatransit.shared.generated.resources.location_action_retry
import georgiatransit.shared.generated.resources.location_action_settings
import georgiatransit.shared.generated.resources.location_failure_invalid
import georgiatransit.shared.generated.resources.location_failure_timed_out
import georgiatransit.shared.generated.resources.location_failure_unavailable
import georgiatransit.shared.generated.resources.map_change_city_action
import georgiatransit.shared.generated.resources.map_my_location_action
import georgiatransit.shared.generated.resources.map_routes_action
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun MapScreen(viewModel: MapViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(language.toTransitLocale()))
    }
    val mapViewportInsets = if (state.stopArrivalsSheet == null) {
        MapViewportInsets.None
    } else {
        MapViewportInsets.StopArrivalsSheet
    }
    LaunchedEffect(mapViewportInsets) {
        viewModel.dispatchAction(Action.MapViewportInsetsChanged(mapViewportInsets))
    }
    MapRealtimeVisibilityEffect { visibleAndStarted ->
        viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visibleAndStarted))
    }
    MapLocationRefreshEffect(state.location.permission is LocationPermissionState.Granted) {
        viewModel.dispatchAction(Action.RefreshLocation)
    }
    var platformCommand by remember { mutableStateOf<LocationPlatformCommand?>(null) }
    var connectionNoticeId by remember { mutableStateOf(0) }
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is NavigationEffect -> handleNavigation(effect)
            is SideEffect.HandleLocation -> platformCommand = effect.command
            SideEffect.ShowConnectionError -> connectionNoticeId++
        }
    }
    PlatformLocationEffect(
        command = platformCommand,
        onCommandConsumed = { platformCommand = null },
        onEvent = { viewModel.dispatchAction(Action.LocationEventReceived(it)) },
    )
    Content(state = state, connectionNoticeId = connectionNoticeId, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, connectionNoticeId: Int, onAction: (Action) -> Unit) {
    val planner = state.journeyPlanner
    val mapPick = planner.mapPick
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = planner.isOpen || planner.pin != null,
        onBackCompleted = {
            onAction(
                when {
                    mapPick != null -> JourneyPlannerAction.CancelMapPick
                    planner.pin != null -> JourneyPlannerAction.DismissPin
                    planner.showingResults -> JourneyPlannerAction.EditPoints
                    else -> JourneyPlannerAction.ClosePlanner
                },
            )
        },
    )
    val pointPicker = remember { MapPointPicker() }
    LaunchedEffect(mapPick) { pointPicker.cancel() }
    // Re-keyed on endpoint changes so a choice collapses its block and opens the missing one; taps toggle in between.
    var expandedSide by remember(planner.origin, planner.destination) {
        mutableStateOf(journeyExpandedSideFor(planner.origin, planner.destination))
    }
    val density = LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var mapTopPx by remember { mutableStateOf(0.0) }
    var stopSheetTopPx by remember(state.stopArrivalsSheet?.stopId) { mutableStateOf<Double?>(null) }
    var pickPanelHeightPx by remember(mapPick) { mutableIntStateOf(0) }
    val mapOverlayHeight = when {
        mapPick != null -> with(density) { pickPanelHeightPx.toDp() }
        planner.isOpen -> sheetHeight
        else -> 0.dp
    }
    val cameraInsets = MapViewportPolicy.insetsForLayout(
        mapHeightPx = mapHeightPx,
        bottomOverlayHeightPx = with(density) { mapOverlayHeight.toPx().toDouble() },
        mapTopPx = mapTopPx,
        modalTopPx = stopSheetTopPx,
        fallback = state.renderState?.camera?.viewportInsets ?: MapViewportInsets.None,
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        val panelMaxHeight = maxHeight * PLANNER_PANEL_HEIGHT_FRACTION
        val bottomModifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        Column(modifier = Modifier.fillMaxSize()) {
            val renderState = remember(state.renderState, planner, cameraInsets) {
                state.renderState?.withJourneyPlanner(planner)?.let { render ->
                    render.copy(camera = render.camera.copy(viewportInsets = cameraInsets))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MapArea(
                    renderState = renderState,
                    baseLayerState = state.baseLayerState,
                    location = state.location,
                    isMapPicking = mapPick != null,
                    pickRequest = pointPicker.request.takeIf { mapPick != null },
                    bottomOverlayHeight = mapOverlayHeight,
                    hasPin = planner.pin != null,
                    pinOnlySide = planner.missingSide(),
                    onMapEvent = { onAction(Action.MapEventReceived(it)) },
                    onMyLocationClick = { onAction(Action.MyLocationClicked) },
                    onZoomClick = { onAction(Action.ZoomClicked(it)) },
                    onCameraMovingChanged = { moving ->
                        if (moving) pointPicker.motionStarted() else pointPicker.motionSettled()
                    },
                    onPointResolved = { result ->
                        pointPicker.resolve(result, mapPick, mapHeightPx, pickPanelHeightPx)?.let {
                            onAction(JourneyPlannerAction.ConfirmMapPick(it))
                        }
                    },
                    onDismissPin = { onAction(JourneyPlannerAction.DismissPin) },
                    onRouteFromHere = { onAction(JourneyPlannerAction.RouteFromHere) },
                    onRouteHere = { onAction(JourneyPlannerAction.RouteHere) },
                    onCancelMapPick = { onAction(JourneyPlannerAction.CancelMapPick) },
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        mapHeightPx = it.size.height
                        mapTopPx = it.positionOnScreen().y.toDouble()
                    },
                )
                MapConnectionToast(
                    noticeId = connectionNoticeId,
                    // Clears the MapLibre logo and OSM attribution along the bottom edge.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = mapOverlayHeight + TransitSpacing.ExtraLarge + TransitSpacing.Medium),
                )
            }
            if (mapPick == null && !planner.isOpen) {
                MapControls(
                    vehicleLayerState = state.vehicleLayerState,
                    vehicleRoutes = state.vehicleRoutes,
                    routeGeometryLegends = state.routeGeometryLegends,
                    canChangeCity = state.canChangeCity,
                    routesAvailable = state.routesAvailable,
                    attribution = state.attribution,
                    onFocusRoute = { routeId -> onAction(Action.RouteGeometryFocused(routeId)) },
                    onRetryRoute = { routeId -> onAction(Action.RetryRouteGeometry(routeId)) },
                    onRemoveRoute = { routeId -> onAction(Action.RemoveRouteGeometry(routeId)) },
                    onChangeCity = { onAction(Action.ChangeCityClicked) },
                    onRoutes = { onAction(Action.RoutesClicked) },
                    modifier = bottomModifier.padding(TransitSpacing.Medium),
                )
            }
        }
        if (mapPick != null) {
            JourneyMapPickPanel(
                side = mapPick,
                canConfirm = state.renderState != null && pointPicker.canConfirm(mapHeightPx, pickPanelHeightPx),
                onConfirm = { pointPicker.start(mapPick, mapHeightPx, pickPanelHeightPx) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { pickPanelHeightPx = it.height }
                    .background(MaterialTheme.colorScheme.surface)
                    .then(bottomModifier)
                    .padding(TransitSpacing.Medium),
            )
        }
        if (planner.isOpen && mapPick == null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                sheetHeight = with(density) { it.height.toDp() }
            }) {
                JourneyPlannerSheet(
                    maxHeight = panelMaxHeight,
                    floating = planner.showingResults,
                    onClose = { onAction(JourneyPlannerAction.ClosePlanner) },
                ) { sheetModifier, headerModifier ->
                    JourneyPlannerPanel(
                        planner = planner,
                        expandedSide = expandedSide,
                        onToggleSide = { side -> expandedSide = side.takeIf { it != expandedSide } },
                        onClose = { onAction(JourneyPlannerAction.ClosePlanner) },
                        onEdit = { onAction(JourneyPlannerAction.EditPoints) },
                        onFind = { onAction(JourneyPlannerAction.FindRoute) },
                        onPickPoint = { side -> onAction(JourneyPlannerAction.StartMapPick(side)) },
                        onUseMyLocation = { side ->
                            if (side.endpointIn(planner) == JourneyEndpoint.CurrentLocation) expandedSide = null
                            onAction(side.myLocationAction())
                        },
                        onSwap = { onAction(JourneyPlannerAction.Swap) },
                        onClear = { onAction(JourneyPlannerAction.Clear) },
                        onSelectOption = { id -> onAction(JourneyPlannerAction.SelectOption(id)) },
                        onRetry = { onAction(JourneyPlannerAction.Retry) },
                        modifier = sheetModifier
                            .background(MaterialTheme.colorScheme.surface)
                            .then(bottomModifier)
                            .padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                        headerModifier = headerModifier,
                    )
                }
            }
        }
        state.stopArrivalsSheet?.let { sheet ->
            StopArrivalsSheet(
                sheet = sheet,
                onDismiss = { onAction(Action.StopArrivalsDismissed) },
                onRetry = { onAction(Action.RetryStopArrivals) },
                onTopPositioned = { stopSheetTopPx = it },
            )
        }
    }
}

@Composable
private fun MapControls(
    vehicleLayerState: VehicleLayerState,
    vehicleRoutes: List<VehicleRouteAccessibilityUi>,
    routeGeometryLegends: List<RouteGeometryLegendUi>,
    canChangeCity: Boolean,
    routesAvailable: Boolean,
    attribution: List<TransitAttribution>,
    onFocusRoute: (RouteId) -> Unit,
    onRetryRoute: (RouteId) -> Unit,
    onRemoveRoute: (RouteId) -> Unit,
    onChangeCity: () -> Unit,
    onRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        VehicleAccessibility(layerState = vehicleLayerState, routes = vehicleRoutes)
        Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
            RouteGeometryLegend(routes = routeGeometryLegends, onFocus = onFocusRoute, onRetry = onRetryRoute, onRemove = onRemoveRoute)
            Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                if (canChangeCity) {
                    OutlinedButton(
                        onClick = onChangeCity,
                        modifier = Modifier.weight(1f).testTag(AutomationId.MapChangeCity),
                    ) { Text(stringResource(Res.string.map_change_city_action)) }
                }
                if (routesAvailable) {
                    Button(
                        onClick = onRoutes,
                        modifier = Modifier.weight(1f).testTag(AutomationId.MapRoutes),
                    ) { Text(stringResource(Res.string.map_routes_action)) }
                }
            }
            CityAttribution(attribution)
        }
    }
}

/** Common navigation/composition visibility plus the host lifecycle is the realtime ownership gate. */
@Composable
private fun MapRealtimeVisibilityEffect(onVisibilityChanged: (Boolean) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnVisibilityChanged = rememberUpdatedState(onVisibilityChanged)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                -> currentOnVisibilityChanged.value(true)
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> currentOnVisibilityChanged.value(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        currentOnVisibilityChanged.value(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnVisibilityChanged.value(false)
        }
    }
}

/** Inside the planner with one endpoint set, a dropped pin can only fill the other one. */
private fun JourneyPlannerUi.missingSide(): JourneyPlanSide? = if (isOpen) journeyExpandedSideFor(origin, destination) else null

private fun JourneyPlanSide.endpointIn(planner: JourneyPlannerUi) =
    if (this == JourneyPlanSide.Origin) planner.origin else planner.destination

private fun JourneyPlanSide.myLocationAction(): JourneyPlannerAction = when (this) {
    JourneyPlanSide.Origin -> JourneyPlannerAction.SetOriginToMyLocation
    JourneyPlanSide.Destination -> JourneyPlannerAction.SetDestinationToMyLocation
}

private const val PLANNER_PANEL_HEIGHT_FRACTION = 0.55f

private fun String.toTransitLocale(): TransitLocale = when (this) {
    "ka" -> TransitLocale.Georgian
    "ru" -> TransitLocale.Russian
    else -> TransitLocale.English
}

@Composable
internal fun locationActionLabel(location: LocationState): String = stringResource(
    when (location.failure) {
        LocationFailure.TimedOut -> Res.string.location_failure_timed_out
        LocationFailure.InvalidFix -> Res.string.location_failure_invalid
        LocationFailure.Unavailable -> Res.string.location_failure_unavailable
        null -> when (val permission = location.permission) {
            LocationPermissionState.NotDetermined -> Res.string.location_action_enable
            is LocationPermissionState.Granted -> Res.string.map_my_location_action
            LocationPermissionState.Denied -> Res.string.location_action_retry
            LocationPermissionState.SettingsRequired -> Res.string.location_action_settings
            LocationPermissionState.ServicesDisabled -> Res.string.location_action_location_settings
            LocationPermissionState.Restricted -> Res.string.map_my_location_action
            is LocationPermissionState.Unavailable -> Res.string.location_action_retry
            is LocationPermissionState.Error -> Res.string.location_action_retry
        }
    },
)

internal fun locationActionEnabled(permission: LocationPermissionState): Boolean = when (permission) {
    LocationPermissionState.Restricted -> false
    is LocationPermissionState.Unavailable -> permission.canRetry
    is LocationPermissionState.Error -> permission.canRetry
    else -> true
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(
            state = ViewState(cityName = "Tbilisi", routesAvailable = true),
            connectionNoticeId = 0,
            onAction = {},
        )
    }
}
