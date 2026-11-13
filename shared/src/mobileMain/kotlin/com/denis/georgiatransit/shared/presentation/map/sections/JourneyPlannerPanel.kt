package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.presentation.map.JourneyLocationReason
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanErrorReason
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_already_there
import georgiatransit.shared.generated.resources.map_planner_clear
import georgiatransit.shared.generated.resources.map_planner_close
import georgiatransit.shared.generated.resources.map_planner_destination
import georgiatransit.shared.generated.resources.map_planner_disclaimer
import georgiatransit.shared.generated.resources.map_planner_empty
import georgiatransit.shared.generated.resources.map_planner_error_busy
import georgiatransit.shared.generated.resources.map_planner_error_network
import georgiatransit.shared.generated.resources.map_planner_error_rejected
import georgiatransit.shared.generated.resources.map_planner_error_service
import georgiatransit.shared.generated.resources.map_planner_incomplete
import georgiatransit.shared.generated.resources.map_planner_loading
import georgiatransit.shared.generated.resources.map_planner_location_approximate
import georgiatransit.shared.generated.resources.map_planner_location_locating
import georgiatransit.shared.generated.resources.map_planner_location_permission
import georgiatransit.shared.generated.resources.map_planner_map_point
import georgiatransit.shared.generated.resources.map_planner_my_location
import georgiatransit.shared.generated.resources.map_planner_not_set
import georgiatransit.shared.generated.resources.map_planner_origin
import georgiatransit.shared.generated.resources.map_planner_pick_hint
import georgiatransit.shared.generated.resources.map_planner_pick_on_map
import georgiatransit.shared.generated.resources.map_planner_refreshing
import georgiatransit.shared.generated.resources.map_planner_retry
import georgiatransit.shared.generated.resources.map_planner_stale
import georgiatransit.shared.generated.resources.map_planner_swap
import georgiatransit.shared.generated.resources.map_planner_title
import georgiatransit.shared.generated.resources.map_planner_unavailable
import org.jetbrains.compose.resources.stringResource

