package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.presentation.map.JourneyLabelUi
import com.denis.georgiatransit.shared.presentation.map.JourneyLegUi
import com.denis.georgiatransit.shared.presentation.map.JourneyOptionUi
import com.denis.georgiatransit.shared.presentation.map.JourneyRouteBadgeUi
import com.denis.georgiatransit.shared.presentation.map.JourneyTimingUi
import com.denis.georgiatransit.shared.presentation.map.JourneyWalkRole
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_arrival
import georgiatransit.shared.generated.resources.map_planner_label_fastest
import georgiatransit.shared.generated.resources.map_planner_label_least_walking
import georgiatransit.shared.generated.resources.map_planner_label_no_transfers
import georgiatransit.shared.generated.resources.map_planner_label_optimal
import georgiatransit.shared.generated.resources.map_planner_summary_bus
import georgiatransit.shared.generated.resources.map_planner_summary_ferry
import georgiatransit.shared.generated.resources.map_planner_summary_metro
import georgiatransit.shared.generated.resources.map_planner_summary_tram
import georgiatransit.shared.generated.resources.map_planner_summary_walk
import georgiatransit.shared.generated.resources.map_planner_then
import georgiatransit.shared.generated.resources.map_planner_total
import georgiatransit.shared.generated.resources.map_planner_walk_only
import georgiatransit.shared.generated.resources.map_planner_walk_access
import georgiatransit.shared.generated.resources.map_planner_walk_direct
import georgiatransit.shared.generated.resources.map_planner_walk_egress
import georgiatransit.shared.generated.resources.map_planner_walk_transfer
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** One planned option; [nearestEtas] is non-empty only for the selected option's soonest buses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyOptionCard(
    option: JourneyOptionUi,
    isSelected: Boolean,
    nearestEtas: List<Int>,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    stopNames: Map<StopId, String> = emptyMap(),
) {
    val walkOnly = option.legs.isNotEmpty() && option.legs.all { it is JourneyLegUi.Walk }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = TransitShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .testTag(AutomationId.MapPlannerOption)
                    .semantics(mergeDescendants = true) { selected = isSelected }
                    .then(if (isSelected) Modifier else Modifier.clickable(role = Role.Button, onClick = onSelect))
                    .padding(CardPadding),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall + 2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.map_planner_total, option.totalMinutes),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(Res.string.map_planner_arrival, option.arrivalAt.localClock()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!isSelected) Icon(ChevronDownIcon, contentDescription = null)
                }
                if (option.labels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                    ) {
                        option.labels.forEach { JourneyLabelPill(it) }
                    }
                }
                if (walkOnly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(WalkIcon, contentDescription = null, modifier = Modifier.size(GlyphSize))
                        Text(stringResource(Res.string.map_planner_walk_only), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (!isSelected) {
                    JourneyItinerary(option.legs)
                }
            }
            if (isSelected) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = CardPadding), color = MaterialTheme.colorScheme.outlineVariant)
                JourneyLegTimeline(
                    option.legs,
                    nearestEtas,
                    modifier = Modifier.padding(horizontal = CardPadding).padding(bottom = TransitSpacing.ExtraSmall),
                    stopNames = stopNames,
                )
            }
        }
    }
}

/** Label text sits inside the card, so it is part of its accessible name and never colour alone. */
@Composable
private fun JourneyLabelPill(label: JourneyLabelUi) {
    Surface(
        modifier = Modifier.testTag(AutomationId.MapPlannerLabel),
        shape = TransitShapes.Small,
        color = Color(label.backgroundArgb),
    ) {
        Text(
            labelText(label),
            modifier = Modifier.padding(horizontal = TransitSpacing.Small, vertical = 2.dp),
            color = Color(label.textArgb),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Walker → [bus 11] → walker, read aloud as one sentence instead of separate glyphs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneyItinerary(legs: List<JourneyLegUi>) {
    val spoken = legs.map { leg ->
        when (leg) {
            is JourneyLegUi.Walk -> stringResource(Res.string.map_planner_summary_walk)
            is JourneyLegUi.Ride -> stringResource(leg.route.mode.summaryResource(), leg.route.label)
        }
    }.joinToString(stringResource(Res.string.map_planner_then)).replaceFirstChar { it.uppercase() }
    FlowRow(
        modifier = Modifier.testTag(AutomationId.MapPlannerItinerary).clearAndSetSemantics { contentDescription = "$spoken." },
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        legs.forEachIndexed { index, leg ->
            if (index > 0) Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (leg) {
                is JourneyLegUi.Walk -> Icon(WalkIcon, contentDescription = null, modifier = Modifier.size(GlyphSize))
                is JourneyLegUi.Ride -> JourneyRouteBadge(leg.route, withGlyph = true)
            }
        }
    }
}

@Composable
internal fun WalkLegIcon() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        WalkDots(color)
        Icon(WalkIcon, contentDescription = null, modifier = Modifier.size(GlyphSize), tint = color)
        WalkDots(color)
    }
}

@Composable
private fun WalkDots(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = GlyphSize)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
        )
    }
}

