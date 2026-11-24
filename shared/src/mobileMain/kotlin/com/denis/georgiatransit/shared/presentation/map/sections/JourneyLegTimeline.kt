package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.presentation.map.JourneyLegUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.displayName
import com.denis.georgiatransit.shared.presentation.ui.component.routeTitle
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_alight_at
import georgiatransit.shared.generated.resources.map_planner_board_at
import georgiatransit.shared.generated.resources.map_planner_eta_arriving
import georgiatransit.shared.generated.resources.map_planner_eta_arriving_next
import georgiatransit.shared.generated.resources.map_planner_eta_both_arriving
import georgiatransit.shared.generated.resources.map_planner_eta_in
import georgiatransit.shared.generated.resources.map_planner_eta_pair
import georgiatransit.shared.generated.resources.map_planner_meters
import georgiatransit.shared.generated.resources.map_planner_stops
import georgiatransit.shared.generated.resources.map_planner_total
import georgiatransit.shared.generated.resources.map_planner_transfer_wait
import georgiatransit.shared.generated.resources.map_planner_wait_estimate
import georgiatransit.shared.generated.resources.map_planner_wait_unknown
import org.jetbrains.compose.resources.stringResource

/** The line and stops are decorative; the descriptions remain available to screen readers. */
@Composable
internal fun JourneyLegTimeline(
    legs: List<JourneyLegUi>,
    nearestEtas: List<Int>,
    modifier: Modifier = Modifier,
    stopNames: Map<StopId, String> = emptyMap(),
) {
    val firstRide = legs.indexOfFirst { it is JourneyLegUi.Ride }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stackDuration = LocalDensity.current.fontScale >= 1.5f || maxWidth < 290.dp
        Column {
            legs.forEachIndexed { index, leg ->
                if (index > firstRide && leg is JourneyLegUi.Ride) {
                    JourneyTransferWait(leg.waitMinutes, stackDuration)
                }
                JourneyTimelineRow(
                    leg = leg,
                    isFirst = index == 0,
                    isLast = index == legs.lastIndex,
                    isFirstRide = index == firstRide,
                    nearestEtas = if (index == firstRide) nearestEtas else emptyList(),
                    stackDuration = stackDuration,
                    stopNames = stopNames,
                )
            }
        }
    }
}

@Composable
private fun JourneyTimelineRow(
    leg: JourneyLegUi,
    isFirst: Boolean,
    isLast: Boolean,
    isFirstRide: Boolean,
    nearestEtas: List<Int>,
    stackDuration: Boolean,
    stopNames: Map<StopId, String>,
) {
    val minutes = when (leg) {
        is JourneyLegUi.Walk -> leg.minutes
        is JourneyLegUi.Ride -> leg.rideMinutes
    }
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
                is JourneyLegUi.Walk -> Text(
                    walkRoleText(leg.role),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                is JourneyLegUi.Ride -> {
                    val routeTitle = leg.route.mode.routeTitle(leg.route.label)
                    Row(
                        modifier = Modifier.clearAndSetSemantics { contentDescription = routeTitle },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                    ) {
                        Text(leg.route.mode.displayName(), style = MaterialTheme.typography.bodyMedium)
                        JourneyRouteBadge(leg.route, withGlyph = false)
                    }
                }
            }
            if (stackDuration) JourneyStepDuration(minutes)
            when (leg) {
                is JourneyLegUi.Walk -> leg.meters?.let {
                    Text(stringResource(Res.string.map_planner_meters, it), style = MaterialTheme.typography.bodySmall)
                }
                is JourneyLegUi.Ride -> {
                    (leg.fromStopName ?: leg.fromStopId?.let(stopNames::get))?.let {
                        JourneyStepNote(stringResource(Res.string.map_planner_board_at, it))
                    }
                    if (isFirstRide) {
                        val liveEta = nearestEtas.firstOrNull()
                        val arrivalText = when {
                            liveEta != null -> nearestArrivalText(liveEta, nearestEtas.getOrNull(1))
                            leg.waitMinutes != null -> stringResource(Res.string.map_planner_wait_estimate, leg.waitMinutes)
                            else -> null
                        }
                        if (arrivalText == null) JourneyUnknownWait()
                        else Text(
                            arrivalText,
                            modifier = if (liveEta != null) Modifier.testTag(AutomationId.MapPlannerNearest) else Modifier,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    leg.stopCount?.let { Text(stringResource(Res.string.map_planner_stops, it), style = MaterialTheme.typography.bodySmall) }
                    (leg.toStopName ?: leg.toStopId?.let(stopNames::get))?.let {
                        JourneyStepNote(stringResource(Res.string.map_planner_alight_at, it))
                    }
                }
            }
        }
        if (!stackDuration) JourneyStepDuration(minutes)
    }
}

@Composable
private fun nearestArrivalText(first: Int, next: Int?): String = when {
    first == 0 && next == 0 -> stringResource(Res.string.map_planner_eta_both_arriving)
    first == 0 && next != null -> stringResource(Res.string.map_planner_eta_arriving_next, next)
    first == 0 -> stringResource(Res.string.map_planner_eta_arriving)
    next != null -> stringResource(Res.string.map_planner_eta_pair, first, next)
    else -> stringResource(Res.string.map_planner_eta_in, first)
}

@Composable
private fun JourneyStepDuration(minutes: Int?) {
    if (minutes == null) return
    Text(
        stringResource(Res.string.map_planner_total, minutes),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun JourneyStepNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun JourneyTransferWait(wait: Int?, stackDuration: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(AutomationId.MapPlannerLeg),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height), 2.dp.toPx())
            drawCircle(color, radius = 5.dp.toPx(), center = center)
        }
        Icon(SwapIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = TransitSpacing.Small)) {
            Text(
                stringResource(Res.string.map_planner_transfer_wait),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (wait == null) JourneyUnknownWait()
            else if (stackDuration) JourneyStepDuration(wait)
        }
        if (!stackDuration) JourneyStepDuration(wait)
    }
}

@Composable
private fun JourneyUnknownWait() {
    Text(
        stringResource(Res.string.map_planner_wait_unknown),
        modifier = Modifier.testTag(AutomationId.MapPlannerWaitUnknown),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
