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
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.displayName
import com.denis.georgiatransit.shared.presentation.ui.component.routeTitle
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_row_description
import georgiatransit.shared.generated.resources.routes_row_description_short
import georgiatransit.shared.generated.resources.routes_row_description_with_direction
import georgiatransit.shared.generated.resources.routes_row_limit_reached
import georgiatransit.shared.generated.resources.routes_row_selected
import georgiatransit.shared.generated.resources.routes_row_unselected
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
    val subtitle = route.catalogDirection.ifBlank { route.catalogName }
    val selectionState = stringResource(
        if (selected) Res.string.routes_row_selected else Res.string.routes_row_unselected,
    )
    val accessibilityDescription = routeSelectionDescription(route, isToggleEnabled)
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
internal fun routeSelectionDescription(route: RouteItemUiModel, enabled: Boolean): String {
    val mode = route.mode.displayName()
    val name = route.catalogName
    val direction = route.catalogDirection
    val description = when {
        name.isNotBlank() && direction.isNotBlank() ->
            stringResource(Res.string.routes_row_description_with_direction, route.shortName, name, direction, mode)
        name.isNotBlank() || direction.isNotBlank() ->
            stringResource(Res.string.routes_row_description, route.shortName, name.ifBlank { direction }, mode)
        else -> stringResource(Res.string.routes_row_description_short, route.shortName, mode)
    }
    return if (enabled) description else "$description. ${stringResource(Res.string.routes_row_limit_reached)}"
}

private val RouteItemUiModel.catalogName: String
    get() = name.takeUnless { it.equals(shortName, ignoreCase = true) }.orEmpty()

private val RouteItemUiModel.catalogDirection: String
    get() = direction.takeUnless { it.equals(catalogName, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }.orEmpty()
