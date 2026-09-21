package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.ArrivalSourceUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalRowUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalTimeUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetState
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_stop_arrivals_arriving
import georgiatransit.shared.generated.resources.map_stop_arrivals_close
import georgiatransit.shared.generated.resources.map_stop_arrivals_empty
import georgiatransit.shared.generated.resources.map_stop_arrivals_error
import georgiatransit.shared.generated.resources.map_stop_arrivals_headsign_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_loading
import georgiatransit.shared.generated.resources.map_stop_arrivals_minutes
import georgiatransit.shared.generated.resources.map_stop_arrivals_offline
import georgiatransit.shared.generated.resources.map_stop_arrivals_page_source
import georgiatransit.shared.generated.resources.map_stop_arrivals_partial
import georgiatransit.shared.generated.resources.map_stop_arrivals_refreshing
import georgiatransit.shared.generated.resources.map_stop_arrivals_retry
import georgiatransit.shared.generated.resources.map_stop_arrivals_route_details_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_route_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_routes
import georgiatransit.shared.generated.resources.map_stop_arrivals_selected_route
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_aggregator
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_approximate
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_official
import georgiatransit.shared.generated.resources.map_stop_arrivals_source_schedule
import georgiatransit.shared.generated.resources.map_stop_arrivals_stale
import georgiatransit.shared.generated.resources.map_stop_arrivals_stop_details_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_time_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_title
import georgiatransit.shared.generated.resources.map_stop_arrivals_unavailable
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StopArrivalsSheet(
    sheet: StopArrivalsSheetUi,
    showOpenStreetMapAttribution: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            // ModalBottomSheet owns a separate Android semantics tree. Re-enable the bridge on
            // this in-sheet root so descendants retain their stable resource IDs on Android.
            modifier = Modifier.fillMaxWidth()
                .enableAutomationResourceIds()
                .testTag(AutomationId.MapStopArrivalsSheet)
                .padding(horizontal = TransitSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.map_stop_arrivals_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sheet.stopName.ifBlank { stringResource(Res.string.map_stop_arrivals_stop_details_unavailable) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(AutomationId.MapStopArrivalsClose),
                ) {
                    Text(stringResource(Res.string.map_stop_arrivals_close))
                }
            }
            if (showOpenStreetMapAttribution) {
                OpenStreetMapAttribution(modifier = Modifier.align(Alignment.End))
            }
            if (sheet.passingRoutes.isNotEmpty() || sheet.passingRouteShortNames.isNotEmpty()) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_routes),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (sheet.passingRoutes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        items(sheet.passingRoutes, key = { it.routeId.value }) { route ->
                            StopRouteChip(
                                label = route.routeLabel,
                                background = Color(route.backgroundArgb),
                                foreground = Color(route.textArgb),
                                isSelected = route.isSelected,
                            )
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                        items(sheet.passingRouteShortNames, key = { it }) { shortName ->
                            StopRouteChip(
                                label = shortName,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                                isSelected = false,
                            )
                        }
                    }
                }
            }
            if (sheet.hasUnavailableRouteDetails) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_route_details_unavailable),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRouteDetailsUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sheet.isRefreshing) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_refreshing),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRefreshing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sheet.isStale) {
                Text(
                    stringResource(Res.string.map_stop_arrivals_stale),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsStale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sheet.pageSource?.let { source ->
                Text(
                    stringResource(Res.string.map_stop_arrivals_page_source, arrivalSourceLabel(source)),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StopArrivalsStatus(sheet = sheet, onRetry = onRetry)
            if (sheet.rows.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .testTag(AutomationId.MapStopArrivalsRows),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                ) {
                    items(sheet.rows) { row -> StopArrivalRow(row) }
                }
            }
        }
    }
}

