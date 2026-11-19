package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.presentation.map.sections.ChevronDownIcon
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.schedule_cancel
import georgiatransit.shared.generated.resources.schedule_date
import georgiatransit.shared.generated.resources.schedule_direction
import georgiatransit.shared.generated.resources.schedule_next_day
import georgiatransit.shared.generated.resources.schedule_previous_day
import georgiatransit.shared.generated.resources.schedule_route
import georgiatransit.shared.generated.resources.schedule_select_date
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun ScheduleFilters(state: ViewState, onAction: (Action) -> Unit) {
    val route = state.route ?: return
    val direction = route.directions.firstOrNull { it.id == state.directionId } ?: return
    val locale = state.locale
    val directionName: (TransitDirection) -> String = {
        it.headsign.forScheduleLocale(locale).ifBlank { it.name.forScheduleLocale(locale) }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = TransitSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        ScheduleDropdown(
            label = stringResource(Res.string.schedule_route),
            selected = route,
            options = state.routes,
            name = { it.scheduleLabel(locale) },
            onSelected = { onAction(Action.RouteSelected(it.id)) },
            tag = AutomationId.ScheduleRoute,
            optionTag = AutomationId.ScheduleRouteOption,
        )
        ScheduleDropdown(
            label = stringResource(Res.string.schedule_direction),
            selected = direction,
            options = route.directions,
            name = directionName,
            onSelected = { onAction(Action.DirectionSelected(it.id)) },
            tag = AutomationId.ScheduleDirection,
            optionTag = AutomationId.ScheduleDirectionOption,
        )
        ScheduleDate(state.date) { onAction(Action.DateSelected(it)) }
    }
}

@Composable
private fun <T> ScheduleDropdown(
    label: String,
    selected: T,
    options: List<T>,
    name: (T) -> String,
    onSelected: (T) -> Unit,
    tag: String,
    optionTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = options.size > 1,
                modifier = Modifier.fillMaxWidth().testTag(tag),
            ) {
                Text(name(selected), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(ChevronDownIcon, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.enableAutomationResourceIds(),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(name(option)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                        modifier = Modifier.testTag(optionTag),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDate(date: LocalDate, onSelected: (LocalDate) -> Unit) {
    var calendarOpen by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(Res.string.schedule_date), style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previous = date.plus(-1, DateTimeUnit.DAY)
            val next = date.plus(1, DateTimeUnit.DAY)
            OutlinedButton(
                onClick = { onSelected(previous) },
                enabled = previous.year in ScheduleYears,
                modifier = Modifier.testTag(AutomationId.SchedulePreviousDate),
            ) {
                Icon(ChevronDownIcon, stringResource(Res.string.schedule_previous_day), Modifier.rotate(90f))
            }
            OutlinedButton(
                onClick = { calendarOpen = true },
                modifier = Modifier.weight(1f).testTag(AutomationId.ScheduleDate),
            ) { Text(date.toString()) }
            OutlinedButton(
                onClick = { onSelected(next) },
                enabled = next.year in ScheduleYears,
                modifier = Modifier.testTag(AutomationId.ScheduleNextDate),
            ) {
                Icon(ChevronDownIcon, stringResource(Res.string.schedule_next_day), Modifier.rotate(-90f))
            }
        }
    }
    if (calendarOpen) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = ScheduleYears,
        )
        DatePickerDialog(
            onDismissRequest = { calendarOpen = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    modifier = Modifier.enableAutomationResourceIds().testTag(AutomationId.ScheduleDateConfirm),
                    onClick = {
                        picker.selectedDateMillis?.let {
                            onSelected(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date)
                        }
                        calendarOpen = false
                    },
                ) { Text(stringResource(Res.string.schedule_select_date)) }
            },
            dismissButton = {
                TextButton(onClick = { calendarOpen = false }) { Text(stringResource(Res.string.schedule_cancel)) }
            },
        ) { DatePicker(picker) }
    }
}

internal fun LocalizedText.forScheduleLocale(locale: TransitLocale): String =
    forLocale(locale).ifBlank { en }.ifBlank { ka }.ifBlank { ru }

private fun TransitRoute.scheduleLabel(locale: TransitLocale): String {
    val localized = longName.forScheduleLocale(locale).ifBlank { name }
    return if (shortName.isBlank() || shortName == localized) localized else "$shortName · $localized"
}
