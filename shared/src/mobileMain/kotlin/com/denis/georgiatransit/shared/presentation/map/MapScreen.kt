package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickMarkerAboveTip
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickMarkerBelowTip
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickPanel
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPlannerPanel
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPlannerSheet
import com.denis.georgiatransit.shared.presentation.map.sections.MapCameraControlsHeight
import com.denis.georgiatransit.shared.presentation.map.sections.MapControls
import com.denis.georgiatransit.shared.presentation.map.sections.MapSettingsSheet
import com.denis.georgiatransit.shared.presentation.map.sections.MapLocationPermissionSheet
import com.denis.georgiatransit.shared.presentation.map.sections.MapToast
import com.denis.georgiatransit.shared.presentation.map.sections.MapToastKind
import com.denis.georgiatransit.shared.presentation.map.sections.MapToastNotice
import com.denis.georgiatransit.shared.presentation.map.sections.StopArrivalsSheet
import com.denis.georgiatransit.shared.presentation.map.sections.rememberJourneyStopNames
import com.denis.georgiatransit.shared.presentation.map.sections.usesCompactPanel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.location_action_location_settings
import georgiatransit.shared.generated.resources.map_my_location_action
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onChangeLanguage: (() -> Unit)?,
    handleNavigation: suspend (NavigationEffect) -> Unit,
) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.fromLanguageTag(language)))
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
        viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visibleAndStarted, routeBoundsSupported = true))
    }
    MapLocationRefreshEffect(state.location.permission is LocationPermissionState.Granted) {
        viewModel.dispatchAction(Action.RefreshLocation)
    }
    var platformCommand by remember { mutableStateOf<LocationPlatformCommand?>(null) }
    var toastNotice by remember { mutableStateOf<MapToastNotice?>(null) }
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is NavigationEffect -> handleNavigation(effect)
            is SideEffect.HandleLocation -> platformCommand = effect.command
            SideEffect.ShowConnectionError -> toastNotice = MapToastNotice((toastNotice?.id ?: 0) + 1, MapToastKind.ConnectionError)
            SideEffect.ShowLocationError -> toastNotice = MapToastNotice((toastNotice?.id ?: 0) + 1, MapToastKind.LocationError)
        }
    }
    PlatformLocationEffect(
        command = platformCommand,
        onCommandConsumed = { platformCommand = null },
        onEvent = { viewModel.dispatchAction(Action.LocationEventReceived(it)) },
    )
    Content(
        state = state,
        toastNotice = toastNotice,
        onPlannerUnavailable = {
            toastNotice = MapToastNotice((toastNotice?.id ?: 0) + 1, MapToastKind.PlannerUnavailable)
        },
        onAction = viewModel::dispatchAction,
        onChangeLanguage = onChangeLanguage,
    )
}

