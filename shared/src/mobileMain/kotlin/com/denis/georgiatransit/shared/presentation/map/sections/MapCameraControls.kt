package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.MapZoomDirection
import com.denis.georgiatransit.shared.presentation.map.PlatformMyLocationButton
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_plan_trip_action
import georgiatransit.shared.generated.resources.map_planner_unavailable
import georgiatransit.shared.generated.resources.map_zoom_in_action
import georgiatransit.shared.generated.resources.map_zoom_out_action
import org.jetbrains.compose.resources.stringResource

/** The location action stacked above zoom in and zoom out. */
@Composable
internal fun MapCameraControls(
    locationActionLabel: String,
    locationActionAutomationId: String,
    locationActionEnabled: Boolean,
    onMyLocationClick: () -> Unit,
    onZoomClick: (MapZoomDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MapControlSurface {
            PlatformMyLocationButton(
                contentDescription = locationActionLabel,
                automationId = locationActionAutomationId,
                enabled = locationActionEnabled,
                onClick = onMyLocationClick,
                modifier = Modifier.size(MapControlSize).testTag(locationActionAutomationId),
            )
        }
        ZoomButton(
            direction = MapZoomDirection.In,
            label = stringResource(Res.string.map_zoom_in_action),
            automationId = AutomationId.MapZoomIn,
            onClick = onZoomClick,
        )
        ZoomButton(
            direction = MapZoomDirection.Out,
            label = stringResource(Res.string.map_zoom_out_action),
            automationId = AutomationId.MapZoomOut,
            onClick = onZoomClick,
        )
    }
}

@Composable
internal fun MapPlannerLauncher(
    available: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val mutedColor = colors.onSurface.copy(alpha = 0.38f)
    val label = stringResource(Res.string.map_plan_trip_action)
    MapControlButton(
        automationId = AutomationId.MapPlannerOpen,
        onClick = onClick,
        modifier = modifier,
        label = label.takeUnless { expanded },
        statusDescription = if (available) null else stringResource(Res.string.map_planner_unavailable),
    ) {
        Row(
            modifier = if (expanded) {
                Modifier.padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small)
            } else {
                Modifier
            },
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = JourneyPathIcon, contentDescription = null, tint = if (available) colors.primary else mutedColor)
            if (expanded) {
                Text(
                    text = label,
                    modifier = Modifier.testTag(AutomationId.MapPlannerOpenLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (available) colors.onSurface else mutedColor,
                )
            }
        }
    }
}

@Composable
private fun ZoomButton(
    direction: MapZoomDirection,
    label: String,
    automationId: String,
    onClick: (MapZoomDirection) -> Unit,
) {
    MapControlButton(label = label, automationId = automationId, onClick = { onClick(direction) }) {
        val glyphColor = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(GlyphSize)) {
            val stroke = GlyphStroke.toPx()
            drawLine(glyphColor, Offset(0f, center.y), Offset(size.width, center.y), stroke, StrokeCap.Round)
            if (direction == MapZoomDirection.In) {
                drawLine(glyphColor, Offset(center.x, 0f), Offset(center.x, size.height), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun MapControlButton(
    automationId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    statusDescription: String? = null,
    content: @Composable () -> Unit,
) {
    MapControlSurface(modifier) {
        Box(
            modifier = Modifier
                .testTag(automationId)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    if (label != null) contentDescription = label
                    if (statusDescription != null) stateDescription = statusDescription
                }
                .defaultMinSize(minWidth = MapControlSize, minHeight = MapControlSize),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun MapControlSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = TransitShapes.Full,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = TransitSpacing.ExtraSmall,
        content = content,
    )
}

internal val MapControlSize = 48.dp
internal val MapCameraControlsHeight = MapControlSize * 3 + TransitSpacing.Small * 2
private val GlyphSize = 16.dp
private val GlyphStroke = 2.dp

@Preview
@Composable
private fun MapPlannerLauncherPreview() {
    GeorgiaTransitTheme { MapPlannerLauncher(available = false, expanded = true, onClick = {}) }
}

@Preview
@Composable
private fun MapCameraControlsPreview() {
    GeorgiaTransitTheme {
        MapCameraControls(
            locationActionLabel = "My location",
            locationActionAutomationId = AutomationId.MapMyLocation,
            locationActionEnabled = true,
            onMyLocationClick = {},
            onZoomClick = {},
        )
    }
}
