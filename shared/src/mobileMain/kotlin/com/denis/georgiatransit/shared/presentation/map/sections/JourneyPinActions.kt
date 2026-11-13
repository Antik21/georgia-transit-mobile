package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.MapScreenPoint
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_build_route
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_from_here
import georgiatransit.shared.generated.resources.map_planner_map_pick_confirm
import georgiatransit.shared.generated.resources.map_planner_origin
import georgiatransit.shared.generated.resources.map_planner_to_here
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The route pin with its "Plan a route" card, anchored to where the map was touched. The card sits
 * under the pin, flips above it near the bottom edge, and stays inside the map horizontally.
 * With [onlySide] set, only that endpoint is still missing, so a single "Choose" fills it.
 */
@Composable
internal fun JourneyPinPopup(
    anchor: MapScreenPoint,
    onlySide: JourneyPlanSide?,
    onRouteFromHere: () -> Unit,
    onRouteHere: () -> Unit,
    modifier: Modifier = Modifier,
    cardScale: () -> Float = { 1f },
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            JourneyMapPickMarker()
            if (onlySide == null) {
                JourneyPinCard(onRouteFromHere = onRouteFromHere, onRouteHere = onRouteHere)
            } else {
                JourneyPinSingleCard(
                    side = onlySide,
                    onChoose = if (onlySide == JourneyPlanSide.Origin) onRouteFromHere else onRouteHere,
                )
            }
            CardTail(pointsUp = true)
            CardTail(pointsUp = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val marker = measurables[0].measure(loose)
        val edge = TransitSpacing.Medium.roundToPx()
        val card = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - edge * 2).coerceAtLeast(0)))
        val tailUp = measurables[2].measure(loose)
        val tailDown = measurables[3].measure(loose)
        val x = anchor.x.dp.roundToPx()
        val y = anchor.y.dp.roundToPx()
        // The tail base tucks 1 px under the card so no seam shows between them.
        val below = y + tailUp.height - 1
        val fitsBelow = below + card.height <= constraints.maxHeight - edge
        val cardY = if (fitsBelow) below else y - PIN_HEIGHT.roundToPx() - tailDown.height + 1 - card.height
        val cardX = (x - card.width / 2).coerceIn(edge, (constraints.maxWidth - card.width - edge).coerceAtLeast(edge))
        layout(constraints.maxWidth, constraints.maxHeight) {
            marker.place(x - marker.width / 2, y - marker.height / 2)
            // Grows out of the tail tip, which is where the anchor sits horizontally.
            val pivot = TransformOrigin(((x - cardX).toFloat() / card.width).coerceIn(0f, 1f), if (fitsBelow) 0f else 1f)
            card.placeWithLayer(cardX, cardY.coerceAtLeast(0)) {
                val scale = cardScale()
                scaleX = scale
                scaleY = scale
                transformOrigin = pivot
            }
            if (fitsBelow) {
                tailUp.place(x - tailUp.width / 2, y)
            } else {
                tailDown.place(x - tailDown.width / 2, cardY + card.height - 1)
            }
        }
    }
}

@Composable
private fun JourneyPinCard(onRouteFromHere: () -> Unit, onRouteHere: () -> Unit) {
    Surface(
        modifier = Modifier.width(CARD_WIDTH).testTag(AutomationId.MapPlannerPin),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(CARD_RADIUS),
        shadowElevation = TransitSpacing.Small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag(AutomationId.MapPlannerRouteFromHere),
                    shape = TransitShapes.Large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(stringResource(Res.string.map_planner_from_here), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onRouteHere,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag(AutomationId.MapPlannerRouteHere),
                    shape = TransitShapes.Large,
                ) { Text(stringResource(Res.string.map_planner_to_here), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun JourneyPinSingleCard(side: JourneyPlanSide, onChoose: () -> Unit) {
    Surface(
        modifier = Modifier.width(SINGLE_CARD_WIDTH).testTag(AutomationId.MapPlannerPin),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(CARD_RADIUS),
        shadowElevation = TransitSpacing.Small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (side == JourneyPlanSide.Origin) Res.string.map_planner_origin else Res.string.map_planner_destination),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = onChoose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(
                    if (side == JourneyPlanSide.Origin) AutomationId.MapPlannerRouteFromHere else AutomationId.MapPlannerRouteHere,
                ),
                shape = TransitShapes.Large,
            ) { Text(stringResource(Res.string.map_planner_map_pick_confirm), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CardTail(pointsUp: Boolean) {
    val color = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(TAIL_WIDTH, TAIL_HEIGHT)) {
        val tail = Path().apply {
            if (pointsUp) {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(tail, color)
    }
}

private val CARD_WIDTH = 264.dp
private val SINGLE_CARD_WIDTH = 200.dp
private val CARD_RADIUS = 18.dp
private val TAIL_WIDTH = 18.dp
private val TAIL_HEIGHT = 10.dp
private val PIN_HEIGHT = 56.dp

@Preview
@Composable
private fun JourneyPinPopupPreview() {
    GeorgiaTransitTheme {
        Box(Modifier.widthIn(max = 400.dp).heightIn(max = 400.dp)) {
            JourneyPinPopup(anchor = MapScreenPoint(180f, 120f), onlySide = null, onRouteFromHere = {}, onRouteHere = {})
        }
    }
}
