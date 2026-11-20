package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickMarker
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyMapPickTopBar
import com.denis.georgiatransit.shared.presentation.map.sections.JourneyPinPopup
import com.denis.georgiatransit.shared.presentation.map.sections.MapCanvas
import com.denis.georgiatransit.shared.presentation.map.sections.MapContentPlaceholder
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing

/** The native map with planner overlays: the centred pick marker or the long-press pin card. */
@Composable
internal fun MapArea(
    renderState: MapRenderState?,
    baseLayerState: MapBaseLayerState,
    location: LocationState,
    isMapPicking: Boolean,
    pickRequest: MapPointRequest?,
    bottomOverlayHeight: Dp,
    hasPin: Boolean,
    pinOnlySide: JourneyPlanSide?,
    onMapEvent: (MapPlatformEvent) -> Unit,
    onMyLocationClick: () -> Unit,
    onZoomClick: (MapZoomDirection) -> Unit,
    onOpenPlanner: (() -> Unit)?,
    onCameraMovingChanged: (Boolean) -> Unit,
    onPointResolved: (MapPlatformEvent.MapPointResolved) -> Unit,
    onDismissPin: () -> Unit,
    onRouteFromHere: () -> Unit,
    onRouteHere: () -> Unit,
    onCancelMapPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinAnchor by remember { mutableStateOf<MapScreenPoint?>(null) }
    val currentHasPin by rememberUpdatedState(hasPin)
    Box(modifier = modifier) {
        renderState?.let {
            MapCanvas(
                renderState = remember(it, pickRequest) { it.copy(pointRequest = pickRequest) },
                baseLayerState = baseLayerState,
                bottomOverlayHeight = bottomOverlayHeight,
                locationActionLabel = locationActionLabel(location),
                locationActionAutomationId = if (location.permission == LocationPermissionState.SettingsRequired) {
                    AutomationId.MapLocationSettings
                } else {
                    AutomationId.MapMyLocation
                },
                locationActionEnabled = locationActionEnabled(location.permission) && !location.isLocating,
                onMyLocationClick = onMyLocationClick,
                onZoomClick = onZoomClick,
                onOpenPlanner = onOpenPlanner,
                onMapEvent = { event ->
                    when (event) {
                        MapPlatformEvent.CameraMoveStarted, MapPlatformEvent.UserCameraMoveStarted -> {
                            onCameraMovingChanged(true)
                            // The card is pinned to screen coordinates, so it cannot follow a moving map.
                            if (currentHasPin) onDismissPin()
                        }
                        is MapPlatformEvent.ViewportSettled -> onCameraMovingChanged(false)
                        is MapPlatformEvent.MapPointResolved -> onPointResolved(event)
                        is MapPlatformEvent.MapTapped -> if (currentHasPin) onDismissPin()
                        is MapPlatformEvent.MapLongPressed -> pinAnchor = event.screen
                        else -> Unit
                    }
                    if (event !is MapPlatformEvent.MapPointResolved) onMapEvent(event)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } ?: MapContentPlaceholder(baseLayerState = baseLayerState, modifier = Modifier.fillMaxSize())
        if (isMapPicking) {
            JourneyMapPickMarker(modifier = Modifier.align(Alignment.Center).offset(y = -bottomOverlayHeight / 2))
            JourneyMapPickTopBar(
                onBack = onCancelMapPick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(TransitSpacing.Medium),
            )
        } else {
            val visible = hasPin && pinAnchor != null
            // Keep the last shown anchor while the card fades out after dismissal.
            val shownAnchor = remember { arrayOfNulls<MapScreenPoint>(1) }
            if (visible) shownAnchor[0] = pinAnchor
            AnimatedVisibility(visible = visible, enter = fadeIn(tween(PIN_FADE_MILLIS)), exit = fadeOut(tween(PIN_FADE_MILLIS))) {
                val cardScale by transition.animateFloat(
                    transitionSpec = { tween(PIN_SCALE_MILLIS, easing = FastOutSlowInEasing) },
                ) { if (it == EnterExitState.Visible) 1f else PIN_CARD_START_SCALE }
                shownAnchor[0]?.let { anchor ->
                    JourneyPinPopup(
                        anchor = anchor,
                        onlySide = pinOnlySide,
                        onRouteFromHere = onRouteFromHere,
                        onRouteHere = onRouteHere,
                        cardScale = { cardScale },
                    )
                }
            }
        }
    }
}

private const val PIN_FADE_MILLIS = 180
private const val PIN_SCALE_MILLIS = 220
private const val PIN_CARD_START_SCALE = 0.8f
