package com.denis.georgiatransit.shared.presentation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.denis.georgiatransit.shared.domain.model.ScheduledStop
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.schedule_following_day
import georgiatransit.shared.generated.resources.schedule_stop_empty
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ScheduleStopRow(
    stop: ScheduledStop,
    serviceDate: LocalDate,
    timeZone: TimeZone,
    locale: TransitLocale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = TransitSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
    ) {
        Text(
            text = stop.name.forScheduleLocale(locale),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        if (stop.arrivalTimes.isEmpty()) {
            Text(stringResource(Res.string.schedule_stop_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            val labels = stop.arrivalTimes.map { instant ->
                val time = instant.toLocalDateTime(timeZone)
                val offset = time.date.toEpochDays() - serviceDate.toEpochDays()
                val clock = time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')
                if (offset == 0L) clock else stringResource(Res.string.schedule_following_day, clock, offset.toInt())
            }
            ScheduleTimes(labels)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ScheduleTimes(labels: List<String>) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val textWidth = remember(labels, style, textMeasurer) {
        labels.maxOf { textMeasurer.measure(it, style = style, softWrap = false, maxLines = 1).size.width }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = with(density) { TransitSpacing.Small.roundToPx() }
        val minimumWidth = maxOf(textWidth, with(density) { 52.dp.roundToPx() })
        val columns = ((constraints.maxWidth + gap) / (minimumWidth + gap)).coerceAtLeast(1)
        val width = with(density) { ((constraints.maxWidth - (columns - 1) * gap) / columns).toDp() }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
            maxItemsInEachRow = columns,
        ) {
            labels.forEach { label -> Text(label, modifier = Modifier.width(width), style = style) }
        }
    }
}
