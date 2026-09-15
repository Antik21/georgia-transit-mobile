package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import georgiatransit.shared.generated.resources.map_openstreetmap_attribution
import georgiatransit.shared.generated.resources.map_retry_action
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
            val mapModifier = Modifier.fillMaxWidth().weight(1f)
            state.renderState?.let { renderState ->
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
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(TransitSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                VehicleAccessibility(
                    layerState = state.vehicleLayerState,
                    routes = state.vehicleRoutes,
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

/** Compact route chips for the actual ordered route-polyline source. */
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
            val background = Color(route.colorArgb)
            val removeDescription = stringResource(Res.string.map_route_geometry_remove_accessibility, route.routeLabel)
            val retryDescription = stringResource(Res.string.map_route_geometry_retry_accessibility, route.routeLabel)
            val status = routeGeometryStatusLabel(route)
            InputChip(
                selected = route.isFocused,
                onClick = { onFocus(route.routeId) },
                modifier = Modifier
                    .testTag(AutomationId.MapRouteGeometryChip)
                    .semantics {
                        contentDescription = "${route.routeLabel}. $status"
                        selected = route.isFocused
                        if (route.canRetry) {
                            customActions = listOf(
                                CustomAccessibilityAction(retryDescription) {
                                    onRetry(route.routeId)
                                    true
                                },
                            )
                        }
                    },
                label = {
                    Text(
                        text = route.routeLabel,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = DirectionsBusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .size(RouteGeometryActionSize)
                            .clip(CircleShape)
                            .testTag(AutomationId.MapRouteGeometryRemove)
                            .semantics(mergeDescendants = true) {
                                contentDescription = removeDescription
                            }
                            .clickable { onRemove(route.routeId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = InputChipDefaults.inputChipColors(
                    containerColor = background,
                    labelColor = foreground,
                    leadingIconColor = foreground,
                    trailingIconColor = foreground,
                    selectedContainerColor = background,
                    selectedLabelColor = foreground,
                    selectedLeadingIconColor = foreground,
                    selectedTrailingIconColor = foreground,
                ),
            )
            if (route.canRetry) {
                // A real target keeps both accessibility and automated recovery actionable.
                Box(
                    modifier = Modifier
                        .size(RouteGeometryActionSize)
                        .clip(CircleShape)
                        .testTag(AutomationId.MapRouteGeometryRetry)
                        .semantics(mergeDescendants = true) { contentDescription = retryDescription }
                        .clickable { onRetry(route.routeId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↻",
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
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

/** Material Symbols "directions_bus" geometry, used under the Apache-2.0 license. */
private val DirectionsBusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DirectionsBus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            fill = SolidColor(Color.Black),
            pathData = PathParser().parsePathString(
                "M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4S4 2.5 4 6v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zM17 17c-.83 0-1.5-.67-1.5-1.5S16.17 14 17 14s1.5.67 1.5 1.5S17.83 17 17 17zM18 12H6V6h12v6z",
            ).toNodes(),
        )
    }.build()
}

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
                            StopRouteChip(
                                label = route.routeLabel,
                                background = Color(route.backgroundArgb),
                                foreground = Color(route.textArgb),
                                isSelected = route.isSelected,
                            )
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        items(sheet.passingRouteShortNames, key = { it }) { shortName ->
                            StopRouteChip(
                                label = shortName,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                                isSelected = false,
                            )
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

/** Informational counterpart of the selected-route chip: no dismiss or click affordance. */
@Composable
private fun StopRouteChip(
    label: String,
    background: Color,
    foreground: Color,
    isSelected: Boolean,
) {
    val selectedLabel = stringResource(Res.string.map_stop_arrivals_selected_route)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = background,
        tonalElevation = if (isSelected) TransitSpacing.ExtraSmall else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(InputChipDefaults.Height)
                .padding(horizontal = TransitSpacing.Small)
                .semantics {
                    contentDescription = if (isSelected) "$label. $selectedLabel" else label
                    selected = isSelected
                },
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DirectionsBusIcon,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
                tint = foreground,
            )
            Text(
                text = label,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
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
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                )
                .padding(TransitSpacing.Medium),
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
    val accessibleStops = localizedRenderState.stops.filter { it.accessibilityLabel.isNotBlank() }
    val nearbyStopsLabel = stringResource(Res.string.map_nearby_stops_title)
    Box(modifier = modifier.background(TransitColors.MapLand)) {
        // AndroidView/UIKitView do not retain Compose test tags. Keep the automation-only
        // semantics node in common Compose so it remains visible without drawing or handling
        // pointer input above the native map.
        Box(
            modifier = Modifier.fillMaxSize().then(
                if (renderState.userLocation != null) Modifier.testTag(AutomationId.MapUserLocation) else Modifier,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (accessibleStops.isEmpty()) {
                        Modifier
                    } else {
                        Modifier.testTag(AutomationId.MapNearbyStops)
                    },
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().then(
                        if (accessibleStops.isEmpty()) {
                            Modifier
                        } else {
                            val firstStop = accessibleStops.first()
                            Modifier
                                .testTag(AutomationId.MapNearbyStop)
                                .semantics {
                                    contentDescription = nearbyStopsLabel
                                    onClick(label = firstStop.accessibilityLabel) {
                                        onMapEvent(
                                            MapPlatformEvent.StopTapped(
                                                stopId = firstStop.id,
                                                sourceRevision = localizedRenderState.stopSourceRevision,
                                            ),
                                        )
                                        true
                                    }
                                    customActions = accessibleStops.take(MAX_ACCESSIBILITY_CUSTOM_ACTIONS).map { stop ->
                                        CustomAccessibilityAction(stop.accessibilityLabel) {
                                            onMapEvent(
                                                MapPlatformEvent.StopTapped(
                                                    stopId = stop.id,
                                                    sourceRevision = localizedRenderState.stopSourceRevision,
                                                ),
                                            )
                                            true
                                        }
                                    }
                                }
                        },
                    ),
                ) {
                    PlatformMap(
                        renderState = localizedRenderState,
                        onEvent = onMapEvent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        MapStatusOverlays(
            contentState = contentState,
            baseLayerState = baseLayerState,
            onRetry = onRetry,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                )
                .padding(TransitSpacing.Medium),
        )
        PlatformMyLocationButton(
            contentDescription = locationActionLabel,
            automationId = locationActionAutomationId,
            enabled = locationActionEnabled,
            onClick = onMyLocationClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(TransitSpacing.Medium)
                .size(48.dp)
                .testTag(locationActionAutomationId),
        )
        if (baseLayerState == MapBaseLayerState.BffStyle) {
            OpenStreetMapAttribution(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(TransitSpacing.ExtraSmall),
            )
        }
    }
}

@Composable
private fun OpenStreetMapAttribution(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.map_openstreetmap_attribution)
    Surface(
        modifier = modifier.testTag(AutomationId.MapOpenStreetMapAttribution),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = TransitShapes.Small,
    ) {
        Text(
            text = buildAnnotatedString {
                withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) {
                    append(label)
                }
            },
            modifier = Modifier.padding(
                horizontal = TransitSpacing.Small,
                vertical = TransitSpacing.ExtraSmall,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
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

/**
 * Keeps a live, localized summary for assistive technology and UI automation without adding a
 * production-facing status row to the sheet.
 */
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
        .semantics {
            contentDescription = summary
            liveRegion = LiveRegionMode.Polite
        }
    val hasLiveNonemptyRoute = routes.any { route ->
        route.layerState == VehicleLayerState.Live && route.vehicleCount > 0
    }
    if (hasLiveNonemptyRoute) {
        // Transparent text retains real semantics bounds without restoring the visual status row.
        Box(modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapVehiclesLiveNonempty)) {
            Text(
                text = summary,
                modifier = summaryModifier,
                color = Color.Transparent,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Text(
            text = summary,
            modifier = summaryModifier,
            color = Color.Transparent,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
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

// Android exposes 32 accessibility action IDs total; the primary onClick consumes one.
private const val MAX_ACCESSIBILITY_CUSTOM_ACTIONS = 31
private val RouteGeometryActionSize = 48.dp

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
