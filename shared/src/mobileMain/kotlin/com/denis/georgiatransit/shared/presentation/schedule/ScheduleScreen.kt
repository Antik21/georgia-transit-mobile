package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.map.sections.BackArrowIcon
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_planner_back
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.schedule_error
import georgiatransit.shared.generated.resources.schedule_following_day
import georgiatransit.shared.generated.resources.schedule_local_times
import georgiatransit.shared.generated.resources.schedule_no_routes
import georgiatransit.shared.generated.resources.schedule_no_times
import georgiatransit.shared.generated.resources.schedule_stop_empty
import georgiatransit.shared.generated.resources.schedule_title
import georgiatransit.shared.generated.resources.schedule_unavailable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState

@Composable
internal fun ScheduleScreen(viewModel: ScheduleViewModel, onBack: () -> Unit) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.fromLanguageTag(language)))
    }
    Content(state = state, onAction = { viewModel.dispatchAction(it) }, onBack = onBack)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(AutomationId.ScheduleScreen)
            .safeDrawingPadding()
            .padding(horizontal = TransitSpacing.Medium),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(AutomationId.ScheduleBack)) {
                Icon(BackArrowIcon, contentDescription = stringResource(Res.string.map_planner_back))
            }
            Text(stringResource(Res.string.schedule_title), style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        key(state.routeId, state.directionId, state.date) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                if (state.route != null) {
                    item { ScheduleFilters(state, onAction) }
                }
                val content = state.content
                if (content is ScheduleContent.Available) {
                    item {
                        Text(
                            stringResource(Res.string.schedule_local_times),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(content.schedule.stops) { stop ->
                        val times = stop.arrivalTimes.map { it.toLocalDateTime(content.schedule.timeZone) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AutomationId.ScheduleStop)
                                .padding(vertical = TransitSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                        ) {
                            Text(stop.name.forScheduleLocale(state.locale), style = MaterialTheme.typography.titleSmall)
                            val timeLabels = times.map { time ->
                                val offset = time.date.toEpochDays() - content.schedule.serviceDate.toEpochDays()
                                val clock = time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')
                                if (offset == 0L) clock else {
                                    stringResource(Res.string.schedule_following_day, clock, offset.toInt())
                                }
                            }
                            Text(
                                text = timeLabels.joinToString("   ").ifEmpty { stringResource(Res.string.schedule_stop_empty) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    item { ScheduleStatus(content, onRetry = { onAction(Action.Retry) }) }
                }
            }
        }
    }
}

@Composable
private fun ScheduleStatus(content: ScheduleContent, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = TransitSpacing.Large), contentAlignment = Alignment.Center) {
        when (content) {
            ScheduleContent.Loading -> CircularProgressIndicator()
            ScheduleContent.Unavailable -> Text(stringResource(Res.string.schedule_unavailable))
            ScheduleContent.NoRoutes -> Text(stringResource(Res.string.schedule_no_routes))
            ScheduleContent.NoTimes -> Text(
                text = stringResource(Res.string.schedule_no_times),
                modifier = Modifier.testTag(AutomationId.ScheduleEmpty),
            )
            ScheduleContent.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.schedule_error))
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(AutomationId.ScheduleRetry)) {
                    Text(stringResource(Res.string.routes_retry))
                }
            }
            is ScheduleContent.Available -> Unit
        }
    }
}

@Preview
@Composable
private fun SchedulePreview() {
    GeorgiaTransitTheme {
        Content(ViewState(date = LocalDate(2026, 11, 19), content = ScheduleContent.NoTimes), {}, {})
    }
}
