package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_back
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_map_pick_body
import georgiatransit.shared.generated.resources.map_planner_map_pick_confirm
import georgiatransit.shared.generated.resources.map_planner_map_pick_screen
import georgiatransit.shared.generated.resources.map_planner_map_pick_title
import georgiatransit.shared.generated.resources.map_planner_origin
import org.jetbrains.compose.resources.stringResource

/** Pill with the way back to the planner panel while a point is chosen under the centred marker. */
@Composable
internal fun JourneyMapPickTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = TransitShapes.Large,
        shadowElevation = TransitSpacing.Small,
    ) {
        Row(
            modifier = Modifier.padding(end = TransitSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(AutomationId.MapPlannerMapPickBack)) {
                Icon(BackArrowIcon, contentDescription = stringResource(Res.string.map_planner_back))
            }
            Text(
                text = stringResource(Res.string.map_planner_map_pick_screen),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The tip of the pin marks the map centre, so the caller centres this box on the map. */
@Composable
internal fun JourneyMapPickMarker(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(MARKER_WIDTH, MARKER_HEIGHT * 2).testTag(AutomationId.MapPlannerMapPickMarker)) {
        val tip = Offset(size.width / 2f, size.height / 2f)
        drawOval(color.copy(alpha = 0.18f), topLeft = tip - Offset(20.dp.toPx(), 9.dp.toPx()), size = Size(40.dp.toPx(), 18.dp.toPx()))
        drawCircle(color, radius = 3.dp.toPx(), center = tip)
        val radius = 18.dp.toPx()
        val head = Offset(tip.x, tip.y - MARKER_HEIGHT.toPx() + radius + 2.dp.toPx())
        val pin = Path().apply {
            arcTo(Rect(head, radius), startAngleDegrees = 130f, sweepAngleDegrees = 280f, forceMoveTo = true)
            lineTo(tip.x, tip.y - 2.dp.toPx())
            close()
        }
        drawPath(pin, color)
        drawPath(pin, Color.White, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = 7.dp.toPx(), center = head)
    }
}

/** Bottom sheet of the map pick: the chosen side, a short hint, and the confirm action. */
@Composable
internal fun JourneyMapPickPanel(
    side: JourneyPlanSide,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag(AutomationId.MapPlannerMapPick),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), TransitShapes.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PlaceIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(TransitSpacing.Medium))
            Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                Text(
                    text = stringResource(
                        if (side == JourneyPlanSide.Origin) Res.string.map_planner_origin else Res.string.map_planner_destination,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.map_planner_map_pick_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.map_planner_map_pick_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(AutomationId.MapPlannerMapPickConfirm),
        ) {
            Icon(CheckIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(TransitSpacing.Small))
            Text(stringResource(Res.string.map_planner_map_pick_confirm), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val MARKER_WIDTH = 48.dp
private val MARKER_HEIGHT = 56.dp

@Preview
@Composable
private fun JourneyMapPickPreview() {
    GeorgiaTransitTheme {
        Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium)) {
            JourneyMapPickTopBar(onBack = {})
            JourneyMapPickMarker()
            JourneyMapPickPanel(
                side = JourneyPlanSide.Destination,
                canConfirm = true,
                onConfirm = {},
                modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(TransitSpacing.Medium),
            )
        }
    }
}
