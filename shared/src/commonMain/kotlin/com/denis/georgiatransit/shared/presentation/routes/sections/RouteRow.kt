package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_mode_bus
import georgiatransit.shared.generated.resources.routes_mode_ferry
import georgiatransit.shared.generated.resources.routes_mode_metro
import georgiatransit.shared.generated.resources.routes_mode_tram
import georgiatransit.shared.generated.resources.routes_row_description
import georgiatransit.shared.generated.resources.routes_row_description_short
import georgiatransit.shared.generated.resources.routes_row_description_with_direction
import georgiatransit.shared.generated.resources.routes_row_limit_reached
import georgiatransit.shared.generated.resources.routes_row_selected
import georgiatransit.shared.generated.resources.routes_row_unselected
import georgiatransit.shared.generated.resources.routes_title_bus
import georgiatransit.shared.generated.resources.routes_title_ferry
import georgiatransit.shared.generated.resources.routes_title_metro
import georgiatransit.shared.generated.resources.routes_title_tram
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RouteRow(
    route: RouteItemUiModel,
    selected: Boolean,
    selectionLimitReached: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToggleEnabled = selected || !selectionLimitReached
    val mode = route.mode.displayName()
    val name = route.name.takeUnless { it.equals(route.shortName, ignoreCase = true) }.orEmpty()
    val direction = route.direction.takeUnless {
        it.equals(name, ignoreCase = true) || it.equals(route.shortName, ignoreCase = true)
    }.orEmpty()
    val selectionState = stringResource(
        if (selected) Res.string.routes_row_selected else Res.string.routes_row_unselected,
    )
    val description = when {
        name.isNotBlank() && direction.isNotBlank() ->
            stringResource(Res.string.routes_row_description_with_direction, route.shortName, name, direction, mode)
        name.isNotBlank() || direction.isNotBlank() ->
            stringResource(Res.string.routes_row_description, route.shortName, name.ifBlank { direction }, mode)
        else -> stringResource(Res.string.routes_row_description_short, route.shortName, mode)
    }
    val accessibilityDescription = if (isToggleEnabled) {
        description
    } else {
        "$description. ${stringResource(Res.string.routes_row_limit_reached)}"
    }
    val colors = MaterialTheme.colorScheme
    val rowColor = if (selected) lerp(colors.surfaceContainerLow, colors.primary, 0.14f) else colors.surfaceContainerLow
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = TransitShapes.Large,
        colors = CardDefaults.cardColors(containerColor = rowColor, contentColor = colors.onSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .testTag(AutomationId.RoutesOption)
                .selectable(
                    selected = selected,
                    enabled = isToggleEnabled,
                    role = Role.Checkbox,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityDescription
                    stateDescription = selectionState
                }
                .padding(horizontal = 12.dp, vertical = TransitSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(route.colorArgb), TransitShapes.Full)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    route.shortName,
                    color = Color(contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    route.mode.routeTitle(route.shortName),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = direction.ifBlank { name }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The row owns the one checkable semantics target; this is visual state only.
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = isToggleEnabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun TransitMode.displayName(): String = stringResource(
    when (this) {
        TransitMode.Bus -> Res.string.routes_mode_bus
        TransitMode.Metro -> Res.string.routes_mode_metro
        TransitMode.Tram -> Res.string.routes_mode_tram
        TransitMode.Ferry -> Res.string.routes_mode_ferry
    },
)

@Composable
private fun TransitMode.routeTitle(number: String): String = stringResource(
    when (this) {
        TransitMode.Bus -> Res.string.routes_title_bus
        TransitMode.Metro -> Res.string.routes_title_metro
        TransitMode.Tram -> Res.string.routes_title_tram
        TransitMode.Ferry -> Res.string.routes_title_ferry
    },
    number,
)
