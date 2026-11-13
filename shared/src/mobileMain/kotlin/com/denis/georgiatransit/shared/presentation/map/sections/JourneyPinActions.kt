package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_dismiss_pin
import georgiatransit.shared.generated.resources.map_planner_route_from_here
import georgiatransit.shared.generated.resources.map_planner_route_here
import org.jetbrains.compose.resources.stringResource

/** The long-press choice; the pin itself is drawn by the native map. */
@Composable
internal fun JourneyPinActions(
    onRouteHere: () -> Unit,
    onRouteFromHere: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(AutomationId.MapPlannerPin),
        color = MaterialTheme.colorScheme.surface,
        shape = TransitShapes.Large,
        shadowElevation = TransitSpacing.Small,
    ) {
        Row(
            modifier = Modifier.padding(start = TransitSpacing.Small, top = TransitSpacing.ExtraSmall, bottom = TransitSpacing.ExtraSmall),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRouteHere, modifier = Modifier.testTag(AutomationId.MapPlannerRouteHere)) {
                Text(stringResource(Res.string.map_planner_route_here))
            }
            OutlinedButton(onClick = onRouteFromHere, modifier = Modifier.testTag(AutomationId.MapPlannerRouteFromHere)) {
                Text(stringResource(Res.string.map_planner_route_from_here))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag(AutomationId.MapPlannerDismissPin)) {
                Icon(CloseIcon, contentDescription = stringResource(Res.string.map_planner_dismiss_pin))
            }
        }
    }
}

@Preview
@Composable
private fun JourneyPinActionsPreview() {
    GeorgiaTransitTheme {
        JourneyPinActions(onRouteHere = {}, onRouteFromHere = {}, onDismiss = {})
    }
}