@Composable
internal fun JourneyRouteBadge(route: JourneyRouteBadgeUi, withGlyph: Boolean) {
    Surface(shape = TransitShapes.ExtraSmall, color = Color(route.backgroundArgb)) {
        Row(
            modifier = Modifier.padding(horizontal = TransitSpacing.ExtraSmall + 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (withGlyph) {
                Icon(DirectionsBusIcon, contentDescription = null, modifier = Modifier.size(GlyphSize), tint = Color(route.textArgb))
            }
            Text(route.label, color = Color(route.textArgb), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun labelText(label: JourneyLabelUi): String = stringResource(
    when (label) {
        JourneyLabelUi.Optimal -> Res.string.map_planner_label_optimal
        JourneyLabelUi.Fastest -> Res.string.map_planner_label_fastest
        JourneyLabelUi.LeastWalking -> Res.string.map_planner_label_least_walking
        JourneyLabelUi.NoTransfers -> Res.string.map_planner_label_no_transfers
    },
)

@Composable
internal fun walkRoleText(role: JourneyWalkRole): String = stringResource(
    when (role) {
        JourneyWalkRole.Access -> Res.string.map_planner_walk_access
        JourneyWalkRole.Transfer -> Res.string.map_planner_walk_transfer
        JourneyWalkRole.Egress -> Res.string.map_planner_walk_egress
        JourneyWalkRole.Direct -> Res.string.map_planner_walk_direct
    },
)

internal fun TransitMode.summaryResource() = when (this) {
    TransitMode.Metro -> Res.string.map_planner_summary_metro
    TransitMode.Tram -> Res.string.map_planner_summary_tram
    TransitMode.Ferry -> Res.string.map_planner_summary_ferry
    TransitMode.Bus -> Res.string.map_planner_summary_bus
}

private fun Instant.localClock(): String = toLocalDateTime(TimeZone.currentSystemDefault()).let { time ->
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private val CardPadding = TransitSpacing.Small + TransitSpacing.ExtraSmall
private val GlyphSize = 16.dp

internal val PreviewJourneyOption = JourneyOptionUi(
    id = JourneyId("preview"),
    totalMinutes = 24,
    arrivalAt = Instant.fromEpochMilliseconds(1_763_037_000_000),
    transfers = 0,
    walkingMeters = 500,
    timing = JourneyTimingUi.Live,
    legs = listOf(
        JourneyLegUi.Walk(JourneyWalkRole.Access, meters = 300, minutes = 4, isApproximate = true),
        JourneyLegUi.Ride(
            JourneyRouteBadgeUi(RouteId("10a"), "10A", 0xFF1565C0, 0xFFFFFFFF),
            stopCount = 7, rideMinutes = 14, waitMinutes = 3, waitIsLive = true,
        ),
        JourneyLegUi.Walk(JourneyWalkRole.Egress, meters = 200, minutes = 3, isApproximate = true),
    ),
    labels = listOf(JourneyLabelUi.Optimal, JourneyLabelUi.NoTransfers),
)

@Preview
@Composable
private fun JourneyOptionCardPreview() {
    GeorgiaTransitTheme {
        JourneyOptionCard(PreviewJourneyOption, isSelected = true, nearestEtas = listOf(3, 11), onSelect = {})
    }
}
