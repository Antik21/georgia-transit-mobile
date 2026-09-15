package com.denis.georgiatransit.shared.presentation.routes.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.contrastSafeRouteTextColor
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_direction
import georgiatransit.shared.generated.resources.routes_mode_bus
import georgiatransit.shared.generated.resources.routes_mode_ferry
import georgiatransit.shared.generated.resources.routes_mode_metro
import georgiatransit.shared.generated.resources.routes_mode_tram
import georgiatransit.shared.generated.resources.routes_row_description
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
) {
    val isToggleEnabled = selected || !selectionLimitReached
    val mode = route.mode.displayName()
    val selectionState = stringResource(
        if (selected) Res.string.routes_row_selected else Res.string.routes_row_unselected,
    )
    val description = route.direction.takeIf(String::isNotBlank)?.let { direction ->
        stringResource(
            Res.string.routes_row_description_with_direction,
            route.shortName,
            route.name,
            direction,
            mode,
        )
    } ?: stringResource(Res.string.routes_row_description, route.shortName, route.name, mode)
    val accessibilityDescription = if (isToggleEnabled) {
        description
    } else {
        "$description. ${stringResource(Res.string.routes_row_limit_reached)}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                .padding(TransitSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Medium),
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(route.colorArgb), TransitShapes.Full),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    route.shortName,
                    color = Color(contrastSafeRouteTextColor(route.colorArgb, route.textColorArgb)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(route.shortName, style = MaterialTheme.typography.titleMedium)
                Text(route.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                route.direction.takeIf(String::isNotBlank)?.let { direction ->
                    Text(
                        stringResource(Res.string.routes_direction, direction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
