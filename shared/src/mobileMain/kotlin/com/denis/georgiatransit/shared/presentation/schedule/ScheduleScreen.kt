package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.component.TransitTopAppBar
import com.denis.georgiatransit.shared.presentation.ui.theme.GeorgiaTransitTheme
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_retry
import georgiatransit.shared.generated.resources.schedule_error
import georgiatransit.shared.generated.resources.schedule_loading
import georgiatransit.shared.generated.resources.schedule_local_times
import georgiatransit.shared.generated.resources.schedule_no_routes
import georgiatransit.shared.generated.resources.schedule_no_times
import georgiatransit.shared.generated.resources.schedule_title
import georgiatransit.shared.generated.resources.schedule_unavailable
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState

@Composable
internal fun ScheduleScreen(viewModel: ScheduleViewModel, onDismiss: () -> Unit) {
    val state by viewModel.collectAsState()
    val language = Locale.current.language
    LaunchedEffect(language) {
        viewModel.dispatchAction(Action.LocaleChanged(TransitLocale.fromLanguageTag(language)))
    }
    Content(state = state, onAction = viewModel::dispatchAction, onDismiss = onDismiss)
}

@Composable
private fun Content(state: ViewState, onAction: (Action) -> Unit, onDismiss: () -> Unit) {
    val content = state.content
    val showStatusBeforeFilters = LocalDensity.current.fontScale >= 1.5f &&
        (content == ScheduleContent.Loading || content == ScheduleContent.Retrying || content == ScheduleContent.Error)
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(AutomationId.ScheduleScreen),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TransitTopAppBar(
                title = stringResource(Res.string.schedule_title),
                closeButtonModifier = Modifier.testTag(AutomationId.ScheduleBack),
                onClose = onDismiss,
            )
        },
    ) { innerPadding ->
        key(state.routeId, state.directionId, state.date) {
            val listState = rememberLazyListState()
            LaunchedEffect(content, showStatusBeforeFilters) {
                if (showStatusBeforeFilters && content == ScheduleContent.Error) {
                    listState.requestScrollToItem(0)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
                state = listState,
                contentPadding = PaddingValues(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            ) {
                if (showStatusBeforeFilters) {
                    item(key = ScheduleStatusKey) { ScheduleStatus(content, onRetry = { onAction(Action.Retry) }) }
                }
                if (state.route != null) {
                    item(key = "schedule-filters") { ScheduleFilters(state, onAction) }
                }
                if (content is ScheduleContent.Available) {
                    item {
                        Text(
                            stringResource(Res.string.schedule_local_times),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(content.schedule.stops) { stop ->
                        ScheduleStopRow(
                            stop = stop,
                            serviceDate = content.schedule.serviceDate,
                            timeZone = content.schedule.timeZone,
                            locale = state.locale,
                            modifier = Modifier.testTag(AutomationId.ScheduleStop),
                        )
                    }
                } else if (!showStatusBeforeFilters) {
                    item(key = ScheduleStatusKey) { ScheduleStatus(content, onRetry = { onAction(Action.Retry) }) }
                }
            }
        }
    }
}

@Composable
private fun ScheduleStatus(content: ScheduleContent, onRetry: () -> Unit) {
    when (content) {
        ScheduleContent.Loading, ScheduleContent.Retrying -> LoadingState(
            message = stringResource(Res.string.schedule_loading),
            modifier = Modifier.testTag(AutomationId.ScheduleLoading),
            compact = true,
        )
        ScheduleContent.Unavailable -> EmptyState(title = stringResource(Res.string.schedule_unavailable), compact = true)
        ScheduleContent.NoRoutes -> EmptyState(title = stringResource(Res.string.schedule_no_routes), compact = true)
        ScheduleContent.NoTimes -> EmptyState(
            title = stringResource(Res.string.schedule_no_times),
            modifier = Modifier.testTag(AutomationId.ScheduleEmpty),
            compact = true,
        )
        ScheduleContent.Error -> ErrorState(
            title = stringResource(Res.string.schedule_error),
            retryLabel = stringResource(Res.string.routes_retry),
            onRetry = onRetry,
            retryModifier = Modifier.testTag(AutomationId.ScheduleRetry),
            compact = true,
        )
        is ScheduleContent.Available -> Unit
    }
}

private const val ScheduleStatusKey = "schedule-status"

@Preview
@Composable
private fun SchedulePreview() {
    GeorgiaTransitTheme {
        Content(ViewState(date = LocalDate(2026, 11, 19), content = ScheduleContent.NoTimes), {}, {})
    }
}
