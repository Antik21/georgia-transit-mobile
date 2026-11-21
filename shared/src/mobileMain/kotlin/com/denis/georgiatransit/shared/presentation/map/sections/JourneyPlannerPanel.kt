package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.JourneyEndpoint
import com.denis.georgiatransit.shared.domain.model.JourneyId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.presentation.map.JourneyLegUi
import com.denis.georgiatransit.shared.presentation.map.JourneyLocationReason
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanErrorReason
import com.denis.georgiatransit.shared.presentation.map.JourneyPlanSide
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerStatus
import com.denis.georgiatransit.shared.presentation.map.JourneyPlannerUi
import com.denis.georgiatransit.shared.presentation.map.NearbyStopUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.CloseIcon
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_plan_trip_action
import georgiatransit.shared.generated.resources.map_planner_already_there
import georgiatransit.shared.generated.resources.map_planner_clear
import georgiatransit.shared.generated.resources.map_planner_back
import georgiatransit.shared.generated.resources.map_planner_find
import georgiatransit.shared.generated.resources.map_planner_close
import georgiatransit.shared.generated.resources.map_planner_disclaimer
import georgiatransit.shared.generated.resources.map_planner_edit_points
import georgiatransit.shared.generated.resources.map_planner_empty
import georgiatransit.shared.generated.resources.map_planner_error_busy
import georgiatransit.shared.generated.resources.map_planner_error_network
import georgiatransit.shared.generated.resources.map_planner_error_rejected
import georgiatransit.shared.generated.resources.map_planner_error_service
import georgiatransit.shared.generated.resources.map_planner_loading
import georgiatransit.shared.generated.resources.map_planner_location_approximate
import georgiatransit.shared.generated.resources.map_planner_location_locating
import georgiatransit.shared.generated.resources.map_planner_location_permission
import georgiatransit.shared.generated.resources.map_planner_pick_on_map
import georgiatransit.shared.generated.resources.map_planner_retry
import georgiatransit.shared.generated.resources.map_planner_stale
import georgiatransit.shared.generated.resources.map_planner_swap
import georgiatransit.shared.generated.resources.map_planner_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberJourneyStopNames(planner: JourneyPlannerUi, nearbyStops: List<NearbyStopUi>): Map<StopId, String> {
    val results = planner.status as? JourneyPlannerStatus.Results ?: return emptyMap()
    var rememberedNames by remember(planner.origin, planner.destination, Locale.current.language) {
        mutableStateOf(emptyMap<StopId, String>())
    }
    val itineraryStops = remember(results.options) {
        buildSet {
            results.options.forEach { option ->
                option.legs.filterIsInstance<JourneyLegUi.Ride>().forEach { ride ->
                    ride.fromStopId?.let(::add)
                    ride.toStopId?.let(::add)
                }
            }
        }
    }
    val names = remember(nearbyStops, itineraryStops, rememberedNames) {
        buildMap {
            itineraryStops.forEach { id -> rememberedNames[id]?.let { put(id, it) } }
            nearbyStops.forEach { stop ->
                if (stop.id in itineraryStops) stop.catalogName?.let { put(stop.id, it) }
            }
        }
    }
    // A viewport refresh can omit stops already shown in the itinerary.
    SideEffect { rememberedNames = names }
    return names
}

