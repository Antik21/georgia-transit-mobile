package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.MapBaseLayerState
import com.denis.georgiatransit.shared.presentation.map.MapContentState
import com.denis.georgiatransit.shared.presentation.map.MapPlatformEvent
import com.denis.georgiatransit.shared.presentation.map.MapRenderState
import com.denis.georgiatransit.shared.presentation.map.PlatformMap
import com.denis.georgiatransit.shared.presentation.map.PlatformMyLocationButton
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitColors
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_cluster_stops
import georgiatransit.shared.generated.resources.map_content_empty
import georgiatransit.shared.generated.resources.map_content_error
import georgiatransit.shared.generated.resources.map_content_loading
import georgiatransit.shared.generated.resources.map_content_offline
import georgiatransit.shared.generated.resources.map_content_stale
import georgiatransit.shared.generated.resources.map_content_unavailable
import georgiatransit.shared.generated.resources.map_nearby_stops_title
import georgiatransit.shared.generated.resources.map_preview_note
import georgiatransit.shared.generated.resources.map_retry_action
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.stringResource

/** Retains the current typed status when a renderer state has not been supplied yet. */
@Composable
internal fun MapContentPlaceholder(
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
internal fun MapCanvas(
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
                .padding(
                    start = TransitSpacing.Medium,
                    top = TransitSpacing.Medium,
                    end = TransitSpacing.Medium,
                    bottom = TransitSpacing.Medium + OpenStreetMapAttributionHeight,
                )
                .size(48.dp)
                .testTag(locationActionAutomationId),
        )
        if (baseLayerState == MapBaseLayerState.BffStyle) {
            OpenStreetMapAttribution(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TransitSpacing.ExtraSmall),
            )
        }
    }
}

// Reserves room above the compact copyright label for the location action.
private val OpenStreetMapAttributionHeight = 28.dp

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

// Android exposes 32 accessibility action IDs total; the primary onClick consumes one.
private const val MAX_ACCESSIBILITY_CUSTOM_ACTIONS = 31
