package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.route_history
import georgiatransit.shared.generated.resources.routes_recent
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Row(
            modifier = Modifier.widthIn(max = 140.dp),
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        LazyRow(
            modifier = Modifier.weight(1f),
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
        modifier = Modifier.heightIn(min = 48.dp)
            .testTag(AutomationId.RoutesRecentOption)
            .clip(TransitShapes.Full)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                .background(containerColor, TransitShapes.Full)
                .padding(horizontal = if (route.shortName.length == 1) 0.dp else TransitSpacing.Small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = route.shortName,
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