/** The planner replaces the map controls while open; every state message comes from the shared status. */
@Composable
internal fun JourneyPlannerPanel(
    planner: JourneyPlannerUi,
    onClose: () -> Unit,
    onPickPoint: (JourneyPlanSide) -> Unit,
    onUseMyLocation: (JourneyPlanSide) -> Unit,
    onSwap: () -> Unit,
    onClear: () -> Unit,
    onSelectOption: (JourneyId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = planner.status
    Column(
        modifier = modifier.testTag(AutomationId.MapPlannerPanel).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.map_planner_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onSwap, modifier = Modifier.testTag(AutomationId.MapPlannerSwap)) {
                Icon(SwapIcon, contentDescription = stringResource(Res.string.map_planner_swap))
            }
            TextButton(onClick = onClear, modifier = Modifier.testTag(AutomationId.MapPlannerClear)) {
                Text(stringResource(Res.string.map_planner_clear))
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag(AutomationId.MapPlannerClose)) {
                Icon(CloseIcon, contentDescription = stringResource(Res.string.map_planner_close))
            }
        }
        JourneySide.entries.forEach { side ->
            JourneyEndpointRow(
                side = side,
                endpoint = if (side.side == JourneyPlanSide.Origin) planner.origin else planner.destination,
                isPicking = (status as? JourneyPlannerStatus.PickingPoint)?.side == side.side,
                onPickPoint = { onPickPoint(side.side) },
                onUseMyLocation = { onUseMyLocation(side.side) },
            )
        }
        plannerStatusText(planner)?.let { message ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status.isBusy()) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    message,
                    modifier = Modifier.testTag(AutomationId.MapPlannerStatus).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (status is JourneyPlannerStatus.LocationNeeded) {
            OutlinedButton(onClick = { onPickPoint(status.side) }, modifier = Modifier.testTag(AutomationId.MapPlannerPickInstead)) {
                Text(stringResource(Res.string.map_planner_pick_on_map))
            }
        }
        if (status.canRetry()) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(AutomationId.MapPlannerRetry)) {
                Text(stringResource(Res.string.map_planner_retry))
            }
        }
        if (status is JourneyPlannerStatus.Results) {
            status.options.forEach { option ->
                val isSelected = option.id == status.selectedId
                JourneyOptionCard(
                    option = option,
                    isSelected = isSelected,
                    nearestEtas = planner.buses?.takeIf { isSelected && it.optionId == option.id }?.etaMinutes.orEmpty(),
                    onSelect = { onSelectOption(option.id) },
                )
            }
        }
        Text(
            stringResource(Res.string.map_planner_disclaimer),
            modifier = Modifier.testTag(AutomationId.MapPlannerDisclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class JourneySide(val side: JourneyPlanSide, val letter: String, val colorArgb: Long) {
    Origin(JourneyPlanSide.Origin, "A", 0xFF2E7D32),
    Destination(JourneyPlanSide.Destination, "B", 0xFF8E1B2D),
}

@Composable
private fun JourneyEndpointRow(
    side: JourneySide,
    endpoint: JourneyEndpoint?,
    isPicking: Boolean,
    onPickPoint: () -> Unit,
    onUseMyLocation: () -> Unit,
) {
    val isOrigin = side.side == JourneyPlanSide.Origin
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(24.dp).background(Color(side.colorArgb), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(side.letter, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (isOrigin) Res.string.map_planner_origin else Res.string.map_planner_destination),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        when (endpoint) {
                            JourneyEndpoint.CurrentLocation -> Res.string.map_planner_my_location
                            is JourneyEndpoint.MapPoint -> Res.string.map_planner_map_point
                            null -> Res.string.map_planner_not_set
                        },
                    ),
                    modifier = Modifier.testTag(
                        if (isOrigin) AutomationId.MapPlannerOriginValue else AutomationId.MapPlannerDestinationValue,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (isPicking) {
                    Text(
                        stringResource(Res.string.map_planner_pick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            FilterChip(
                selected = isPicking,
                onClick = onPickPoint,
                label = { Text(stringResource(Res.string.map_planner_pick_on_map)) },
                modifier = Modifier.testTag(if (isOrigin) AutomationId.MapPlannerPickOrigin else AutomationId.MapPlannerPickDestination),
            )
            FilterChip(
                selected = endpoint == JourneyEndpoint.CurrentLocation,
                onClick = onUseMyLocation,
                label = { Text(stringResource(Res.string.map_planner_my_location)) },
                modifier = Modifier.testTag(
                    if (isOrigin) AutomationId.MapPlannerMyLocationOrigin else AutomationId.MapPlannerMyLocationDestination,
                ),
            )
        }
    }
}

@Composable
private fun plannerStatusText(planner: JourneyPlannerUi): String? {
    val status = planner.status
    if (status is JourneyPlannerStatus.Results) {
        return when {
            status.isRefreshing -> stringResource(Res.string.map_planner_refreshing)
            status.isStale -> stringResource(Res.string.map_planner_stale)
            else -> null
        }
    }
    return when (status) {
        JourneyPlannerStatus.Idle, is JourneyPlannerStatus.PickingPoint, is JourneyPlannerStatus.Results -> null
        JourneyPlannerStatus.EndpointsIncomplete -> stringResource(Res.string.map_planner_incomplete)
        JourneyPlannerStatus.Loading -> stringResource(Res.string.map_planner_loading)
        JourneyPlannerStatus.Empty -> stringResource(Res.string.map_planner_empty)
        JourneyPlannerStatus.Unavailable -> stringResource(Res.string.map_planner_unavailable)
        is JourneyPlannerStatus.AlreadyThere ->
            stringResource(Res.string.map_planner_already_there, status.walkMeters, status.walkMinutes)
        is JourneyPlannerStatus.LocationNeeded -> stringResource(
            when (status.reason) {
                JourneyLocationReason.PermissionRequired -> Res.string.map_planner_location_permission
                JourneyLocationReason.Locating -> Res.string.map_planner_location_locating
                JourneyLocationReason.Approximate -> Res.string.map_planner_location_approximate
            },
        )
        is JourneyPlannerStatus.Error -> stringResource(
            when (status.reason) {
                JourneyPlanErrorReason.Network -> Res.string.map_planner_error_network
                JourneyPlanErrorReason.Busy -> Res.string.map_planner_error_busy
                JourneyPlanErrorReason.Rejected -> Res.string.map_planner_error_rejected
                JourneyPlanErrorReason.Service -> Res.string.map_planner_error_service
            },
        )
    }
}

private fun JourneyPlannerStatus.isBusy(): Boolean = this == JourneyPlannerStatus.Loading ||
    (this is JourneyPlannerStatus.Results && isRefreshing) ||
    (this is JourneyPlannerStatus.LocationNeeded && reason == JourneyLocationReason.Locating)

private fun JourneyPlannerStatus.canRetry(): Boolean = (this is JourneyPlannerStatus.Error && retryable) ||
    this == JourneyPlannerStatus.Empty || (this is JourneyPlannerStatus.Results && isStale)

@Preview
@Composable
private fun JourneyPlannerPanelPreview() {
    GeorgiaTransitTheme {
        JourneyPlannerPanel(
            planner = JourneyPlannerUi(
                isOpen = true,
                origin = JourneyEndpoint.CurrentLocation,
                destination = JourneyEndpoint.MapPoint(GeoPoint(41.6413, 41.6359)),
                status = JourneyPlannerStatus.Results(listOf(PreviewJourneyOption), selectedId = PreviewJourneyOption.id),
            ),
            onClose = {}, onPickPoint = {}, onUseMyLocation = {}, onSwap = {}, onClear = {}, onSelectOption = {}, onRetry = {},
        )
    }
}
