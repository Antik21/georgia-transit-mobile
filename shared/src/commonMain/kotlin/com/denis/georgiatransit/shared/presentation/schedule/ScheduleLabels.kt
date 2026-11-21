package com.denis.georgiatransit.shared.presentation.schedule

import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale

internal fun LocalizedText.forScheduleLocale(locale: TransitLocale): String =
    forLocale(locale).ifBlank { en }.ifBlank { ka }.ifBlank { ru }

internal fun TransitDirection.scheduleDirectionLabel(locale: TransitLocale, routeNumber: String): String {
    val label = headsign.forScheduleLocale(locale).trim()
    return if (label.isBlank() || label.equals(routeNumber.trim(), ignoreCase = true)) {
        name.forScheduleLocale(locale).trim().ifBlank { label }
    } else {
        label
    }
}
