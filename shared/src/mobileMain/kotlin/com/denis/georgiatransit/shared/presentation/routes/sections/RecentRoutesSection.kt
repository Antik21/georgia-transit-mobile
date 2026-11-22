package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.map.sections.CheckIcon
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.route_history
import georgiatransit.shared.generated.resources.routes_recent
import georgiatransit.shared.generated.resources.routes_row_selected
import georgiatransit.shared.generated.resources.routes_row_unselected
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RecentRoutesSection(
    routes: List<RouteItemUiModel>,
    selectedIds: Set<RouteId>,
    isSelectionLimitReached: Boolean,
    onRouteToggled: (RouteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (routes.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Icon(
                painter = painterResource(Res.drawable.route_history),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(Res.string.routes_recent),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            items(routes, key = { it.id.value }) { route ->
                RecentRouteChip(
                    route = route,
                    selected = route.id in selectedIds,
                    selectionLimitReached = isSelectionLimitReached,
                    onClick = { onRouteToggled(route.id) },
                )
            }
        }
    }
}

@Composable
private fun RecentRouteChip(
    route: RouteItemUiModel,
    selected: Boolean,
    selectionLimitReached: Boolean,
    onClick: () -> Unit,
) {
    val enabled = selected || !selectionLimitReached
    val description = routeSelectionDescription(route, enabled)
    val selectionState = stringResource(
        if (selected) Res.string.routes_row_selected else Res.string.routes_row_unselected,
    )
    val colors = MaterialTheme.colorScheme
    val containerColor = when {
        selected -> Color(route.colorArgb)
        enabled -> colors.surfaceContainerHigh
        else -> colors.onSurface.copy(alpha = 0.12f)
    }
    val labelColor = when {
        selected -> Color(contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb))
        enabled -> colors.onSurface
        else -> colors.onSurface.copy(alpha = 0.38f)
    }

    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(AutomationId.RoutesRecentOption)
            .clip(TransitShapes.Full)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = selectionState
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                .background(containerColor, TransitShapes.Full)
                .padding(
                    horizontal = if (selected || route.shortName.length > 1) TransitSpacing.Small else TransitSpacing.ExtraSmall,
                    vertical = TransitSpacing.ExtraSmall,
                ),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(CheckIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = labelColor)
            }
            Text(
                text = route.shortName,
                modifier = Modifier.clearAndSetSemantics {},
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