/** The planner replaces the map controls while open; every state message comes from the shared status. */
@Composable
internal fun JourneyPlannerPanel(
    planner: JourneyPlannerUi,
    expandedSide: JourneyPlanSide?,
    onToggleSide: (JourneyPlanSide) -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onFind: () -> Unit,
    onPickPoint: (JourneyPlanSide) -> Unit,
    onUseMyLocation: (JourneyPlanSide) -> Unit,
    onSwap: () -> Unit,
    onClear: () -> Unit,
    onSelectOption: (JourneyId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    stopNames: Map<StopId, String> = emptyMap(),
) {
    val status = planner.status
    val isEmpty = status == JourneyPlannerStatus.Empty
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var selectedCoordinates by remember { mutableStateOf<Pair<JourneyId, LayoutCoordinates>?>(null) }
    var scrollToOption by remember { mutableStateOf<JourneyId?>(null) }
    val selectedId = (status as? JourneyPlannerStatus.Results)?.selectedId
    LaunchedEffect(scrollToOption, selectedId) {
        val requested = scrollToOption ?: return@LaunchedEffect
        if (requested != selectedId) return@LaunchedEffect
        // Wait for the old card to collapse and the selected card to be laid out at its new height.
        withFrameNanos { }
        withFrameNanos { }
        val viewport = viewportCoordinates
        val selected = selectedCoordinates?.takeIf { it.first == requested }?.second
        if (viewport != null && selected != null && viewport.isAttached && selected.isAttached) {
            val top = viewport.localPositionOf(selected, Offset.Zero).y
            val margin = with(density) { TransitSpacing.ExtraSmall.toPx() }
            try {
                scrollState.animateScrollTo((scrollState.value + top - margin).roundToInt().coerceAtLeast(0))
            } finally {
                if (scrollToOption == requested) scrollToOption = null
            }
        } else {
            scrollToOption = null
        }
    }
    Column(
        modifier = modifier.testTag(AutomationId.MapPlannerPanel),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Column(modifier = headerModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (planner.showingResults) {
                Box(
                    Modifier.size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(50)),
                )
            }
            if (planner.showingResults) {
                ResultsHeader(planner.origin, planner.destination, onEdit)
            } else Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.map_plan_trip_action),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small)) {
                    // Swapping needs both points and clearing needs at least one.
                    if (planner.origin != null && planner.destination != null) {
                        HeaderButton(SwapIcon, Res.string.map_planner_swap, onSwap, AutomationId.MapPlannerSwap)
                    }
                    if (planner.origin != null || planner.destination != null) {
                        HeaderButton(TrashIcon, Res.string.map_planner_clear, onClear, AutomationId.MapPlannerClear)
                    }
                    HeaderButton(CloseIcon, Res.string.map_planner_close, onClose, AutomationId.MapPlannerClose)
                }
            }
        }
        // Only the body scrolls, so the title and its actions stay reachable over long results.
        Column(
            modifier = Modifier.weight(1f, fill = planner.showingResults && !isEmpty)
                .onGloballyPositioned { viewportCoordinates = it }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            if (!planner.showingResults) {
                JourneyPlanSide.entries.forEach { side ->
                    JourneyEndpointBlock(
                        side = side,
                        endpoint = if (side == JourneyPlanSide.Origin) planner.origin else planner.destination,
                        isExpanded = expandedSide == side,
                        onToggle = { onToggleSide(side) },
                        onPickPoint = { onPickPoint(side) },
                        onUseMyLocation = { onUseMyLocation(side) },
                    )
                }
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
            if (isEmpty) {
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapPlannerEditPoints)) {
                    Text(stringResource(Res.string.map_planner_edit_points))
                }
            } else if (status.canRetry()) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(AutomationId.MapPlannerRetry)) {
                    Text(stringResource(Res.string.map_planner_retry))
                }
            }
            if (planner.showingResults && !isEmpty) {
                Text(
                    stringResource(Res.string.map_planner_disclaimer),
                    modifier = Modifier.testTag(AutomationId.MapPlannerDisclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status is JourneyPlannerStatus.Results) {
                status.options.forEach { option ->
                    key(option.id) {
                        val isSelected = option.id == status.selectedId
                        JourneyOptionCard(
                            option = option,
                            isSelected = isSelected,
                            nearestEtas = planner.buses?.takeIf { isSelected && it.optionId == option.id }?.etaMinutes.orEmpty(),
                            stopNames = stopNames,
                            onSelect = {
                                scrollToOption = option.id
                                onSelectOption(option.id)
                            },
                            modifier = Modifier.onGloballyPositioned {
                                if (isSelected) selectedCoordinates = option.id to it
                            },
                        )
                    }
                }
            }
        }
        if (!planner.showingResults) {
            Button(
                onClick = onFind,
                enabled = planner.origin != null && planner.destination != null && status != JourneyPlannerStatus.Unavailable,
                modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapPlannerFind),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(Res.string.map_planner_find), modifier = Modifier.padding(vertical = TransitSpacing.Small))
            }
        }
    }
}

@Composable
private fun ResultsHeader(origin: JourneyEndpoint?, destination: JourneyEndpoint?, onEdit: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackEndpoints = LocalDensity.current.fontScale >= 1.5f || maxWidth < 280.dp
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(
                BackArrowIcon, Res.string.map_planner_back, onEdit, AutomationId.MapPlannerEdit,
                modifier = Modifier.offset(x = -TransitSpacing.Small),
            )
            if (stackEndpoints) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                ) {
                    EndpointChip(JourneyPlanSide.Origin, origin, Modifier.fillMaxWidth())
                    EndpointChip(JourneyPlanSide.Destination, destination, Modifier.fillMaxWidth())
                }
            } else {
                EndpointChip(JourneyPlanSide.Origin, origin, Modifier.weight(1f))
                Text("→", modifier = Modifier.padding(horizontal = TransitSpacing.ExtraSmall))
                EndpointChip(JourneyPlanSide.Destination, destination, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EndpointChip(side: JourneyPlanSide, endpoint: JourneyEndpoint?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = TransitSpacing.Small, vertical = TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        JourneyEndpointBadge(side, minimumSize = 24.dp)
        JourneyEndpointSummary(side, endpoint, modifier = Modifier.weight(1f), compact = true)
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    label: StringResource,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp).testTag(testTag),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun plannerStatusText(planner: JourneyPlannerUi): String? {
    val status = planner.status
    if (status is JourneyPlannerStatus.Results) {
        return when {
            status.isStale -> stringResource(Res.string.map_planner_stale)
            else -> null
        }
    }
    return when (status) {
        JourneyPlannerStatus.Idle, JourneyPlannerStatus.EndpointsIncomplete,
        is JourneyPlannerStatus.PickingPoint, is JourneyPlannerStatus.Results -> null
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
    (this is JourneyPlannerStatus.LocationNeeded && reason == JourneyLocationReason.Locating)

private fun JourneyPlannerStatus.canRetry(): Boolean = (this is JourneyPlannerStatus.Error && retryable) ||
    (this is JourneyPlannerStatus.Results && isStale)

@Preview
@Composable
private fun JourneyPlannerPanelPreview() {
    GeorgiaTransitTheme {
        JourneyPlannerPanel(
            planner = JourneyPlannerUi(
                isOpen = true,
                origin = JourneyEndpoint.CurrentLocation,
                destination = JourneyEndpoint.MapPoint(GeoPoint(41.6413, 41.6359)),
                showingResults = true,
                status = JourneyPlannerStatus.Results(listOf(PreviewJourneyOption), selectedId = PreviewJourneyOption.id),
            ),
            expandedSide = null, onToggleSide = {},
            onClose = {}, onEdit = {}, onFind = {}, onPickPoint = {}, onUseMyLocation = {}, onSwap = {}, onClear = {},
            onSelectOption = {}, onRetry = {},
        )
    }
}
