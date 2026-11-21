package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.MapScreenPoint
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_plan_trip_action
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_from_here
import georgiatransit.shared.generated.resources.map_planner_map_pick_confirm
import georgiatransit.shared.generated.resources.map_planner_origin
import georgiatransit.shared.generated.resources.map_planner_to_here
import org.jetbrains.compose.resources.stringResource

/** Keeps the point anchored to the map and the card clear of the camera controls. */
@Composable
internal fun JourneyPinPopup(
    anchor: MapScreenPoint,
    onlySide: JourneyPlanSide?,
    onRouteFromHere: () -> Unit,
    onRouteHere: () -> Unit,
    modifier: Modifier = Modifier,
    bottomOverlayHeight: Dp = 0.dp,
    onCardPositioned: (Rect) -> Unit = {},
    cardScale: () -> Float = { 1f },
) {
    val safeInsets = WindowInsets.safeDrawing
    val cardModifier = Modifier.onGloballyPositioned { onCardPositioned(it.boundsInRoot()) }
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            JourneyUnassignedMapMarker()
            if (onlySide == null) {
                JourneyPinCard(onRouteFromHere, onRouteHere, modifier = cardModifier)
            } else {
                JourneyPinSingleCard(
                    side = onlySide,
                    onChoose = if (onlySide == JourneyPlanSide.Origin) onRouteFromHere else onRouteHere,
                    modifier = cardModifier,
                )
            }
            CardTail(pointsUp = true)
            CardTail(pointsUp = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val edge = TransitSpacing.Medium.roundToPx()
        val controlLane = (MapControlSize + TransitSpacing.Small).roundToPx()
        val isLtr = layoutDirection == LayoutDirection.Ltr
        val left = safeInsets.getLeft(this, layoutDirection) + edge + if (isLtr) 0 else controlLane
        val right = constraints.maxWidth - safeInsets.getRight(this, layoutDirection) - edge - if (isLtr) controlLane else 0
        val top = safeInsets.getTop(this) + edge
        val bottom = constraints.maxHeight - maxOf(safeInsets.getBottom(this), bottomOverlayHeight.roundToPx()) - edge
        val marker = measurables[0].measure(loose)
        val card = measurables[1].measure(
            loose.copy(maxWidth = (right - left).coerceAtLeast(0), maxHeight = (bottom - top).coerceAtLeast(0)),
        )
        val tailUp = measurables[2].measure(loose)
        val tailDown = measurables[3].measure(loose)
        val x = anchor.x.dp.roundToPx()
        val y = anchor.y.dp.roundToPx()
        // The tail base tucks 1 px under the card so no seam shows between them.
        val below = y + tailUp.height - 1
        val above = y - JourneyMapPickMarkerAboveTip.roundToPx() - tailDown.height + 1 - card.height
        val preferBelow = below + card.height <= bottom || (above < top && bottom - below >= y - top)
        val candidate = if (preferBelow) below else above
        val cardY = candidate.coerceIn(top, (bottom - card.height).coerceAtLeast(top))
        val cardX = (x - card.width / 2).coerceIn(left, (right - card.width).coerceAtLeast(left))
        // The tail meets the straight edge only when the card can stay anchored to the point.
        val tailInset = CARD_RADIUS.roundToPx() + (tailUp.width + 1) / 2
        val showTail = candidate == cardY && x in (cardX + tailInset)..(cardX + card.width - tailInset)
        layout(constraints.maxWidth, constraints.maxHeight) {
            marker.place(x - marker.width / 2, y - marker.height / 2)
            val pivot = TransformOrigin(
                ((x - cardX).toFloat() / card.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                if (preferBelow) 0f else 1f,
            )
            card.placeWithLayer(cardX, cardY) {
                val scale = cardScale()
                scaleX = scale
                scaleY = scale
                transformOrigin = pivot
            }
            if (showTail) {
                if (preferBelow) {
                    tailUp.place(x - tailUp.width / 2, y)
                } else {
                    tailDown.place(x - tailDown.width / 2, cardY + card.height - 1)
                }
            }
        }
    }
}

@Composable
private fun JourneyPinCard(
    onRouteFromHere: () -> Unit,
    onRouteHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fromHere = stringResource(Res.string.map_planner_from_here)
    val toHere = stringResource(Res.string.map_planner_to_here)
    PinCardSurface(CARD_WIDTH, modifier) {
        Text(
            stringResource(Res.string.map_plan_trip_action),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val labelStyle = MaterialTheme.typography.labelLarge
            val measurer = rememberTextMeasurer()
            val labelRoom = with(density) {
                ((maxWidth - TransitSpacing.Small) / 2 - PIN_ACTION_BADGE - PIN_ACTION_PADDING * 2 - TransitSpacing.Small)
                    .roundToPx()
            }
            val stackActions = density.fontScale >= 1.5f || listOf(fromHere, toHere).any {
                measurer.measure(it, style = labelStyle, softWrap = false).size.width > labelRoom
            }
            if (stackActions) {
                Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                    JourneyPinAction(JourneyPlanSide.Origin, fromHere, onRouteFromHere, Modifier.fillMaxWidth())
                    JourneyPinAction(JourneyPlanSide.Destination, toHere, onRouteHere, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                    JourneyPinAction(JourneyPlanSide.Origin, fromHere, onRouteFromHere, Modifier.weight(1f))
                    JourneyPinAction(JourneyPlanSide.Destination, toHere, onRouteHere, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun JourneyPinAction(side: JourneyPlanSide, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MapControlSize).testTag(
            if (side == JourneyPlanSide.Origin) AutomationId.MapPlannerRouteFromHere else AutomationId.MapPlannerRouteHere,
        ),
        shape = TransitShapes.Large,
        contentPadding = PIN_BUTTON_PADDING,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JourneyEndpointBadge(side, minimumSize = PIN_ACTION_BADGE)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun JourneyPinSingleCard(side: JourneyPlanSide, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    PinCardSurface(SINGLE_CARD_WIDTH, modifier) {
        Text(
            stringResource(if (side == JourneyPlanSide.Origin) Res.string.map_planner_origin else Res.string.map_planner_destination),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Button(
            onClick = onChoose,
            modifier = Modifier.fillMaxWidth().heightIn(min = MapControlSize).testTag(
                if (side == JourneyPlanSide.Origin) AutomationId.MapPlannerRouteFromHere else AutomationId.MapPlannerRouteHere,
            ),
            shape = TransitShapes.Large,
            contentPadding = PIN_BUTTON_PADDING,
        ) {
            Text(stringResource(Res.string.map_planner_map_pick_confirm), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PinCardSurface(width: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.width(width).testTag(AutomationId.MapPlannerPin),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(CARD_RADIUS),
        shadowElevation = TransitSpacing.Small,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(TransitSpacing.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) { content() }
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

private val PIN_ACTION_PADDING = TransitSpacing.Small
private val PIN_BUTTON_PADDING = PaddingValues(PIN_ACTION_PADDING)
private val PIN_ACTION_BADGE = 24.dp
private val CARD_WIDTH = 264.dp
private val SINGLE_CARD_WIDTH = 200.dp
private val CARD_RADIUS = 18.dp
private val TAIL_WIDTH = 18.dp
private val TAIL_HEIGHT = 10.dp

@Preview
@Composable
private fun JourneyPinPopupPreview() {
    GeorgiaTransitTheme {
        Box(Modifier.widthIn(max = 400.dp).heightIn(max = 400.dp)) {
            JourneyPinPopup(anchor = MapScreenPoint(180f, 120f), onlySide = null, onRouteFromHere = {}, onRouteHere = {})
        }
    }
}
