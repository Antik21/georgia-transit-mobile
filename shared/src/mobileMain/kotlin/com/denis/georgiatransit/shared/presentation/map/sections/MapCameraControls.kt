package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.MapZoomDirection
import com.denis.georgiatransit.shared.presentation.map.PlatformMyLocationButton
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_zoom_in_action
import georgiatransit.shared.generated.resources.map_zoom_out_action
import org.jetbrains.compose.resources.stringResource

/** Bottom-end map controls: the location action stacked above zoom in and zoom out. */
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
        PlatformMyLocationButton(
            contentDescription = locationActionLabel,
            automationId = locationActionAutomationId,
            enabled = locationActionEnabled,
            onClick = onMyLocationClick,
            modifier = Modifier.size(ControlSize).testTag(locationActionAutomationId),
        )
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
private fun ZoomButton(
    direction: MapZoomDirection,
    label: String,
    automationId: String,
    onClick: (MapZoomDirection) -> Unit,
) {
    Surface(
        onClick = { onClick(direction) },
        modifier = Modifier
            .size(ControlSize)
            .testTag(automationId)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        shape = TransitShapes.Full,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = TransitSpacing.ExtraSmall,
    ) {
        val glyphColor = MaterialTheme.colorScheme.onSurface
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(GlyphSize)) {
                val stroke = GlyphStroke.toPx()
                drawLine(glyphColor, Offset(0f, center.y), Offset(size.width, center.y), stroke, StrokeCap.Round)
                if (direction == MapZoomDirection.In) {
                    drawLine(glyphColor, Offset(center.x, 0f), Offset(center.x, size.height), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

private val ControlSize = 48.dp
private val GlyphSize = 16.dp
private val GlyphStroke = 2.dp

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
