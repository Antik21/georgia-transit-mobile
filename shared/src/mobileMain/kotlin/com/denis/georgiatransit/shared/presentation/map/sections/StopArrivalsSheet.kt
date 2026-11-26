package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.presentation.map.ArrivalSourceUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalRowUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalTimeUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetState
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetUi
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.component.TransitCloseButton
import com.denis.georgiatransit.shared.presentation.ui.component.routeTitle
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_stop_arrivals_arriving
import georgiatransit.shared.generated.resources.map_stop_arrivals_collapse_accessibility
import georgiatransit.shared.generated.resources.map_stop_arrivals_empty
import georgiatransit.shared.generated.resources.map_stop_arrivals_error
import georgiatransit.shared.generated.resources.map_stop_arrivals_expand_accessibility
import georgiatransit.shared.generated.resources.map_stop_arrivals_handle_accessibility
import georgiatransit.shared.generated.resources.map_stop_arrivals_loading
import georgiatransit.shared.generated.resources.map_stop_arrivals_minutes
import georgiatransit.shared.generated.resources.map_stop_arrivals_offline
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
import georgiatransit.shared.generated.resources.map_stop_arrivals_stale_forecast
import georgiatransit.shared.generated.resources.map_stop_arrivals_stop_details_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_time_unavailable
import georgiatransit.shared.generated.resources.map_stop_arrivals_title
import georgiatransit.shared.generated.resources.map_stop_arrivals_unavailable
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StopArrivalsSheet(
    sheet: StopArrivalsSheetUi,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onTopPositioned: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val hasPartialAnchor = sheetState.hasPartiallyExpandedState
    val isExpanded = sheetState.currentValue == SheetValue.Expanded
    val commonSource = if (sheet.rows.isEmpty()) {
        sheet.pageSource
    } else {
        sheet.rows.first().source.takeIf { source -> sheet.rows.all { it.source == source } }
    }
    val showScheduleInHeader = commonSource == ArrivalSourceUi.Schedule && sheet.rows.isNotEmpty()
    val showStaleForecast = sheet.isStale && sheet.rows.any { it.source != ArrivalSourceUi.Schedule }
    val detailsSource = commonSource.takeUnless { source ->
        showScheduleInHeader || showStaleForecast &&
            (source == ArrivalSourceUi.OfficialRealtime || source == ArrivalSourceUi.AggregatorRealtime)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            // The Android modal uses a separate semantics tree.
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { onTopPositioned(it.positionOnScreen().y.toDouble()) }
                .enableAutomationResourceIds()
                .testTag(AutomationId.MapStopArrivalsSheet)
                .padding(horizontal = TransitSpacing.Medium),
        ) {
            StopArrivalsHandle(
                isExpanded = isExpanded,
                onToggle = if (hasPartialAnchor) {
                    {
                        scope.launch {
                            if (isExpanded) sheetState.partialExpand() else sheetState.expand()
                        }
                    }
                } else {
                    null
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.map_stop_arrivals_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sheet.isStale) {
                        Text(
                            stringResource(
                                if (showStaleForecast) Res.string.map_stop_arrivals_stale_forecast
                                else Res.string.map_stop_arrivals_stale,
                            ),
                            modifier = Modifier.testTag(AutomationId.MapStopArrivalsStale)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (showScheduleInHeader) {
                        Text(
                            stringResource(Res.string.map_stop_arrivals_source_schedule),
                            modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TransitCloseButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            if (!sheetState.isVisible) onDismiss()
                        }
                    },
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsClose),
                    tonal = true,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .then(if (sheet.rows.isNotEmpty()) Modifier.testTag(AutomationId.MapStopArrivalsRows) else Modifier),
                contentPadding = PaddingValues(bottom = TransitSpacing.Medium),
            ) {
                item(key = "stop-details") {
                    StopArrivalsDetails(sheet, detailsSource, onRetry)
                }
                itemsIndexed(sheet.rows) { index, row ->
                    StopArrivalRow(row, showSource = commonSource == null, isStale = sheet.isStale)
                    if (index < sheet.rows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StopArrivalsHandle(isExpanded: Boolean, onToggle: (() -> Unit)?) {
    val handleModifier = if (onToggle != null) {
        val description = stringResource(Res.string.map_stop_arrivals_handle_accessibility)
        val actionLabel = stringResource(
            if (isExpanded) Res.string.map_stop_arrivals_collapse_accessibility
            else Res.string.map_stop_arrivals_expand_accessibility,
        )
        Modifier
            .testTag(AutomationId.MapStopArrivalsHandle)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = actionLabel,
                onClick = onToggle,
            )
    } else {
        Modifier
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .then(handleModifier)
            .padding(vertical = StopArrivalsHandleVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(StopArrivalsHandleWidth, StopArrivalsHandleHeight),
            shape = RoundedCornerShape(StopArrivalsHandleHeight / 2),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = StopArrivalsHandleAlpha),
        ) {}
    }
}

@Composable
private fun StopArrivalsDetails(
    sheet: StopArrivalsSheetUi,
    commonSource: ArrivalSourceUi?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = TransitSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Text(
            sheet.stopName.ifBlank { stringResource(Res.string.map_stop_arrivals_stop_details_unavailable) },
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (sheet.passingRoutes.isNotEmpty() || sheet.passingRouteShortNames.isNotEmpty()) {
            Text(
                stringResource(Res.string.map_stop_arrivals_routes),
                style = MaterialTheme.typography.labelLarge,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall)) {
                if (sheet.passingRoutes.isNotEmpty()) {
                    items(sheet.passingRoutes, key = { it.routeId.value }) { route ->
                        StopRouteChip(
                            label = route.routeLabel,
                            background = Color(route.backgroundArgb),
                            foreground = Color(route.textArgb),
                            isSelected = route.isSelected,
                        )
                    }
                } else {
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
        if (sheet.isRefreshing && sheet.rows.isEmpty()) {
            Text(
                stringResource(Res.string.map_stop_arrivals_refreshing),
                modifier = Modifier.testTag(AutomationId.MapStopArrivalsRefreshing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        commonSource?.let {
            Text(
                arrivalSourceLabel(it),
                modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StopArrivalsStatus(sheet = sheet, onRetry = onRetry)
    }
}

private val StopArrivalsHandleWidth = 32.dp
private val StopArrivalsHandleHeight = 4.dp
private val StopArrivalsHandleVerticalPadding = 22.dp
private const val StopArrivalsHandleAlpha = 0.40f

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
                .heightIn(min = InputChipDefaults.Height)
                .padding(horizontal = TransitSpacing.Small, vertical = TransitSpacing.ExtraSmall)
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
    val modifier = Modifier.testTag(status.second)
    when (sheet.state) {
        StopArrivalsSheetState.Loading -> LoadingState(message = status.first, modifier = modifier, compact = true)
        StopArrivalsSheetState.Offline,
        StopArrivalsSheetState.UpstreamError,
        -> ErrorState(
            title = status.first,
            modifier = modifier,
            retryLabel = stringResource(Res.string.map_stop_arrivals_retry),
            onRetry = onRetry,
            retryModifier = Modifier.testTag(AutomationId.MapStopArrivalsRetry),
            compact = true,
        )
        StopArrivalsSheetState.PartialData -> Text(
            status.first,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> EmptyState(title = status.first, modifier = modifier, compact = true)
    }
}

@Composable
private fun StopArrivalRow(row: StopArrivalRowUi, showSource: Boolean, isStale: Boolean) {
    val routeLabel = row.routeBadge?.routeLabel ?: row.routeShortName
    val routeTitle = row.routeBadge?.let { it.mode.routeTitle(it.routeLabel) }
        ?: routeLabel ?: stringResource(Res.string.map_stop_arrivals_route_unavailable)
    val direction = row.headsign.takeIf { it.isNotBlank() && !it.equals(routeLabel, ignoreCase = true) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
            .testTag(AutomationId.MapStopArrivalsRow)
            .padding(vertical = TransitSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.widthIn(min = 40.dp).clearAndSetSemantics {},
            contentAlignment = Alignment.CenterStart,
        ) {
            row.routeBadge?.let { badge ->
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
                        .background(Color(badge.backgroundArgb), TransitShapes.Full)
                        .padding(horizontal = TransitSpacing.ExtraSmall, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge.routeLabel,
                        color = Color(badge.textArgb),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
        ) {
            Text(
                text = routeTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            direction?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showSource) {
                Text(
                    arrivalSourceLabel(row.source),
                    modifier = Modifier.testTag(AutomationId.MapStopArrivalsSource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            arrivalTimeLabel(row.time, isStale),
            modifier = Modifier.fillMaxWidth(0.33f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun arrivalTimeLabel(time: StopArrivalTimeUi, isStale: Boolean): String = when (time) {
    StopArrivalTimeUi.Arriving -> if (isStale) {
        stringResource(Res.string.map_stop_arrivals_minutes, 0)
    } else {
        stringResource(Res.string.map_stop_arrivals_arriving)
    }
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
