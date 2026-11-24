package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.denis.georgiatransit.shared.presentation.map.sections.CheckIcon
import com.denis.georgiatransit.shared.presentation.map.sections.ChevronDownIcon
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.schedule_filter_value
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal data class ScheduleOptionLabel(val title: String, val number: String? = null)

@Composable
internal fun <T> ScheduleDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> ScheduleOptionLabel,
    onSelected: (T) -> Unit,
    tag: String,
    optionTag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    val selectedLabel = optionLabel(selected)
    val value = selectedLabel.number?.let {
        stringResource(Res.string.schedule_filter_value, it, selectedLabel.title)
    } ?: selectedLabel.title
    val description = stringResource(Res.string.schedule_filter_value, label, value)
    Column(modifier = modifier) {
        Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                if (position.x.isFinite() && position.y.isFinite()) {
                    anchor = IntRect(
                        IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                        coordinates.size,
                    )
                }
            },
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = options.size > 1,
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = description },
            ) {
                ScheduleOptionText(selectedLabel, Modifier.weight(1f).clearAndSetSemantics {})
                Icon(ChevronDownIcon, contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
            }
            if (expanded && options.size > 1) {
                anchor?.let {
                    ScheduleOptionMenu(
                        anchor = it,
                        selectedIndex = options.indexOf(selected).coerceAtLeast(0),
                        options = options,
                        optionLabel = optionLabel,
                        onDismiss = { expanded = false },
                        onSelected = onSelected,
                        optionTag = optionTag,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ScheduleOptionMenu(
    anchor: IntRect,
    selectedIndex: Int,
    options: List<T>,
    optionLabel: (T) -> ScheduleOptionLabel,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
    optionTag: String,
) {
    val density = LocalDensity.current
    val gap = with(density) { TransitSpacing.ExtraSmall.roundToPx() }
    val preferredHeight = with(density) { 320.dp.roundToPx() }
    val insets = WindowInsets.safeDrawing
    val bottomLimit = LocalWindowInfo.current.containerSize.height - insets.getBottom(density)
    val below = bottomLimit - anchor.bottom - gap * 2
    val above = anchor.top - insets.getTop(density) - gap * 2
    val placeBelow = below >= preferredHeight || below >= above
    val height = minOf(preferredHeight, if (placeBelow) below else above)
    if (height <= 0 || anchor.width <= 0) return

    val position = remember(placeBelow, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                anchorBounds.left,
                if (placeBelow) anchorBounds.bottom + gap else anchorBounds.top - gap - popupContentSize.height,
            )
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = with(density) { Modifier.width(anchor.width.toDp()).heightIn(max = height.toDp()) },
            shape = TransitShapes.Small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
        ) {
            LazyColumn(
                modifier = Modifier.enableAutomationResourceIds(),
                state = remember(selectedIndex) { LazyListState(firstVisibleItemIndex = selectedIndex) },
                contentPadding = PaddingValues(vertical = TransitSpacing.ExtraSmall),
            ) {
                items(options.size) { index ->
                    val option = options[index]
                    val isSelected = index == selectedIndex
                    DropdownMenuItem(
                        text = { ScheduleOptionText(optionLabel(option)) },
                        trailingIcon = if (isSelected) {
                            { Icon(CheckIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            onDismiss()
                            if (!isSelected) onSelected(option)
                        },
                        modifier = Modifier.testTag(optionTag).semantics { selected = isSelected }
                            .then(
                                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                                else Modifier,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleOptionText(label: ScheduleOptionLabel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        label.number?.let {
            Text(it, modifier = Modifier.widthIn(max = 88.dp), fontWeight = FontWeight.SemiBold)
        }
        Text(label.title, modifier = Modifier.weight(1f))
    }
}
