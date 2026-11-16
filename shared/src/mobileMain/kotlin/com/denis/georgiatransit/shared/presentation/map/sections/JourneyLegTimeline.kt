package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denis.georgiatransit.shared.presentation.map.JourneyLegUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_arriving_in
import georgiatransit.shared.generated.resources.map_planner_meters
import georgiatransit.shared.generated.resources.map_planner_minutes
import georgiatransit.shared.generated.resources.map_planner_stops
import georgiatransit.shared.generated.resources.map_planner_transfer_wait
import georgiatransit.shared.generated.resources.map_planner_wait_estimate
import org.jetbrains.compose.resources.stringResource

/** The line and stops are decorative; the descriptions remain available to screen readers. */
@Composable
internal fun JourneyLegTimeline(legs: List<JourneyLegUi>, nearestEtas: List<Int>, modifier: Modifier = Modifier) {
    val firstRide = legs.indexOfFirst { it is JourneyLegUi.Ride }
    Column(modifier = modifier.fillMaxWidth()) {
        legs.forEachIndexed { index, leg ->
            val transferWait = leg is JourneyLegUi.Ride && leg.waitMinutes != null &&
                legs.take(index).any { it is JourneyLegUi.Ride }
            if (transferWait && leg is JourneyLegUi.Ride) JourneyTransferWait(leg)
            JourneyTimelineRow(
                leg, isFirst = index == 0, isLast = index == legs.lastIndex,
                showWait = !transferWait, nearestEtas = nearestEtas.takeIf { index == firstRide }.orEmpty(),
            )
        }
    }
}

@Composable
private fun JourneyTimelineRow(leg: JourneyLegUi, isFirst: Boolean, isLast: Boolean, showWait: Boolean, nearestEtas: List<Int>) {
    val lineColor = when (leg) {
        is JourneyLegUi.Walk -> MaterialTheme.colorScheme.onSurfaceVariant
        is JourneyLegUi.Ride -> Color(leg.route.backgroundArgb)
    }
    val surface = MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(AutomationId.MapPlannerLeg),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            val center = Offset(size.width / 2, size.height / 2)
            val stroke = 2.dp.toPx()
            val effect = if (leg is JourneyLegUi.Walk) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null
            if (!isFirst) drawLine(lineColor, Offset(center.x, 0f), center, stroke, pathEffect = effect)
            if (!isLast) drawLine(lineColor, center, Offset(center.x, size.height), stroke, pathEffect = effect)
            drawCircle(if (leg is JourneyLegUi.Walk) surface else lineColor, radius = 5.dp.toPx(), center = center)
            if (leg is JourneyLegUi.Walk) drawCircle(lineColor, radius = 5.dp.toPx(), center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
        when (leg) {
            is JourneyLegUi.Walk -> WalkLegIcon()
            is JourneyLegUi.Ride -> Icon(DirectionsBusIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = TransitSpacing.Small)) {
            when (leg) {
                is JourneyLegUi.Walk -> {
                    Text(walkRoleText(leg.role), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    leg.meters?.let { Text(stringResource(Res.string.map_planner_meters, it), style = MaterialTheme.typography.bodySmall) }
                }
                is JourneyLegUi.Ride -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                        JourneyRouteBadge(leg.route, withGlyph = false)
                        Text(stringResource(leg.route.mode.summaryResource(), leg.route.label), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (nearestEtas.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.map_planner_arriving_in, nearestEtas.joinToString(", ")),
                            modifier = Modifier.testTag(AutomationId.MapPlannerNearest),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = (MaterialTheme.typography.bodySmall.fontSize.value + 2f).sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    leg.stopCount?.let { Text(stringResource(Res.string.map_planner_stops, it), style = MaterialTheme.typography.bodySmall) }
                    if (nearestEtas.isEmpty()) {
                        leg.waitMinutes?.takeIf { showWait }?.let { wait ->
                            Text(
                                stringResource(Res.string.map_planner_wait_estimate, wait),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        val minutes = when (leg) { is JourneyLegUi.Walk -> leg.minutes; is JourneyLegUi.Ride -> leg.rideMinutes }
        Text("≈ ${stringResource(Res.string.map_planner_minutes, minutes)}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun JourneyTransferWait(leg: JourneyLegUi.Ride) {
    val wait = leg.waitMinutes ?: return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(AutomationId.MapPlannerLeg),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small), verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height), 2.dp.toPx())
            drawCircle(color, radius = 5.dp.toPx(), center = center)
        }
        Icon(SwapIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = TransitSpacing.Small)) {
            Text(stringResource(Res.string.map_planner_transfer_wait), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.map_planner_wait_estimate, wait),
                style = MaterialTheme.typography.bodySmall, color = color)
        }
        Text("≈ ${stringResource(Res.string.map_planner_minutes, wait)}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold)
    }
}