/** Informational counterpart of the selected-route chip: no dismiss or click affordance. */
@Composable
private fun StopRouteChip(
    label: String,
    background: Color,
    foreground: Color,
    isSelected: Boolean,
) {
    val selectedLabel = stringResource(Res.string.map_stop_arrivals_selected_route)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = background,
        tonalElevation = if (isSelected) TransitSpacing.ExtraSmall else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(InputChipDefaults.Height)
                .padding(horizontal = TransitSpacing.Small)
                .semantics {
                    contentDescription = if (isSelected) "$label. $selectedLabel" else label
                    selected = isSelected
                },
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DirectionsBusIcon,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
                tint = foreground,
            )
            Text(
                text = label,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Status is a polite summary only; repeated row/source refreshes are deliberately not live regions. */
@Composable
private fun StopArrivalsStatus(sheet: StopArrivalsSheetUi, onRetry: () -> Unit) {
    val status = when (sheet.state) {
        StopArrivalsSheetState.Loading -> stringResource(Res.string.map_stop_arrivals_loading) to AutomationId.MapStopArrivalsLoading
        StopArrivalsSheetState.NoArrivals -> stringResource(Res.string.map_stop_arrivals_empty) to AutomationId.MapStopArrivalsEmpty
        StopArrivalsSheetState.PartialData -> stringResource(Res.string.map_stop_arrivals_partial) to AutomationId.MapStopArrivalsPartial
        StopArrivalsSheetState.Offline -> stringResource(Res.string.map_stop_arrivals_offline) to AutomationId.MapStopArrivalsOffline
        StopArrivalsSheetState.UpstreamError -> stringResource(Res.string.map_stop_arrivals_error) to AutomationId.MapStopArrivalsError
        StopArrivalsSheetState.Unavailable -> stringResource(Res.string.map_stop_arrivals_unavailable) to AutomationId.MapStopArrivalsUnavailable
        StopArrivalsSheetState.Ready -> null
    } ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(status.second)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = TransitShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(status.first, style = MaterialTheme.typography.bodyMedium)
            if (sheet.state is StopArrivalsSheetState.Offline || sheet.state is StopArrivalsSheetState.UpstreamError) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsRetry),
                ) { Text(stringResource(Res.string.map_stop_arrivals_retry)) }
            }
        }
    }
}

@Composable
private fun StopArrivalRow(row: StopArrivalRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapStopArrivalsRow),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            row.routeBadge?.let { badge ->
                Surface(shape = TransitShapes.Small, color = Color(badge.backgroundArgb)) {
                    Text(
                        badge.routeLabel,
                        modifier = Modifier.padding(horizontal = TransitSpacing.ExtraSmall),
                        color = Color(badge.textArgb),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } ?: Text(
                row.routeShortName ?: stringResource(Res.string.map_stop_arrivals_route_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                row.headsign.ifBlank { stringResource(Res.string.map_stop_arrivals_headsign_unavailable) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                arrivalSourceLabel(row.source),
                modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(arrivalTimeLabel(row.time), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun arrivalTimeLabel(time: StopArrivalTimeUi): String = when (time) {
    StopArrivalTimeUi.Arriving -> stringResource(Res.string.map_stop_arrivals_arriving)
    is StopArrivalTimeUi.Minutes -> stringResource(Res.string.map_stop_arrivals_minutes, time.value)
    StopArrivalTimeUi.Unavailable -> stringResource(Res.string.map_stop_arrivals_time_unavailable)
}

@Composable
private fun arrivalSourceLabel(source: ArrivalSourceUi): String = when (source) {
    ArrivalSourceUi.OfficialRealtime -> stringResource(Res.string.map_stop_arrivals_source_official)
    ArrivalSourceUi.AggregatorRealtime -> stringResource(Res.string.map_stop_arrivals_source_aggregator)
    ArrivalSourceUi.Schedule -> stringResource(Res.string.map_stop_arrivals_source_schedule)
    ArrivalSourceUi.Approximate -> stringResource(Res.string.map_stop_arrivals_source_approximate)
}
