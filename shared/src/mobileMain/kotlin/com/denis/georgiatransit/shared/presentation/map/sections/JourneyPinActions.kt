package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.MapScreenPoint
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_build_route
import georgiatransit.shared.generated.resources.map_planner_from_here
import georgiatransit.shared.generated.resources.map_planner_to_here
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The route pin with its "Plan a route" card, anchored to where the map was touched. The card sits
 * under the pin, flips above it near the bottom edge, and stays inside the map horizontally.
 */
@Composable
internal fun JourneyPinPopup(
    anchor: MapScreenPoint,
    onRouteFromHere: () -> Unit,
    onRouteHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            JourneyMapPickMarker()
            JourneyPinCard(onRouteFromHere = onRouteFromHere, onRouteHere = onRouteHere)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val marker = measurables[0].measure(loose)
        val edge = TransitSpacing.Medium.roundToPx()
        val card = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - edge * 2).coerceAtLeast(0)))
        val x = anchor.x.dp.roundToPx()
        val y = anchor.y.dp.roundToPx()
        val gap = CARD_GAP.roundToPx()
        val below = y + gap
        val cardY = if (below + card.height <= constraints.maxHeight - edge) below else y - PIN_HEIGHT.roundToPx() - gap - card.height
        val cardX = (x - card.width / 2).coerceIn(edge, (constraints.maxWidth - card.width - edge).coerceAtLeast(edge))
        layout(constraints.maxWidth, constraints.maxHeight) {
            marker.place(x - marker.width / 2, y - marker.height / 2)
            card.place(cardX, cardY.coerceAtLeast(0))
        }
    }
}

@Composable
private fun JourneyPinCard(onRouteFromHere: () -> Unit, onRouteHere: () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 340.dp).testTag(AutomationId.MapPlannerPin),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = TransitSpacing.Small,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Text(
                stringResource(Res.string.map_planner_build_route),
                modifier = Modifier.padding(horizontal = TransitSpacing.Small),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                Button(
                    onClick = onRouteFromHere,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(AutomationId.MapPlannerRouteFromHere),
                    shape = TransitShapes.Large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(stringResource(Res.string.map_planner_from_here), fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onRouteHere,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(AutomationId.MapPlannerRouteHere),
                    shape = TransitShapes.Large,
                ) { Text(stringResource(Res.string.map_planner_to_here), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private val CARD_GAP = 14.dp
private val PIN_HEIGHT = 56.dp

@Preview
@Composable
private fun JourneyPinPopupPreview() {
    GeorgiaTransitTheme {
        Box(Modifier.widthIn(max = 400.dp).heightIn(max = 400.dp)) {
            JourneyPinPopup(anchor = MapScreenPoint(180f, 120f), onRouteFromHere = {}, onRouteHere = {})
        }
    }
}