@Composable
private fun Content(
    state: ViewState,
    toastNotice: MapToastNotice?,
    onPlannerUnavailable: () -> Unit,
    onAction: (Action) -> Unit,
    onChangeLanguage: (() -> Unit)?,
) {
    val planner = state.journeyPlanner
    val knownStopNames = rememberJourneyStopNames(planner, state.nearbyStops)
    val mapPick = planner.mapPick
    val showPlannerLauncher = !planner.isOpen && mapPick == null
    val settingsAvailable = state.canChangeCity || onChangeLanguage != null
    var settingsOpen by rememberSaveable(settingsAvailable) { mutableStateOf(false) }
    val permission = state.location.permission
    var permissionSheetOpen by remember(permission) { mutableStateOf(false) }
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
    var expandedSide by remember(planner.isOpen, planner.origin, planner.destination) {
        mutableStateOf(
            if (planner.origin == null && planner.destination == null) JourneyPlanSide.Origin
            else journeyExpandedSideFor(planner.origin, planner.destination),
        )
    }
    val density = LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }
    var mapWidthPx by remember { mutableIntStateOf(0) }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var mapTopPx by remember { mutableStateOf(0.0) }
    var stopSheetTopPx by remember(state.stopArrivalsSheet?.stopId) { mutableStateOf<Double?>(null) }
    var pickHeaderHeightPx by remember(mapPick) { mutableIntStateOf(0) }
    var pickPanelHeightPx by remember(mapPick) { mutableIntStateOf(0) }
    val pickTarget = mapPick?.let {
        with(density) {
            mapPickTarget(
                mapWidthPx = mapWidthPx,
                mapHeightPx = mapHeightPx,
                topOverlayHeightPx = pickHeaderHeightPx,
                bottomOverlayHeightPx = pickPanelHeightPx,
                markerAboveTipPx = JourneyMapPickMarkerAboveTip.toPx(),
                markerBelowTipPx = JourneyMapPickMarkerBelowTip.toPx(),
            )
        }
    }
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
        val resultsMaxHeight = if (planner.showingResults && density.fontScale >= 1.5f) {
            maxHeight * LARGE_TEXT_RESULTS_PANEL_HEIGHT_FRACTION
        } else {
            panelMaxHeight
        }
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
                    mapPickSide = mapPick,
                    pickTarget = pickTarget,
                    pickRequest = pointPicker.request.takeIf { mapPick != null },
                    onPickHeaderHeightChanged = { pickHeaderHeightPx = it },
                    bottomOverlayHeight = mapOverlayHeight,
                    hasPin = planner.pin != null,
                    pinOnlySide = planner.missingSide(),
                    onMapEvent = { onAction(Action.MapEventReceived(it)) },
                    onMyLocationClick = {
                        when (permission) {
                            LocationPermissionState.Denied, LocationPermissionState.SettingsRequired ->
                                permissionSheetOpen = true
                            else -> onAction(Action.MyLocationClicked)
                        }
                    },
                    onZoomClick = { onAction(Action.ZoomClicked(it)) },
                    journeyPlanningAvailable = state.journeyPlanningAvailable,
                    plannerNotice = toastNotice?.takeIf { it.kind == MapToastKind.PlannerUnavailable && !state.journeyPlanningAvailable },
                    onOpenPlanner = when {
                        !showPlannerLauncher -> null
                        !state.journeyPlanningAvailable -> onPlannerUnavailable
                        else -> ({ onAction(JourneyPlannerAction.OpenPlanner) })
                    },
                    onCameraMovingChanged = { moving ->
                        if (moving) pointPicker.motionStarted() else pointPicker.motionSettled()
                    },
                    onPointResolved = { result ->
                        pointPicker.resolve(result, mapPick, pickTarget)?.let {
                            onAction(JourneyPlannerAction.ConfirmMapPick(it))
                        }
                    },
                    onDismissPin = { onAction(JourneyPlannerAction.DismissPin) },
                    onRouteFromHere = { onAction(JourneyPlannerAction.RouteFromHere) },
                    onRouteHere = { onAction(JourneyPlannerAction.RouteHere) },
                    onCancelMapPick = { onAction(JourneyPlannerAction.CancelMapPick) },
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        mapWidthPx = it.size.width
                        mapHeightPx = it.size.height
                        mapTopPx = it.positionOnScreen().y.toDouble()
                    },
                )
                MapToast(
                    notice = toastNotice?.takeUnless { it.kind == MapToastKind.PlannerUnavailable },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = TransitSpacing.Medium,
                            end = TransitSpacing.Medium,
                            bottom = mapOverlayHeight + MapCameraControlsHeight + TransitSpacing.Large + TransitSpacing.Small,
                        ),
                )
            }
            if (mapPick == null && !planner.isOpen) {
                MapControls(
                    vehicleLayerState = state.vehicleLayerState,
                    vehicleRoutes = state.vehicleRoutes,
                    routeGeometryLegends = state.routeGeometryLegends,
                    settingsAvailable = settingsAvailable,
                    routesAvailable = state.routesAvailable,
                    schedulesAvailable = state.schedulesAvailable,
                    onFocusRoute = { routeId -> onAction(Action.RouteGeometryFocused(routeId)) },
                    onRetryRoute = { routeId -> onAction(Action.RetryRouteGeometry(routeId)) },
                    onRemoveRoute = { routeId -> onAction(Action.RemoveRouteGeometry(routeId)) },
                    onSettings = { settingsOpen = true },
                    onRoutes = { onAction(Action.RoutesClicked) },
                    onSchedules = { onAction(Action.SchedulesClicked) },
                    modifier = bottomModifier.padding(TransitSpacing.Medium),
                )
            }
        }
        if (mapPick != null) {
            JourneyMapPickPanel(
                side = mapPick,
                canConfirm = state.renderState != null && pointPicker.canConfirm(pickTarget),
                onConfirm = { pointPicker.start(mapPick, pickTarget) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { pickPanelHeightPx = it.height }
                    .heightIn(max = panelMaxHeight)
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
                    maxHeight = resultsMaxHeight,
                    floating = planner.showingResults,
                    compact = planner.status.usesCompactPanel,
                    onClose = { onAction(JourneyPlannerAction.ClosePlanner) },
                ) { sheetModifier, headerModifier ->
                    JourneyPlannerPanel(
                        planner = planner,
                        expandedSide = expandedSide,
                        onToggleSide = { side -> expandedSide = side.takeIf { it != expandedSide } },
                        onClose = { onAction(JourneyPlannerAction.ClosePlanner) },
                        onEdit = {
                            if (planner.status == JourneyPlannerStatus.Empty) expandedSide = JourneyPlanSide.Origin
                            onAction(JourneyPlannerAction.EditPoints)
                        },
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
                        stopNames = knownStopNames,
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
        if (settingsOpen && settingsAvailable) {
            MapSettingsSheet(
                onDismiss = { settingsOpen = false },
                onChangeCity = if (state.canChangeCity) {
                    {
                        settingsOpen = false
                        onAction(Action.ChangeCityClicked)
                    }
                } else null,
                onChangeLanguage = onChangeLanguage?.let { open ->
                    {
                        settingsOpen = false
                        open()
                    }
                },
            )
        }
        if (permissionSheetOpen) {
            MapLocationPermissionSheet(
                openSettings = permission == LocationPermissionState.SettingsRequired,
                onDismiss = { permissionSheetOpen = false },
                onConfirm = {
                    if (permissionSheetOpen) {
                        permissionSheetOpen = false
                        onAction(Action.MyLocationClicked)
                    }
                },
            )
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
private const val LARGE_TEXT_RESULTS_PANEL_HEIGHT_FRACTION = 0.68f

@Composable
internal fun locationActionLabel(permission: LocationPermissionState): String = stringResource(
    when (permission) {
        LocationPermissionState.ServicesDisabled -> Res.string.location_action_location_settings
        else -> Res.string.map_my_location_action
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
            toastNotice = null,
            onPlannerUnavailable = {},
            onAction = {},
            onChangeLanguage = {},
        )
    }
}
