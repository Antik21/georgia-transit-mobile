package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitColors
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.location_action_enable
import georgiatransit.shared.generated.resources.location_action_location_settings
import georgiatransit.shared.generated.resources.location_action_retry
import georgiatransit.shared.generated.resources.location_action_settings
import georgiatransit.shared.generated.resources.location_failure_invalid
import georgiatransit.shared.generated.resources.location_failure_timed_out
import georgiatransit.shared.generated.resources.location_failure_unavailable
import georgiatransit.shared.generated.resources.location_locating
import georgiatransit.shared.generated.resources.location_status_approximate
import georgiatransit.shared.generated.resources.location_status_denied
import georgiatransit.shared.generated.resources.location_status_error
import georgiatransit.shared.generated.resources.location_status_precise
import georgiatransit.shared.generated.resources.location_status_restricted
import georgiatransit.shared.generated.resources.location_status_services_disabled
import georgiatransit.shared.generated.resources.location_status_settings_required
import georgiatransit.shared.generated.resources.location_status_unavailable
import georgiatransit.shared.generated.resources.map_attribution_title
import georgiatransit.shared.generated.resources.map_change_city_action
import georgiatransit.shared.generated.resources.map_content_empty
import georgiatransit.shared.generated.resources.map_content_error
import georgiatransit.shared.generated.resources.map_content_loading
import georgiatransit.shared.generated.resources.map_content_offline
import georgiatransit.shared.generated.resources.map_content_stale
import georgiatransit.shared.generated.resources.map_content_unavailable
import georgiatransit.shared.generated.resources.map_cluster_stops
import georgiatransit.shared.generated.resources.map_my_location_action
import georgiatransit.shared.generated.resources.map_route_geometry_loading
import georgiatransit.shared.generated.resources.map_route_geometry_partial
import georgiatransit.shared.generated.resources.map_route_geometry_palette_overflow
import georgiatransit.shared.generated.resources.map_route_geometry_ready
import georgiatransit.shared.generated.resources.map_route_geometry_remove
import georgiatransit.shared.generated.resources.map_route_geometry_remove_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retry
import georgiatransit.shared.generated.resources.map_route_geometry_retry_accessibility
import georgiatransit.shared.generated.resources.map_route_geometry_retryable
import georgiatransit.shared.generated.resources.map_route_geometry_title
import georgiatransit.shared.generated.resources.map_route_geometry_unavailable
import georgiatransit.shared.generated.resources.map_preview_note
import georgiatransit.shared.generated.resources.map_routes_action
import georgiatransit.shared.generated.resources.map_nearby_stops_title
import georgiatransit.shared.generated.resources.map_stop_route_highlight_multiple
import georgiatransit.shared.generated.resources.map_stop_route_highlight_single
import georgiatransit.shared.generated.resources.map_retry_action
import georgiatransit.shared.generated.resources.map_selected_routes
import georgiatransit.shared.generated.resources.map_selected_stop
import georgiatransit.shared.generated.resources.map_stop_arrivals_arriving
import georgiatransit.shared.generated.resources.map_stop_arrivals_close
import georgiatransit.shared.generated.resources.map_stop_arrivals_empty
import georgiatransit.shared.generated.resources.map_stop_arrivals_error
import georgiatransit.shared.generated.resources.map_stop_arrivals_headsign_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_loading
import georgiatransit.shared.generated.resources.map_stop_arrivals_minutes
import georgiatransit.shared.generated.resources.map_stop_arrivals_offline
import georgiatransit.shared.generated.resources.map_stop_arrivals_page_source
import georgiatransit.shared.generated.resources.map_stop_arrivals_partial
import georgiatransit.shared.generated.resources.map_stop_arrivals_refreshing
import georgiatransit.shared.generated.resources.map_stop_arrivals_retry
import georgiatransit.shared.generated.resources.map_stop_arrivals_route_details_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_selected_route
import georgiatransit.shared.generated.resources.map_stop_arrivals_route_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_routes
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_aggregator
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_approximate
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_official
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_schedule
import georgiatransit.shared.generated.resources.map_stop_arrivals_stale
import georgiatransit.shared.generated.resources.map_stop_arrivals_stop_code
import georgiatransit.shared.generated.resources.map_stop_arrivals_stop_details_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_time_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_title
import georgiatransit.shared.generated.resources.map_walking_estimate_about
import georgiatransit.shared.generated.resources.map_walking_estimate_action
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_kilometers
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_less_than_fifty
import georgiatransit.shared.generated.resources.map_walking_estimate_distance_meters
import georgiatransit.shared.generated.resources.map_walking_estimate_duration_hours_minutes
import georgiatransit.shared.generated.resources.map_walking_estimate_duration_minutes
import georgiatransit.shared.generated.resources.map_walking_estimate_fallback_note
import georgiatransit.shared.generated.resources.map_walking_estimate_loading
import georgiatransit.shared.generated.resources.map_walking_estimate_source_approximate
import georgiatransit.shared.generated.resources.map_walking_estimate_source_routed
import georgiatransit.shared.generated.resources.map_walking_estimate_title
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_location
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_no_accurate_fix
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_permission_denied
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_permission_required
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_restricted
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_services_disabled
import georgiatransit.shared.generated.resources.map_walking_estimate_unavailable_settings
import georgiatransit.shared.generated.resources.map_stop_arrivals_unavailable
import georgiatransit.shared.generated.resources.map_title
import georgiatransit.shared.generated.resources.map_vehicle_status_live
import georgiatransit.shared.generated.resources.map_vehicle_status_loading
import georgiatransit.shared.generated.resources.map_vehicle_status_mixed
import georgiatransit.shared.generated.resources.map_vehicle_status_retryable
import georgiatransit.shared.generated.resources.map_vehicle_status_stale
import georgiatransit.shared.generated.resources.map_vehicle_status_unavailable
import georgiatransit.shared.generated.resources.map_vehicles_summary
import georgiatransit.shared.generated.resources.map_vehicle_route_summary
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.toPersistentList
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun MapScreen(viewModel: MapViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(language.toTransitLocale()))
    }
    MapRealtimeVisibilityEffect { visibleAndStarted ->
        viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visibleAndStarted))
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
    val isLocationActionable = locationActionEnabled(state.location.permission) && !state.location.isLocating
    val mapViewportInsets = if (state.stopArrivalsSheet == null) {
        MapViewportInsets.None
    } else {
        MapViewportInsets.StopArrivalsSheet
    }
    LaunchedEffect(mapViewportInsets) {
        onAction(Action.MapViewportInsetsChanged(mapViewportInsets))
    }
    Box(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                Text(stringResource(Res.string.map_title), style = MaterialTheme.typography.headlineSmall)
                Text(state.cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            val mapModifier = Modifier.fillMaxWidth().weight(1f)
            state.renderState?.let { renderState ->
                MapCanvas(
                    renderState = renderState,
                    contentState = state.contentState,
                    baseLayerState = state.baseLayerState,
                    locationActionLabel = locationActionLabel(state.location.permission),
                    locationActionAutomationId = if (state.location.permission == LocationPermissionState.SettingsRequired) {
                        AutomationId.MapLocationSettings
                    } else {
                        AutomationId.MapMyLocation
                    },
                    locationActionEnabled = isLocationActionable,
                    onMyLocationClick = { onAction(Action.MyLocationClicked) },
                    onMapEvent = { onAction(Action.MapEventReceived(it)) },
                    onRetry = { onAction(Action.RetryNearby) },
                    modifier = mapModifier,
                )
            } ?: MapContentPlaceholder(
                contentState = state.contentState,
                baseLayerState = state.baseLayerState,
                modifier = mapModifier,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                LocationStatus(state.location)
                VehicleAccessibility(
                    layerState = state.vehicleLayerState,
                    routes = state.vehicleRoutes,
                )
                NearbyStopsAccessibility(
                    stops = state.nearbyStops,
                    onStopSelected = { stop -> onAction(Action.StopSelected(stop.id, stop.sourceRevision)) },
                )
                state.selectedStop?.let { selectedStop ->
                    Text(
                        stringResource(Res.string.map_selected_stop, selectedStop.name),
                        modifier = Modifier
                            .testTag(AutomationId.MapSelectedStop)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.selectedRouteNames.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.map_selected_routes, state.selectedRouteNames.joinToString()),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                RouteGeometryLegend(
                    routes = state.routeGeometryLegends,
                    onFocus = { routeId -> onAction(Action.RouteGeometryFocused(routeId)) },
                    onRetry = { routeId -> onAction(Action.RetryRouteGeometry(routeId)) },
                    onRemove = { routeId -> onAction(Action.RemoveRouteGeometry(routeId)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                    OutlinedButton(
                        onClick = { onAction(Action.ChangeCityClicked) },
                        modifier = Modifier.weight(1f).testTag(AutomationId.MapChangeCity),
                    ) { Text(stringResource(Res.string.map_change_city_action)) }
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
        state.stopArrivalsSheet?.let { sheet ->
            StopArrivalsSheet(
                sheet = sheet,
                onDismiss = { onAction(Action.StopArrivalsDismissed) },
                onRetry = { onAction(Action.RetryStopArrivals) },
                onMyLocation = { onAction(Action.MyLocationClicked) },
                isLocationActionable = isLocationActionable,
            )
        }
    }
}

/** A common textual and interactive legend for the actual ordered route-polyline source. */
@Composable
private fun RouteGeometryLegend(
    routes: List<RouteGeometryLegendUi>,
    onFocus: (RouteId) -> Unit,
    onRetry: (RouteId) -> Unit,
    onRemove: (RouteId) -> Unit,
) {
    if (routes.isEmpty()) return
    Text(stringResource(Res.string.map_route_geometry_title), style = MaterialTheme.typography.labelLarge)
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapRouteGeometryLegend),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        items(routes, key = { it.routeId.value }) { route ->
            val foreground = Color(route.textColorArgb)
            val removeDescription = stringResource(Res.string.map_route_geometry_remove_accessibility, route.routeLabel)
            val retryDescription = stringResource(Res.string.map_route_geometry_retry_accessibility, route.routeLabel)
            Surface(
                shape = TransitShapes.Small,
                color = Color(route.colorArgb),
                tonalElevation = if (route.isFocused) TransitSpacing.ExtraSmall else 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(TransitSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                ) {
                    TextButton(
                        onClick = { onFocus(route.routeId) },
                        modifier = Modifier.testTag(AutomationId.MapRouteGeometryChip).semantics {
                            contentDescription = route.routeLabel
                            selected = route.isFocused
                        },
                    ) {
                        Text(route.routeLabel, color = foreground, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        routeGeometryStatusLabel(route),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = foreground,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        TextButton(
                            onClick = { onRemove(route.routeId) },
                            modifier = Modifier.testTag(AutomationId.MapRouteGeometryRemove).semantics {
                                contentDescription = removeDescription
                            },
                        ) {
                            Text(stringResource(Res.string.map_route_geometry_remove), color = foreground)
                        }
                        if (route.canRetry) {
                            TextButton(
                                onClick = { onRetry(route.routeId) },
                                modifier = Modifier.testTag(AutomationId.MapRouteGeometryRetry).semantics {
                                    contentDescription = retryDescription
                                },
                            ) {
                                Text(stringResource(Res.string.map_route_geometry_retry), color = foreground)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun routeGeometryStatusLabel(route: RouteGeometryLegendUi): String = when (route.state) {
    RouteGeometryLegendState.Loading -> stringResource(
        Res.string.map_route_geometry_loading,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Ready -> stringResource(
        Res.string.map_route_geometry_ready,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Partial -> stringResource(
        Res.string.map_route_geometry_partial,
        route.successfulDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Retryable -> stringResource(
        Res.string.map_route_geometry_retryable,
        route.failedDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.Unavailable -> stringResource(
        Res.string.map_route_geometry_unavailable,
        route.failedDirections,
        route.totalDirections,
    )
    RouteGeometryLegendState.PaletteOverflow -> stringResource(Res.string.map_route_geometry_palette_overflow)
}

private val RouteGeometryLegendUi.failedDirections: Int
    get() = (totalDirections - successfulDirections).coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopArrivalsSheet(
    sheet: StopArrivalsSheetUi,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onMyLocation: () -> Unit,
    isLocationActionable: Boolean,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            // ModalBottomSheet owns a separate Android semantics tree. Re-enable the bridge on
            // this in-sheet root so descendants retain their stable resource IDs on Android.
            modifier = Modifier.fillMaxWidth()
                .enableAutomationResourceIds()
                .testTag(AutomationId.MapStopArrivalsSheet)
                .padding(horizontal = TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.map_stop_arrivals_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sheet.stopName.ifBlank { stringResource(Res.string.map_stop_arrivals_stop_details_unavailable) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    sheet.stopCode?.let { code ->
                        Text(
                            stringResource(Res.string.map_stop_arrivals_stop_code, code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(AutomationId.MapStopArrivalsClose),
                ) {
                    Text(stringResource(Res.string.map_stop_arrivals_close))
                }
            }
            if (sheet.passingRoutes.isNotEmpty() || sheet.passingRouteShortNames.isNotEmpty()) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_routes),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (sheet.passingRoutes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        items(sheet.passingRoutes, key = { it.routeId.value }) { route ->
                            Surface(
                                shape = TransitShapes.Small,
                                color = Color(route.backgroundArgb),
                                tonalElevation = if (route.isSelected) TransitSpacing.ExtraSmall else 0.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(TransitSpacing.ExtraSmall),
                                    horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        route.routeLabel,
                                        modifier = Modifier.semantics { selected = route.isSelected },
                                        color = Color(route.textArgb),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    if (route.isSelected) {
                                        Text(
                                            stringResource(Res.string.map_stop_arrivals_selected_route),
                                            color = Color(route.textArgb),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        items(sheet.passingRouteShortNames, key = { it }) { shortName ->
                            Surface(shape = TransitShapes.Small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    shortName,
                                    modifier = Modifier.padding(TransitSpacing.ExtraSmall),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
            if (sheet.hasUnavailableRouteDetails) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_route_details_unavailable),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRouteDetailsUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WalkingEstimateSection(
                estimate = sheet.walkingEstimate,
                onMyLocation = onMyLocation,
                isLocationActionable = isLocationActionable,
            )
            if (sheet.isRefreshing) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_refreshing),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRefreshing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sheet.isStale) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_stale),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsStale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sheet.pageSource?.let { source ->
                Text(
                    stringResource(Res.string.map_stop_arrivals_page_source, arrivalSourceLabel(source)),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StopArrivalsStatus(sheet = sheet, onRetry = onRetry)
            if (sheet.rows.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .testTag(AutomationId.MapStopArrivalsRows),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                ) {
                    items(sheet.rows) { row -> StopArrivalRow(row) }
                }
            }
        }
    }
}

/** Separate, non-blocking walking feedback. Every duration is explicitly displayed as approximate. */
@Composable
private fun WalkingEstimateSection(
    estimate: WalkingEstimateUi,
    onMyLocation: () -> Unit,
    isLocationActionable: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapWalkingEstimate),
        shape = TransitShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(stringResource(Res.string.map_walking_estimate_title), style = MaterialTheme.typography.labelLarge)
            when (estimate) {
                WalkingEstimateUi.Loading -> Text(
                    stringResource(Res.string.map_walking_estimate_loading),
                    modifier = Modifier.testTag(AutomationId.MapWalkingEstimateLoading).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                is WalkingEstimateUi.Ready -> {
                    val formatted = formatWalkingEstimate(
                        distanceMeters = estimate.distanceMeters,
                        durationSeconds = estimate.durationSeconds,
                        locale = estimate.locale,
                    )
                    Text(
                        stringResource(Res.string.map_walking_estimate_about, walkingDurationLabel(formatted.duration)),
                        modifier = Modifier.testTag(AutomationId.MapWalkingEstimateReady).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(walkingDistanceLabel(formatted.distance), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            if (estimate.source == com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource.Routed) {
                                Res.string.map_walking_estimate_source_routed
                            } else {
                                Res.string.map_walking_estimate_source_approximate
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (estimate.source == com.denis.georgiatransit.shared.domain.model.WalkingEstimateSource.Approximate) {
                        Text(
                            stringResource(Res.string.map_walking_estimate_fallback_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is WalkingEstimateUi.Unavailable -> {
                    Text(
                        walkingUnavailableLabel(estimate.reason),
                        modifier = Modifier.testTag(AutomationId.MapWalkingEstimateUnavailable).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isLocationActionable) {
                        TextButton(onClick = onMyLocation, modifier = Modifier.testTag(AutomationId.MapWalkingEstimateAction)) {
                            Text(stringResource(Res.string.map_walking_estimate_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun walkingDistanceLabel(distance: WalkingDistanceFormat): String = when (distance) {
    WalkingDistanceFormat.UnderFiftyMeters -> stringResource(Res.string.map_walking_estimate_distance_less_than_fifty)
    is WalkingDistanceFormat.Meters -> stringResource(Res.string.map_walking_estimate_distance_meters, distance.value)
    is WalkingDistanceFormat.KilometersTenths -> stringResource(Res.string.map_walking_estimate_distance_kilometers, distance.value)
    is WalkingDistanceFormat.KilometersWhole -> stringResource(Res.string.map_walking_estimate_distance_kilometers, distance.value.toString())
}

@Composable
private fun walkingDurationLabel(duration: WalkingDurationFormat): String = when (duration) {
    is WalkingDurationFormat.Minutes -> stringResource(Res.string.map_walking_estimate_duration_minutes, duration.value)
    is WalkingDurationFormat.HoursMinutes -> stringResource(
        Res.string.map_walking_estimate_duration_hours_minutes,
        duration.hours,
        duration.minutes,
    )
}

@Composable
private fun walkingUnavailableLabel(reason: WalkingEstimateUnavailableReason): String = stringResource(
    when (reason) {
        WalkingEstimateUnavailableReason.PermissionRequired -> Res.string.map_walking_estimate_unavailable_permission_required
        WalkingEstimateUnavailableReason.PermissionDenied -> Res.string.map_walking_estimate_unavailable_permission_denied
        WalkingEstimateUnavailableReason.SettingsRequired -> Res.string.map_walking_estimate_unavailable_settings
        WalkingEstimateUnavailableReason.Restricted -> Res.string.map_walking_estimate_unavailable_restricted
        WalkingEstimateUnavailableReason.ServicesDisabled -> Res.string.map_walking_estimate_unavailable_services_disabled
        WalkingEstimateUnavailableReason.LocationUnavailable -> Res.string.map_walking_estimate_unavailable_location
        WalkingEstimateUnavailableReason.NoAccurateFix -> Res.string.map_walking_estimate_unavailable_no_accurate_fix
    },
)

/** Status is a polite summary only; repeated row/source refreshes are deliberately not live regions. */
@Composable
private fun StopArrivalsStatus(sheet: StopArrivalsSheetUi, onRetry: () -> Unit) {
    val status = when (sheet.state) {
        StopArrivalsSheetState.Loading -> stringResource(Res.string.map_stop_arrivals_loading) to AutomationId.MapStopArrivalsLoading
        StopArrivalsSheetState.NoArrivals -> stringResource(Res.string.map_stop_arrivals_empty) to AutomationId.MapStopArrivalsEmpty
        StopArrivalsSheetState.PartialData -> stringResource(Res.string.map_stop_arrivals_partial) to AutomationId.MapStopArrivalsPartial
        StopArrivalsSheetState.Offline -> stringResource(Res.string.map_stop_arrivals_offline) to AutomationId.MapStopArrivalsOffline
        StopArrivalsSheetState.UpstreamError -> stringResource(Res.string.map_stop_arrivals_error) to AutomationId.MapStopArrivalsError
        StopArrivalsSheetState.Unavailable -> stringResource(Res.string.map_stop_arrivals_unavailable) to AutomationId.MapStopArrivalsUnavailable
        StopArrivalsSheetState.Ready -> null
    } ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(status.second)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = TransitShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(status.first, style = MaterialTheme.typography.bodyMedium)
            if (sheet.state is StopArrivalsSheetState.Offline || sheet.state is StopArrivalsSheetState.UpstreamError) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRetry),
                ) { Text(stringResource(Res.string.map_stop_arrivals_retry)) }
            }
        }
    }
}

@Composable
private fun StopArrivalRow(row: StopArrivalRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapStopArrivalsRow),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            row.routeBadge?.let { badge ->
                Surface(shape = TransitShapes.Small, color = Color(badge.backgroundArgb)) {
                    Text(
                        badge.routeLabel,
                        modifier = Modifier.padding(horizontal = TransitSpacing.ExtraSmall),
                        color = Color(badge.textArgb),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } ?: Text(
                row.routeShortName ?: stringResource(Res.string.map_stop_arrivals_route_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                row.headsign.ifBlank { stringResource(Res.string.map_stop_arrivals_headsign_unavailable) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                arrivalSourceLabel(row.source),
                modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(arrivalTimeLabel(row.time), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun arrivalTimeLabel(time: StopArrivalTimeUi): String = when (time) {
    StopArrivalTimeUi.Arriving -> stringResource(Res.string.map_stop_arrivals_arriving)
    is StopArrivalTimeUi.Minutes -> stringResource(Res.string.map_stop_arrivals_minutes, time.value)
    StopArrivalTimeUi.Unavailable -> stringResource(Res.string.map_stop_arrivals_time_unavailable)
}

@Composable
private fun arrivalSourceLabel(source: ArrivalSourceUi): String = when (source) {
    ArrivalSourceUi.OfficialRealtime -> stringResource(Res.string.map_stop_arrivals_source_official)
    ArrivalSourceUi.AggregatorRealtime -> stringResource(Res.string.map_stop_arrivals_source_aggregator)
    ArrivalSourceUi.Schedule -> stringResource(Res.string.map_stop_arrivals_source_schedule)
    ArrivalSourceUi.Approximate -> stringResource(Res.string.map_stop_arrivals_source_approximate)
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

/** Retains the current typed status when a renderer state has not been supplied yet. */
@Composable
private fun MapContentPlaceholder(
    contentState: MapContentState,
    baseLayerState: MapBaseLayerState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(TransitColors.MapLand)) {
        MapStatusOverlays(
            contentState = contentState,
            baseLayerState = baseLayerState,
            modifier = Modifier.align(Alignment.TopCenter).padding(TransitSpacing.Medium),
        )
    }
}

@Composable
private fun MapCanvas(
    renderState: MapRenderState,
    contentState: MapContentState,
    baseLayerState: MapBaseLayerState,
    locationActionLabel: String,
    locationActionAutomationId: String,
    locationActionEnabled: Boolean,
    onMyLocationClick: () -> Unit,
    onMapEvent: (MapPlatformEvent) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localizedRenderState = renderState.withLocalizedClusterLabels()
    Box(modifier = modifier.background(TransitColors.MapLand)) {
        // AndroidView/UIKitView do not retain Compose test tags. Keep the automation-only
        // semantics node in common Compose so it remains visible without drawing or handling
        // pointer input above the native map.
        Box(
            modifier = Modifier.fillMaxSize().then(
                if (renderState.userLocation != null) Modifier.testTag(AutomationId.MapUserLocation) else Modifier,
            ),
        ) {
            PlatformMap(
                renderState = localizedRenderState,
                onEvent = onMapEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
        MapStatusOverlays(
            contentState = contentState,
            baseLayerState = baseLayerState,
            onRetry = onRetry,
            modifier = Modifier.align(Alignment.TopCenter).padding(TransitSpacing.Medium),
        )
        Button(
            onClick = onMyLocationClick,
            enabled = locationActionEnabled,
            modifier = Modifier.align(Alignment.BottomEnd).padding(TransitSpacing.Medium)
                .testTag(locationActionAutomationId),
        ) { Text(locationActionLabel) }
    }
}

@Composable
private fun MapStatusOverlays(
    contentState: MapContentState,
    baseLayerState: MapBaseLayerState,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BaseLayerOverlay(baseLayerState)
        MapContentOverlay(contentState = contentState, onRetry = onRetry)
    }
}

@Composable
private fun BaseLayerOverlay(baseLayerState: MapBaseLayerState) {
    val text = when (baseLayerState) {
        MapBaseLayerState.LocalPreview -> stringResource(Res.string.map_preview_note)
        MapBaseLayerState.BffStyle -> return
    }
    Surface(
        modifier = Modifier.testTag(AutomationId.MapLocalPreview),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = TransitShapes.Small,
        shadowElevation = TransitSpacing.ExtraSmall,
    ) {
        Text(
            text,
            modifier = Modifier.padding(TransitSpacing.Small),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The surface is deliberately compact: pan and zoom remain available around it. */
@Composable
private fun MapContentOverlay(
    contentState: MapContentState,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val textAndId = when (contentState) {
        MapContentState.Loading -> stringResource(Res.string.map_content_loading) to AutomationId.MapLoading
        MapContentState.Empty -> stringResource(Res.string.map_content_empty) to AutomationId.MapEmpty
        is MapContentState.RetryableError -> stringResource(Res.string.map_content_error) to AutomationId.MapError
        MapContentState.Unavailable -> stringResource(Res.string.map_content_unavailable) to AutomationId.MapUnavailable
        is MapContentState.Offline -> {
            val text = stringResource(
                if (contentState.isStale) Res.string.map_content_stale else Res.string.map_content_offline,
            )
            text to AutomationId.MapOffline
        }
        MapContentState.Ready -> return
    }
    Surface(
        modifier = modifier.testTag(textAndId.second),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = TransitShapes.Small,
        shadowElevation = TransitSpacing.ExtraSmall,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(textAndId.first, style = MaterialTheme.typography.labelLarge)
            if (contentState is MapContentState.RetryableError && onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag(AutomationId.MapRetry),
                ) {
                    Text(stringResource(Res.string.map_retry_action))
                }
            }
        }
    }
}

@Composable
private fun MapRenderState.withLocalizedClusterLabels(): MapRenderState = copy(
    stopClusters = stopClusters.map { cluster ->
        cluster.copy(
            accessibilityLabel = stringResource(Res.string.map_cluster_stops, cluster.stopCount),
        )
    }.toPersistentList(),
)

/** A concise textual equivalent of the badge layer; it intentionally does not replace stop state. */
@Composable
private fun VehicleAccessibility(
    layerState: VehicleLayerState,
    routes: List<VehicleRouteAccessibilityUi>,
) {
    if (layerState == VehicleLayerState.Hidden || routes.isEmpty()) return
    val status = vehicleStatusLabel(layerState)
    val routeCounts = routes.map { route ->
        stringResource(
            Res.string.map_vehicle_route_summary,
            route.routeLabel,
            route.vehicleCount,
            vehicleStatusLabel(route.layerState),
        )
    }.joinToString(separator = " · ")
    val summary = stringResource(Res.string.map_vehicles_summary, status, routeCounts)
    val summaryModifier = Modifier
        .fillMaxWidth()
        .testTag(AutomationId.MapVehicles)
        .semantics { liveRegion = LiveRegionMode.Polite }
    val hasLiveNonemptyRoute = routes.any { route ->
        route.layerState == VehicleLayerState.Live && route.vehicleCount > 0
    }
    if (hasLiveNonemptyRoute) {
        // A fixed parent semantics node lets automation prove rendered live geometry, while the
        // always-present child retains the localized accessibility summary and its existing ID.
        Box(modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapVehiclesLiveNonempty)) {
            Text(
                summary,
                modifier = summaryModifier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            summary,
            modifier = summaryModifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun vehicleStatusLabel(layerState: VehicleLayerState): String = when (layerState) {
    VehicleLayerState.Hidden -> ""
    VehicleLayerState.Loading -> stringResource(Res.string.map_vehicle_status_loading)
    VehicleLayerState.Live -> stringResource(Res.string.map_vehicle_status_live)
    VehicleLayerState.Stale -> stringResource(Res.string.map_vehicle_status_stale)
    VehicleLayerState.Retryable -> stringResource(Res.string.map_vehicle_status_retryable)
    VehicleLayerState.Unavailable -> stringResource(Res.string.map_vehicle_status_unavailable)
    VehicleLayerState.Mixed -> stringResource(Res.string.map_vehicle_status_mixed)
}

/** Screen-reader and switch-control equivalent of stop marker activation. */
@Composable
private fun NearbyStopsAccessibility(
    stops: List<NearbyStopUi>,
    onStopSelected: (NearbyStopUi) -> Unit,
) {
    if (stops.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapNearbyStops),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        Text(
            stringResource(Res.string.map_nearby_stops_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
            items(items = stops, key = { it.id.value }) { stop ->
                val routeSummary = stop.routeHighlight.accessibilitySummary()
                val label = listOf(stop.name, routeSummary).filter(String::isNotBlank).joinToString(separator = ". ")
                if (stop.isSelected) {
                    Button(
                        onClick = { onStopSelected(stop) },
                        modifier = Modifier.testTag(AutomationId.MapNearbyStop).semantics { contentDescription = label },
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { onStopSelected(stop) },
                        modifier = Modifier.testTag(AutomationId.MapNearbyStop).semantics { contentDescription = label },
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun StopRouteHighlightUi.accessibilitySummary(): String = when (style) {
    StopRouteHighlightStyle.None -> ""
    StopRouteHighlightStyle.SingleRoute -> stringResource(
        Res.string.map_stop_route_highlight_single,
        matchingRouteLabels.singleOrNull().orEmpty(),
    )
    StopRouteHighlightStyle.MultipleRoutes -> stringResource(
        Res.string.map_stop_route_highlight_multiple,
        matchingRouteCount,
    )
}

/** Link annotations retain accessible link semantics on Android and iOS Compose hosts. */
@Composable
private fun CityAttribution(attribution: List<TransitAttribution>) {
    if (attribution.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapAttribution),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        Text(
            stringResource(Res.string.map_attribution_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attribution.forEach { item ->
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url(item.url)) {
                        append(item.label.mapAttributionDisplayName(Locale.current.language))
                    }
                },
                modifier = Modifier.testTag(AutomationId.mapAttributionLink(item.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun LocalizedText.mapAttributionDisplayName(languageTag: String): String = when (languageTag) {
    "ka" -> ka
    "ru" -> ru
    else -> en
}.ifBlank { en.ifBlank { ru.ifBlank { ka } } }

private fun String.toTransitLocale(): TransitLocale = when (this) {
    "ka" -> TransitLocale.Georgian
    "ru" -> TransitLocale.Russian
    else -> TransitLocale.English
}

@Composable
private fun locationActionLabel(permission: LocationPermissionState): String = stringResource(
    when (permission) {
        LocationPermissionState.NotDetermined -> Res.string.location_action_enable
        is LocationPermissionState.Granted -> Res.string.map_my_location_action
        LocationPermissionState.Denied -> Res.string.location_action_retry
        LocationPermissionState.SettingsRequired -> Res.string.location_action_settings
        LocationPermissionState.ServicesDisabled -> Res.string.location_action_location_settings
        LocationPermissionState.Restricted -> Res.string.map_my_location_action
        is LocationPermissionState.Unavailable -> Res.string.location_action_retry
        is LocationPermissionState.Error -> Res.string.location_action_retry
    },
)

private fun locationActionEnabled(permission: LocationPermissionState): Boolean = when (permission) {
    LocationPermissionState.Restricted -> false
    is LocationPermissionState.Unavailable -> permission.canRetry
    is LocationPermissionState.Error -> permission.canRetry
    else -> true
}

@Composable
private fun LocationStatus(location: LocationState) {
    val text = when {
        location.isLocating -> stringResource(Res.string.location_locating)
        location.failure != null -> when (requireNotNull(location.failure)) {
            LocationFailure.TimedOut -> stringResource(Res.string.location_failure_timed_out)
            LocationFailure.InvalidFix -> stringResource(Res.string.location_failure_invalid)
            LocationFailure.Unavailable -> stringResource(Res.string.location_failure_unavailable)
        }
        else -> when (val permission = location.permission) {
            is LocationPermissionState.Granted -> when (permission.precision) {
                LocationPrecision.Precise -> stringResource(Res.string.location_status_precise)
                LocationPrecision.Approximate -> stringResource(Res.string.location_status_approximate)
            }
            LocationPermissionState.Denied -> stringResource(Res.string.location_status_denied)
            LocationPermissionState.SettingsRequired -> stringResource(Res.string.location_status_settings_required)
            LocationPermissionState.Restricted -> stringResource(Res.string.location_status_restricted)
            LocationPermissionState.ServicesDisabled -> stringResource(Res.string.location_status_services_disabled)
            is LocationPermissionState.Unavailable -> stringResource(Res.string.location_status_unavailable)
            is LocationPermissionState.Error -> stringResource(Res.string.location_status_error)
            LocationPermissionState.NotDetermined -> null
        }
    }
    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Preview
@Composable
private fun Preview() {
    GeorgiaTransitTheme {
        Content(
            ViewState(
                cityName = "Tbilisi",
                renderState = MapRenderState(
                    camera = MapCameraCommand(
                        center = GeoPoint(41.7151, 44.8271),
                        zoom = 13.0,
                        revision = 1,
                    ),
                ),
            ),
            onAction = {},
        )
    }
}
