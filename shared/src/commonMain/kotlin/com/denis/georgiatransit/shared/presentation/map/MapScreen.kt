package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.PlatformLocationEffect
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
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
import georgiatransit.shared.generated.resources.map_my_location_action
import georgiatransit.shared.generated.resources.map_preview_note
import georgiatransit.shared.generated.resources.map_routes_action
import georgiatransit.shared.generated.resources.map_selected_routes
import georgiatransit.shared.generated.resources.map_title
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun MapScreen(viewModel: MapViewModel, handleNavigation: suspend (NavigationEffect) -> Unit) {
    val state by viewModel.collectAsState()
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
    Column(modifier = Modifier.fillMaxSize().testTag(AutomationId.MapScreen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Text(stringResource(Res.string.map_title), style = MaterialTheme.typography.headlineSmall)
            Text(state.cityName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        state.renderState?.let { renderState ->
            MapCanvas(
                renderState = renderState,
                contentState = state.contentState,
                locationActionLabel = locationActionLabel(state.location.permission),
                locationActionAutomationId = if (state.location.permission == LocationPermissionState.SettingsRequired) {
                    AutomationId.MapLocationSettings
                } else {
                    AutomationId.MapMyLocation
                },
                locationActionEnabled = locationActionEnabled(state.location.permission) && !state.location.isLocating,
                onMyLocationClick = { onAction(Action.MyLocationClicked) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            LocationStatus(state.location)
            if (state.selectedRouteNames.isNotEmpty()) {
                Text(
                    stringResource(Res.string.map_selected_routes, state.selectedRouteNames.joinToString()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                OutlinedButton(
                    onClick = { onAction(Action.ChangeCityClicked) },
                    modifier = Modifier.weight(1f).testTag(AutomationId.MapChangeCity),
                ) { Text(stringResource(Res.string.map_change_city_action)) }
                Button(
                    onClick = { onAction(Action.RoutesClicked) },
                    modifier = Modifier.weight(1f).testTag(AutomationId.MapRoutes),
                ) { Text(stringResource(Res.string.map_routes_action)) }
            }
            CityAttribution(state.attribution)
        }
    }
}

@Composable
private fun MapCanvas(
    renderState: MapRenderState,
    contentState: MapContentState,
    locationActionLabel: String,
    locationActionAutomationId: String,
    locationActionEnabled: Boolean,
    onMyLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(TransitColors.MapLand)) {
        // AndroidView/UIKitView do not retain Compose test tags. Keep the automation-only
        // semantics node in common Compose so it remains visible without drawing or handling
        // pointer input above the native map.
        Box(
            modifier = Modifier.fillMaxSize().then(
                if (renderState.userLocation != null) Modifier.testTag(AutomationId.MapUserLocation) else Modifier,
            ),
        ) {
            PlatformMap(renderState = renderState, modifier = Modifier.fillMaxSize())
        }
        MapContentOverlay(
            contentState = contentState,
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

/** The surface is deliberately compact: pan and zoom remain available around it. */
@Composable
private fun MapContentOverlay(
    contentState: MapContentState,
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
        MapContentState.LocalPreview -> stringResource(Res.string.map_preview_note) to AutomationId.MapLocalPreview
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
        }
    }
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
