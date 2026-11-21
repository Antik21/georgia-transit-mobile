package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_collapse
import georgiatransit.shared.generated.resources.map_planner_expand
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/** Overlays the map; closing requires a separate drag from the collapsed anchor. */
@Composable
internal fun JourneyPlannerSheet(
    maxHeight: Dp,
    floating: Boolean,
    compact: Boolean,
    onClose: () -> Unit,
    content: @Composable (Modifier, Modifier) -> Unit,
) {
    if (!floating) {
        content(Modifier.heightIn(max = maxHeight), Modifier)
        return
    }
    val density = LocalDensity.current
    val maxPx = with(density) { maxHeight.toPx() }
    val bottomInset = WindowInsets.safeDrawing.getBottom(density)
    var headerPx by remember { mutableIntStateOf(0) }
    var compactHeightPx by remember { mutableIntStateOf(0) }
    val expandedPx = if (compact && compactHeightPx > 0) minOf(compactHeightPx.toFloat(), maxPx) else maxPx
    val minPx = with(density) { headerPx + (TransitSpacing.Small * 2).toPx() + bottomInset }
        .coerceIn(0f, expandedPx)
    val range = expandedPx - minPx
    var collapsed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var beganCollapsed by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) dragOffset.coerceIn(0f, range) else if (collapsed) range else 0f,
        animationSpec = if (dragging) snap() else tween(220),
    )
    val closeDistance = with(density) { 48.dp.toPx() }
    val toggleLabel = stringResource(if (collapsed) Res.string.map_planner_expand else Res.string.map_planner_collapse)

    fun beginDrag() {
        if (!dragging) {
            beganCollapsed = collapsed
            dragDistance = 0f
            dragOffset = offset
            dragging = true
        }
    }
    fun moveDrag(delta: Float) {
        beginDrag()
        dragDistance += delta
        dragOffset = (dragOffset + delta).coerceIn(0f, range)
    }
    fun finishDrag() {
        if (!dragging) return
        if (beganCollapsed && dragDistance > closeDistance) {
            dragging = false
            onClose()
            return
        }
        collapsed = when {
            dragDistance > closeDistance -> true
            dragDistance < -closeDistance -> false
            else -> beganCollapsed
        }
        dragging = false
    }

    val connection = remember(range) {
        JourneyPlannerNestedScrollConnection({ dragOffset }, ::moveDrag, ::finishDrag)
    }
    val dragState = rememberDraggableState { delta -> moveDrag(delta) }
    val headerModifier = Modifier
        // A constrained header shrinks with the sheet. Only measure its natural height when expanded;
        // using a constrained measurement feeds the shrinking height back into the collapsed anchor.
        .onSizeChanged { if (!collapsed && !dragging && offset < 1f && it.height > 0) headerPx = it.height }
        .draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStarted = { beginDrag() },
            onDragStopped = { finishDrag() },
        )
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = toggleLabel,
        ) { collapsed = !collapsed }
    val heightModifier = if (compact) {
        Modifier.clipToBounds()
            .layout { measurable, constraints ->
                // Keep measuring the full panel so collapsing it never changes the expanded height.
                val panel = measurable.measure(constraints)
                val maxOffset = (panel.height - minPx).coerceAtLeast(0f)
                val visibleHeight = constraints.constrainHeight(panel.height - offset.coerceAtMost(maxOffset).roundToInt())
                layout(panel.width, visibleHeight) { panel.placeRelative(0, 0) }
            }
            .heightIn(max = maxHeight)
            .onSizeChanged { compactHeightPx = it.height }
    } else {
        Modifier.height(with(density) { (expandedPx - offset).coerceAtLeast(0f).toDp() })
    }
    content(heightModifier.nestedScroll(connection), headerModifier)
}

/** Once the sheet takes a body drag, it also receives reverse movement until fully expanded. */
internal class JourneyPlannerNestedScrollConnection(
    private val sheetOffset: () -> Float,
    private val moveSheet: (Float) -> Unit,
    private val finishSheetDrag: () -> Unit,
) : NestedScrollConnection {
    private var dragging = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!dragging || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        val consumedY = available.y.coerceAtLeast(-sheetOffset().coerceAtLeast(0f))
        if (consumedY == 0f) return Offset.Zero
        moveSheet(consumedY)
        return Offset(0f, consumedY)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        dragging = true
        moveSheet(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (dragging) {
            dragging = false
            finishSheetDrag()
        }
        return Velocity.Zero
    }
}
