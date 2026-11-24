package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.DirectionSchedule
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.ScheduledStop
import com.denis.georgiatransit.shared.domain.model.StopId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

@Serializable
internal data class DirectionScheduleDto(
    val routeId: String,
    val directionId: String,
    val serviceDate: String,
    val timeZone: String,
    val stops: List<ScheduledStopDto>,
) {
    fun toDomain(route: RouteId, direction: DirectionId, date: LocalDate): DirectionSchedule {
        require(routeId == route.value && directionId == direction.value && serviceDate == date.toString()) {
            "Schedule does not match the requested route, direction and date"
        }
        return DirectionSchedule(
            serviceDate = date,
            timeZone = TimeZone.of(timeZone),
            stops = stops.map { stop ->
                ScheduledStop(
                    id = StopId(stop.stopId.requirePublicId("schedule stop id")),
                    name = stop.name.toDomain(),
                    arrivalTimes = stop.arrivalTimes.map { it.toUtcInstant("scheduled arrival") },
                )
            },
        )
    }
}

@Serializable
internal data class ScheduledStopDto(
    val stopId: String,
    val name: LocalizedTextDto,
    val arrivalTimes: List<String>,
)
