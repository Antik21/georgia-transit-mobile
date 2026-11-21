package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_field_collapsed
import georgiatransit.shared.generated.resources.map_planner_field_expanded
import georgiatransit.shared.generated.resources.map_planner_map_point
import georgiatransit.shared.generated.resources.map_planner_my_location
import georgiatransit.shared.generated.resources.map_planner_not_set
import georgiatransit.shared.generated.resources.map_planner_origin
import georgiatransit.shared.generated.resources.map_planner_pick_on_map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One endpoint: a collapsed summary that opens to the two ways of choosing it, the chosen one filled. */
@Composable
internal fun JourneyEndpointBlock(
    side: JourneyPlanSide,
    endpoint: JourneyEndpoint?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPickPoint: () -> Unit,
    onUseMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOrigin = side == JourneyPlanSide.Origin
    val background by animateColorAsState(
        if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    )
    val chevronRotation by animateFloatAsState(if (isExpanded) 180f else 0f)
    val expansion = stringResource(if (isExpanded) Res.string.map_planner_field_expanded else Res.string.map_planner_field_collapsed)
    Column(modifier = modifier.fillMaxWidth().clip(TransitShapes.Large).background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = expansion }
                .testTag(if (isOrigin) AutomationId.MapPlannerOriginToggle else AutomationId.MapPlannerDestinationToggle)
                .padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JourneyEndpointBadge(side)
            Spacer(Modifier.width(TransitSpacing.Medium))
            JourneyEndpointSummary(side = side, endpoint = endpoint, modifier = Modifier.weight(1f))
            Icon(ChevronDownIcon, contentDescription = null, modifier = Modifier.rotate(chevronRotation))
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.padding(start = TransitSpacing.Medium, end = TransitSpacing.Medium, bottom = TransitSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                EndpointChoice(
                    icon = PlaceIcon,
                    label = Res.string.map_planner_pick_on_map,
                    isChosen = endpoint is JourneyEndpoint.MapPoint,
                    onClick = onPickPoint,
                    testTag = if (isOrigin) AutomationId.MapPlannerPickOrigin else AutomationId.MapPlannerPickDestination,
                )
                EndpointChoice(
                    icon = MyLocationIcon,
                    label = Res.string.map_planner_my_location,
                    isChosen = endpoint is JourneyEndpoint.CurrentLocation,
                    onClick = onUseMyLocation,
                    testTag = if (isOrigin) AutomationId.MapPlannerMyLocationOrigin else AutomationId.MapPlannerMyLocationDestination,
                )
            }
        }
    }
}

@Composable
internal fun JourneyEndpointBadge(side: JourneyPlanSide, modifier: Modifier = Modifier, minimumSize: Dp = 36.dp) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minimumSize, minHeight = minimumSize)
            .background(Color(side.markerArgb), CircleShape)
            .padding(horizontal = TransitSpacing.ExtraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            side.markerLabel,
            modifier = Modifier.clearAndSetSemantics {},
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun JourneyEndpointSummary(
    side: JourneyPlanSide,
    endpoint: JourneyEndpoint?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val isOrigin = side == JourneyPlanSide.Origin
    val valueTag = if (isOrigin) AutomationId.MapPlannerOriginValue else AutomationId.MapPlannerDestinationValue
    Column(modifier = modifier) {
        Text(
            stringResource(if (isOrigin) Res.string.map_planner_origin else Res.string.map_planner_destination),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(when (endpoint) {
                null -> Res.string.map_planner_not_set
                JourneyEndpoint.CurrentLocation -> Res.string.map_planner_my_location
                is JourneyEndpoint.MapPoint -> Res.string.map_planner_map_point
            }),
            modifier = Modifier.testTag(valueTag),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (compact) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EndpointChoice(icon: ImageVector, label: StringResource, isChosen: Boolean, onClick: () -> Unit, testTag: String) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(TransitSpacing.Small))
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    }
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag)
    if (isChosen) {
        Button(onClick = onClick, modifier = buttonModifier, shape = TransitShapes.Medium) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = TransitShapes.Medium,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { content() }
    }
}

@Preview
@Composable
private fun JourneyEndpointBlockPreview() {
    GeorgiaTransitTheme {
        Column(verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
            JourneyEndpointBlock(JourneyPlanSide.Origin, null, isExpanded = true, onToggle = {}, onPickPoint = {}, onUseMyLocation = {})
            JourneyEndpointBlock(
                JourneyPlanSide.Destination,
                JourneyEndpoint.MapPoint(GeoPoint(41.64, 41.63)),
                isExpanded = false,
                onToggle = {}, onPickPoint = {}, onUseMyLocation = {},
            )
        }
    }
}
