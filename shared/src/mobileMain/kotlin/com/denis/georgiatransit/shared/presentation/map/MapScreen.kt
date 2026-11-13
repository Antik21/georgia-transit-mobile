package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.map.sections.CityAttribution
import com.denis.georgiatransit.shared.presentation.map.sections.MapCanvas
import com.denis.georgiatransit.shared.presentation.map.sections.MapConnectionToast
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
    Box(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MapArea(state = state, onAction = onAction)
                MapConnectionToast(
                    noticeId = connectionNoticeId,
                    // Clears the MapLibre logo and OSM attribution along the bottom edge.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = TransitSpacing.ExtraLarge + TransitSpacing.Medium),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(TransitSpacing.Medium),
            ) {
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
private fun MapArea(state: ViewState, onAction: (Action) -> Unit) {
    val modifier = Modifier.fillMaxSize()
    state.renderState?.let { renderState ->
        MapCanvas(
            renderState = renderState,
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
            modifier = modifier,
        )
    } ?: MapContentPlaceholder(baseLayerState = state.baseLayerState, modifier = modifier)
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
            state = ViewState(cityName = "Tbilisi", routesAvailable = true),
            connectionNoticeId = 0,
            onAction = {},
        )
    }
}
