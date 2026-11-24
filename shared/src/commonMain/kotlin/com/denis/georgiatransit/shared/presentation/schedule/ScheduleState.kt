package com.denis.georgiatransit.shared.presentation.schedule

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.DirectionSchedule
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import kotlinx.datetime.LocalDate

internal val ScheduleYears = 1970..2100

internal data class ViewState(
    val date: LocalDate,
    val locale: TransitLocale = TransitLocale.English,
    val routes: List<TransitRoute> = emptyList(),
    val routeId: RouteId? = null,
    val directionId: DirectionId? = null,
    val content: ScheduleContent = ScheduleContent.Loading,
) {
    val route: TransitRoute? get() = routes.firstOrNull { it.id == routeId }
}

internal sealed interface ScheduleContent {
    data object Loading : ScheduleContent
    data object Retrying : ScheduleContent
    data object Unavailable : ScheduleContent
    data object NoRoutes : ScheduleContent
    data object NoTimes : ScheduleContent
    data object Error : ScheduleContent
    data class Available(val schedule: DirectionSchedule) : ScheduleContent
}

internal sealed interface Action {
    data class LocaleChanged(val locale: TransitLocale) : Action
    data class RouteSelected(val routeId: RouteId) : Action
    data class DirectionSelected(val directionId: DirectionId) : Action
    data class DateSelected(val date: LocalDate) : Action
    data object Retry : Action
}
