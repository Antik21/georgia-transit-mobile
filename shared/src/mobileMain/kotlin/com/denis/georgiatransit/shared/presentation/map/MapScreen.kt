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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.map.sections.CityAttribution
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPinActions
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPlannerPanel
import com.denis.georgiatransit.shared.presentation.map.sections.MapCanvas
import com.denis.georgiatransit.shared.presentation.map.sections.MapContentPlaceholder
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
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is NavigationEffect -> handleNavigation(effect)
            is SideEffect.HandleLocation -> platformCommand = effect.command
        }
    }
    PlatformLocationEffect(
        command = platformCommand,
        onCommandConsumed = { platformCommand = null },
        onEvent = { viewModel.dispatchAction(Action.LocationEventReceived(it)) },
    )
    Content(state = state, onAction = viewModel::dispatchAction)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit) {
    val planner = state.journeyPlanner
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = planner.isOpen || planner.pin != null,
        onBackCompleted = {
            onAction(if (planner.pin != null) JourneyPlannerAction.DismissPin else JourneyPlannerAction.ClosePlanner)
        },
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        val panelMaxHeight = maxHeight * PLANNER_PANEL_HEIGHT_FRACTION
        Column(modifier = Modifier.fillMaxSize()) {
            MapArea(state = state, onAction = onAction, modifier = Modifier.fillMaxWidth().weight(1f))
            val bottomModifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            if (planner.isOpen) {
                JourneyPlannerPanel(
                    planner = planner,
                    onClose = { onAction(JourneyPlannerAction.ClosePlanner) },
                    onPickPoint = { side -> onAction(side.pickAction()) },
                    onUseMyLocation = { side -> onAction(side.myLocationAction()) },
                    onSwap = { onAction(JourneyPlannerAction.Swap) },
                    onClear = { onAction(JourneyPlannerAction.Clear) },
                    onSelectOption = { id -> onAction(JourneyPlannerAction.SelectOption(id)) },
                    onRetry = { onAction(JourneyPlannerAction.Retry) },
                    modifier = Modifier
                        .heightIn(max = panelMaxHeight)
                        .background(MaterialTheme.colorScheme.surface)
                        .then(bottomModifier)
                        .padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                )
            } else {
                MapControls(state = state, onAction = onAction, modifier = bottomModifier.padding(TransitSpacing.Medium))
            }
        }
        state.stopArrivalsSheet?.let { sheet ->
            StopArrivalsSheet(
                sheet = sheet,
                onDismiss = { onAction(Action.StopArrivalsDismissed) },
                onRetry = { onAction(Action.RetryStopArrivals) },
            )
        }
    }
}

@Composable
private fun MapArea(state: ViewState, onAction: (Action) -> Unit, modifier: Modifier = Modifier) {
    val planner = state.journeyPlanner
    Box(modifier = modifier) {
        state.renderState?.let { sessionRenderState ->
            val renderState = remember(sessionRenderState, planner) { sessionRenderState.withJourneyPlanner(planner) }
            MapCanvas(
                renderState = renderState,
                contentState = state.contentState,
                baseLayerState = state.baseLayerState,
                locationActionLabel = locationActionLabel(state.location),
                locationActionAutomationId = if (state.location.permission == LocationPermissionState.SettingsRequired) {
                    AutomationId.MapLocationSettings
                } else {
                    AutomationId.MapMyLocation
                },
                locationActionEnabled = locationActionEnabled(state.location.permission) && !state.location.isLocating,
                onMyLocationClick = { onAction(Action.MyLocationClicked) },
                onMapEvent = { onAction(Action.MapEventReceived(it)) },
                onRetry = { onAction(Action.RetryNearby) },
                modifier = Modifier.fillMaxSize(),
            )
        } ?: MapContentPlaceholder(
            contentState = state.contentState,
            baseLayerState = state.baseLayerState,
            modifier = Modifier.fillMaxSize(),
        )
        if (planner.pin != null) {
            JourneyPinActions(
                onRouteHere = { onAction(JourneyPlannerAction.RouteHere) },
                onRouteFromHere = { onAction(JourneyPlannerAction.RouteFromHere) },
                onDismiss = { onAction(JourneyPlannerAction.DismissPin) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    // Clears the my-location button and the attribution in the bottom-right corner.
                    .padding(start = TransitSpacing.Medium, end = TransitSpacing.Medium, bottom = PIN_ACTIONS_BOTTOM_OFFSET),
            )
        }
    }
}

@Composable
private fun MapControls(state: ViewState, onAction: (Action) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        VehicleAccessibility(
            layerState = state.vehicleLayerState,
            routes = state.vehicleRoutes,
        )
        Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
            RouteGeometryLegend(
                routes = state.routeGeometryLegends,
                onFocus = { routeId -> onAction(Action.RouteGeometryFocused(routeId)) },
                onRetry = { routeId -> onAction(Action.RetryRouteGeometry(routeId)) },
                onRemove = { routeId -> onAction(Action.RemoveRouteGeometry(routeId)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                if (state.canChangeCity) {
                    OutlinedButton(
                        onClick = { onAction(Action.ChangeCityClicked) },
                        modifier = Modifier.weight(1f).testTag(AutomationId.MapChangeCity),
                    ) { Text(stringResource(Res.string.map_change_city_action)) }
                }
                if (state.routesAvailable) {
                    Button(
                        onClick = { onAction(Action.RoutesClicked) },
                        modifier = Modifier.weight(1f).testTag(AutomationId.MapRoutes),
                    ) { Text(stringResource(Res.string.map_routes_action)) }
                }
            }
            CityAttribution(state.attribution)
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

private fun JourneyPlanSide.pickAction(): JourneyPlannerAction = when (this) {
    JourneyPlanSide.Origin -> JourneyPlannerAction.StartPickingOrigin
    JourneyPlanSide.Destination -> JourneyPlannerAction.StartPickingDestination
}

private fun JourneyPlanSide.myLocationAction(): JourneyPlannerAction = when (this) {
    JourneyPlanSide.Origin -> JourneyPlannerAction.SetOriginToMyLocation
    JourneyPlanSide.Destination -> JourneyPlannerAction.SetDestinationToMyLocation
}

private const val PLANNER_PANEL_HEIGHT_FRACTION = 0.55f
private val PIN_ACTIONS_BOTTOM_OFFSET = TransitSpacing.ExtraLarge + TransitSpacing.Medium * 2 + 48.dp

private fun String.toTransitLocale(): TransitLocale = when (this) {
    "ka" -> TransitLocale.Georgian
    "ru" -> TransitLocale.Russian
    else -> TransitLocale.English
}

@Composable
private fun locationActionLabel(location: LocationState): String = stringResource(
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

private fun locationActionEnabled(permission: LocationPermissionState): Boolean = when (permission) {
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
            state = ViewState(
                cityName = "Tbilisi",
                routesAvailable = true,
                contentState = MapContentState.Empty,
            ),
            onAction = {},
        )
    }
}
