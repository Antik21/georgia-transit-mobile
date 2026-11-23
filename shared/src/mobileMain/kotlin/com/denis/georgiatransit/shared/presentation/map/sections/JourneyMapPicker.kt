package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.MapPickTarget
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_map_pick_back_to_planner
import georgiatransit.shared.generated.resources.map_planner_map_pick_body
import georgiatransit.shared.generated.resources.map_planner_map_pick_confirm_point
import georgiatransit.shared.generated.resources.map_planner_origin
import org.jetbrains.compose.resources.stringResource

/** Pill with the way back to the planner panel while a point is chosen under the centred marker. */
@Composable
internal fun JourneyMapPickTopBar(side: JourneyPlanSide, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
                Icon(BackArrowIcon, contentDescription = stringResource(Res.string.map_planner_map_pick_back_to_planner))
            }
            Text(
                text = stringResource(
                    when (side) {
                        JourneyPlanSide.Origin -> Res.string.map_planner_origin
                        JourneyPlanSide.Destination -> Res.string.map_planner_destination
                    },
                ),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Draws the pin tip at the same fraction of the native viewport used for confirmation. */
@Composable
internal fun JourneyMapPickMarker(side: JourneyPlanSide, target: MapPickTarget, modifier: Modifier = Modifier) {
    val color = Color(side.markerArgb)
    val scale = target.markerScale
    val labelSize = with(LocalDensity.current) { (20.dp * scale).toSp() }
    val label = rememberTextMeasurer().measure(
        text = side.markerLabel,
        style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = labelSize),
    )
    Canvas(modifier = modifier.testTag(AutomationId.MapPlannerMapPickMarker)) {
        val tip = Offset((size.width * target.xFraction).toFloat(), (size.height * target.yFraction).toFloat())
        drawJourneyPin(tip, color, scale, label)
    }
}

/** The popup shows a neutral pin until the user assigns the point to A or B. */
@Composable
internal fun JourneyUnassignedMapMarker(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(48.dp, JourneyMapPickMarkerAboveTip * 2).testTag(AutomationId.MapPlannerMapPickMarker)) {
        drawJourneyPin(center, color, scale = 1f)
    }
}

private fun DrawScope.drawJourneyPin(tip: Offset, color: Color, scale: Float, label: TextLayoutResult? = null) {
    val halo = Size(40.dp.toPx() * scale, 18.dp.toPx() * scale)
    drawOval(color.copy(alpha = 0.18f), topLeft = tip - Offset(halo.width / 2, halo.height / 2), size = halo)
    drawCircle(color, radius = 3.dp.toPx() * scale, center = tip)
    val radius = 18.dp.toPx() * scale
    val head = Offset(tip.x, tip.y - JourneyMapPickMarkerAboveTip.toPx() * scale + radius + 2.dp.toPx() * scale)
    val pin = Path().apply {
        arcTo(Rect(head, radius), startAngleDegrees = 130f, sweepAngleDegrees = 280f, forceMoveTo = true)
        lineTo(tip.x, if (label == null) tip.y - 2.dp.toPx() else tip.y)
        close()
    }
    drawPath(pin, color)
    val join = if (label == null) StrokeJoin.Miter else StrokeJoin.Round
    drawPath(pin, Color.White, style = Stroke(width = 3.dp.toPx() * scale, join = join))
    if (label == null) {
        drawCircle(Color.White, radius = 7.dp.toPx(), center = head)
    } else {
        drawText(label, topLeft = head - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

/** Instruction and confirmation stay separate so a long translation can scroll. */
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
        Text(
            text = stringResource(Res.string.map_planner_map_pick_body),
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(AutomationId.MapPlannerMapPickConfirm),
        ) {
            Icon(CheckIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(TransitSpacing.Small))
            Text(
                stringResource(Res.string.map_planner_map_pick_confirm_point, side.markerLabel),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal val JourneyMapPickMarkerAboveTip = 56.dp
internal val JourneyMapPickMarkerBelowTip = 10.dp

@Preview
@Composable
private fun JourneyMapPickPreview() {
    GeorgiaTransitTheme {
        Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium)) {
            JourneyMapPickTopBar(side = JourneyPlanSide.Destination, onBack = {})
            Box(Modifier.fillMaxWidth().height(100.dp)) {
                JourneyMapPickMarker(
                    side = JourneyPlanSide.Destination,
                    target = MapPickTarget(1, 1, yFraction = 0.75, markerScale = 1f),
                    modifier = Modifier.matchParentSize(),
                )
            }
            JourneyMapPickPanel(
                side = JourneyPlanSide.Destination,
                canConfirm = true,
                onConfirm = {},
                modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(TransitSpacing.Medium),
            )
        }
    }
}
