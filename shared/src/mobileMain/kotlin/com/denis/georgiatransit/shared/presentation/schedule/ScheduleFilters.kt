package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.presentation.map.sections.ChevronDownIcon
import com.denis.georgiatransit.shared.presentation.ui.RouteCatalogComparator
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.schedule_calendar
import georgiatransit.shared.generated.resources.schedule_date
import georgiatransit.shared.generated.resources.schedule_direction
import georgiatransit.shared.generated.resources.schedule_filter_value
import georgiatransit.shared.generated.resources.schedule_next_day
import georgiatransit.shared.generated.resources.schedule_previous_day
import georgiatransit.shared.generated.resources.schedule_route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ScheduleFilters(state: ViewState, onAction: (Action) -> Unit) {
    val route = state.route ?: return
    val direction = route.directions.firstOrNull { it.id == state.directionId } ?: return
    val locale = state.locale
    val routeOptions = remember(state.routes) { state.routes.sortedWith(RouteCatalogComparator) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = TransitSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        ScheduleDropdown(
            label = stringResource(Res.string.schedule_route),
            selected = route,
            options = routeOptions,
            optionLabel = { it.scheduleLabel(locale) },
            onSelected = { onAction(Action.RouteSelected(it.id)) },
            tag = AutomationId.ScheduleRoute,
            optionTag = AutomationId.ScheduleRouteOption,
        )
        ScheduleDropdown(
            label = stringResource(Res.string.schedule_direction),
            selected = direction,
            options = route.directions,
            optionLabel = { ScheduleOptionLabel(it.scheduleDirectionLabel(locale, route.shortName)) },
            onSelected = { onAction(Action.DirectionSelected(it.id)) },
            tag = AutomationId.ScheduleDirection,
            optionTag = AutomationId.ScheduleDirectionOption,
        )
        ScheduleDate(state.date) { onAction(Action.DateSelected(it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDate(date: LocalDate, onSelected: (LocalDate) -> Unit) {
    var calendarOpen by remember { mutableStateOf(false) }
    val dateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val calendar = key(date, calendarOpen, Locale.current) {
        rememberDatePickerState(
            initialSelectedDateMillis = dateMillis,
            yearRange = ScheduleYears,
            initialDisplayMode = if (largeText) DisplayMode.Input else DisplayMode.Picker,
        )
    }
    val formatter = remember { DatePickerDefaults.dateFormatter(selectedDateSkeleton = "yMMMEd") }
    val dateText = formatter.formatDate(dateMillis, calendar.locale) ?: date.toString()
    val spokenDate = formatter.formatDate(dateMillis, calendar.locale, forContentDescription = true) ?: dateText
    val dateLabel = stringResource(Res.string.schedule_date)
    val description = stringResource(Res.string.schedule_filter_value, dateLabel, spokenDate)
    val previousArrowAngle = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 90f else -90f
    Column {
        Text(dateLabel, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previous = date.plus(-1, DateTimeUnit.DAY)
            val next = date.plus(1, DateTimeUnit.DAY)
            OutlinedIconButton(
                onClick = { onSelected(previous) },
                enabled = previous.year in ScheduleYears,
                modifier = Modifier.testTag(AutomationId.SchedulePreviousDate),
            ) {
                Icon(ChevronDownIcon, stringResource(Res.string.schedule_previous_day), Modifier.rotate(previousArrowAngle))
            }
            OutlinedButton(
                onClick = { calendarOpen = true },
                modifier = Modifier.weight(1f).testTag(AutomationId.ScheduleDate)
                    .semantics { contentDescription = description },
                contentPadding = PaddingValues(horizontal = TransitSpacing.Small, vertical = TransitSpacing.Small),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(Res.drawable.schedule_calendar), contentDescription = null)
                    Text(
                        dateText,
                        modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
                        textAlign = TextAlign.Center,
                    )
                }
            }
            OutlinedIconButton(
                onClick = { onSelected(next) },
                enabled = next.year in ScheduleYears,
                modifier = Modifier.testTag(AutomationId.ScheduleNextDate),
            ) {
                Icon(ChevronDownIcon, stringResource(Res.string.schedule_next_day), Modifier.rotate(-previousArrowAngle))
            }
        }
    }
    if (calendarOpen) {
        ScheduleDateDialog(
            picker = calendar,
            largeText = largeText,
            onDismiss = { calendarOpen = false },
            onSelected = {
                onSelected(it)
                calendarOpen = false
            },
        )
    }
}

private fun TransitRoute.scheduleLabel(locale: TransitLocale): ScheduleOptionLabel {
    val number = shortName.trim()
    val title = longName.forScheduleLocale(locale).ifBlank { name }.trim().ifBlank { number }
    return ScheduleOptionLabel(title, number.takeIf { it.isNotBlank() && it != title })
}
