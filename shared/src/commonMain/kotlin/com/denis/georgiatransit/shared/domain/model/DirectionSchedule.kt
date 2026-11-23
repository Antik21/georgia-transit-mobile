package com.denis.georgiatransit.shared.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

data class DirectionSchedule(
    val serviceDate: LocalDate,
    val timeZone: TimeZone,
    val stops: List<ScheduledStop>,
)

data class ScheduledStop(
    val id: StopId,
    val name: LocalizedText,
    val arrivalTimes: List<Instant>,
)
