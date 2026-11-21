package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.automation.enableAutomationResourceIds
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.schedule_calendar
import georgiatransit.shared.generated.resources.schedule_cancel
import georgiatransit.shared.generated.resources.schedule_date_apply
import georgiatransit.shared.generated.resources.schedule_date_dialog_title
import georgiatransit.shared.generated.resources.schedule_date_edit
import georgiatransit.shared.generated.resources.schedule_date_use_calendar
import georgiatransit.shared.generated.resources.schedule_date_use_input
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDateDialog(
    picker: DatePickerState,
    largeText: Boolean,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = DatePickerDefaults.colors()
        Surface(
            modifier = Modifier.padding(horizontal = TransitSpacing.Small, vertical = TransitSpacing.Small)
                .widthIn(max = 360.dp).fillMaxWidth(),
            shape = DatePickerDefaults.shape,
            color = colors.containerColor,
        ) {
            Column(modifier = Modifier.enableAutomationResourceIds()) {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    if (largeText) ScheduleDateModeHeader(picker)
                    ScheduleDatePicker(picker, compactHeader = largeText)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = TransitSpacing.Medium, vertical = TransitSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(AutomationId.ScheduleDateCancel)) {
                        Text(stringResource(Res.string.schedule_cancel))
                    }
                    TextButton(
                        enabled = picker.selectedDateMillis != null,
                        modifier = Modifier.testTag(AutomationId.ScheduleDateConfirm),
                        onClick = {
                            picker.selectedDateMillis?.let { selectedDate ->
                                onSelected(Instant.fromEpochMilliseconds(selectedDate).toLocalDateTime(TimeZone.UTC).date)
                            }
                        },
                    ) { Text(stringResource(Res.string.schedule_date_apply)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDateModeHeader(picker: DatePickerState) {
    val inputMode = picker.displayMode == DisplayMode.Input
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = TransitSpacing.Medium, top = TransitSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.schedule_date_dialog_title),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        IconButton(
            onClick = {
                focusManager.clearFocus()
                keyboard?.hide()
                picker.displayMode = if (inputMode) DisplayMode.Picker else DisplayMode.Input
            },
            modifier = Modifier.testTag(AutomationId.ScheduleDateModeToggle),
        ) {
            Icon(
                painterResource(if (inputMode) Res.drawable.schedule_calendar else Res.drawable.schedule_date_edit),
                contentDescription = stringResource(
                    if (inputMode) Res.string.schedule_date_use_calendar else Res.string.schedule_date_use_input,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePicker(picker: DatePickerState, compactHeader: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val inputMode = picker.displayMode == DisplayMode.Input
        // Material's calendar has seven columns, 40 dp day cells and 12 dp padding on each side.
        val calendarWidth = maxWidth.coerceAtLeast(304.dp)
        val targetSize = ((calendarWidth - 24.dp) / 7).coerceAtMost(48.dp)
        val compactMonth = compactHeader || calendarWidth < 340.dp
        val dateFormatter = remember(compactMonth) {
            if (compactMonth) DatePickerDefaults.dateFormatter(yearSelectionSkeleton = "yM")
            else DatePickerDefaults.dateFormatter()
        }
        val content = Modifier.testTag(if (inputMode) AutomationId.ScheduleDateInput else AutomationId.ScheduleDateCalendar)
        val pickerModifier = if (!inputMode && calendarWidth > maxWidth) {
            content.horizontalScroll(rememberScrollState()).width(calendarWidth)
        } else {
            content.fillMaxWidth()
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides targetSize) {
            if (compactHeader) {
                DatePicker(
                    state = picker,
                    modifier = pickerModifier,
                    dateFormatter = dateFormatter,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    focusRequester = null,
                )
            } else {
                DatePicker(
                    state = picker,
                    modifier = pickerModifier,
                    dateFormatter = dateFormatter,
                )
            }
        }
    }
}
