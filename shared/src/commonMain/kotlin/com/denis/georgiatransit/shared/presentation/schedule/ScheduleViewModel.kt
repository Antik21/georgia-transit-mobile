package com.denis.georgiatransit.shared.presentation.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import kotlin.time.Clock

internal class ScheduleViewModel(
    private val cityId: CityId,
    private val repository: TransitRepository,
    private val session: TransitSession,
    today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Tbilisi")).date,
) : ViewModel(), ContainerHost<ViewState, Nothing> {
    private val locale = MutableStateFlow<TransitLocale?>(null)
    private var loadJob: Job? = null
    private var generation = 0L

    override val container: Container<ViewState, Nothing> = viewModelScope.container(
        initialState = ViewState(date = today),
        onCreate = {
            combine(session.selectedCity, locale) { city, language ->
                (city?.id == cityId && city.capabilities.schedules) to language
            }.distinctUntilChanged().collect { (available, language) ->
                when {
                    !available -> {
                        cancelLoad()
                        reduce { ViewState(date = today, content = ScheduleContent.Unavailable) }
                    }
                    language != null -> {
                        val selection = state.copy(routes = emptyList(), locale = language, content = ScheduleContent.Loading)
                        reduce { selection }
                        loadRoutes(selection)
                    }
                }
            }
        },
    )

    fun dispatchAction(action: Action) = intent {
        if (action is Action.LocaleChanged) {
            locale.value = action.locale
            return@intent
        }
        if (!isAvailable() || locale.value != state.locale) return@intent

        val updated = when (action) {
            is Action.RouteSelected -> {
                val route = state.routes.firstOrNull { it.id == action.routeId } ?: return@intent
                state.copy(routeId = route.id, directionId = route.directions.first().id)
            }
            is Action.DirectionSelected -> {
                if (state.route?.directions?.none { it.id == action.directionId } != false) return@intent
                state.copy(directionId = action.directionId)
            }
            is Action.DateSelected -> {
                if (action.date.year !in ScheduleYears || state.route == null) return@intent
                state.copy(date = action.date)
            }
            Action.Retry -> state
            is Action.LocaleChanged -> state
        }
        if (updated == state && !(action == Action.Retry && state.content == ScheduleContent.Error)) return@intent
        reduce { updated.copy(content = ScheduleContent.Loading) }
        if (updated.route == null) loadRoutes(updated) else loadSchedule(updated)
    }

    private fun loadRoutes(selection: ViewState) {
        val request = RouteListRequest(cityId, selection.locale)
        val version = cancelLoad()
        loadJob = viewModelScope.launch {
            val result = repository.refreshRoutes(request)
            ensureActive()
            intent {
                if (!isCurrent(version, selection)) return@intent
                val routes = (result as? TransitLoadResult.Data)?.value.orEmpty()
                if (result is TransitLoadResult.Failure || routes.any { it.cityId != cityId }) {
                    reduce { state.copy(content = ScheduleContent.Error) }
                    return@intent
                }
                val withDirections = routes.filter { it.directions.isNotEmpty() }
                val route = withDirections.firstOrNull { it.id == selection.routeId } ?: withDirections.firstOrNull()
                val direction = route?.directions?.firstOrNull { it.id == selection.directionId } ?: route?.directions?.first()
                val updated = state.copy(
                    routes = withDirections,
                    routeId = route?.id,
                    directionId = direction?.id,
                    content = if (route == null) ScheduleContent.NoRoutes else ScheduleContent.Loading,
                )
                reduce { updated }
                if (route != null) loadSchedule(updated)
            }
        }
    }

    private fun loadSchedule(selection: ViewState) {
        val routeId = checkNotNull(selection.routeId)
        val directionId = checkNotNull(selection.directionId)
        val version = cancelLoad()
        loadJob = viewModelScope.launch {
            val result = repository.directionSchedule(cityId, routeId, directionId, selection.date, selection.locale)
            ensureActive()
            intent {
                if (!isCurrent(version, selection)) return@intent
                val content = when (result) {
                    is TransitLoadResult.Data -> if (result.value.stops.any { it.arrivalTimes.isNotEmpty() }) {
                        ScheduleContent.Available(result.value)
                    } else {
                        ScheduleContent.NoTimes
                    }
                    is TransitLoadResult.Empty -> ScheduleContent.NoTimes
                    is TransitLoadResult.Failure -> ScheduleContent.Error
                }
                reduce { state.copy(content = content) }
            }
            if (result is TransitLoadResult.Failure && result.error is TransitFailure.CapabilityUnavailable) {
                repository.revalidateCityCapabilities()
            }
        }
    }

    private fun cancelLoad(): Long {
        loadJob?.cancel()
        return ++generation
    }

    private fun isCurrent(version: Long, selection: ViewState): Boolean =
        version == generation && isAvailable() && locale.value == selection.locale

    private fun isAvailable(): Boolean = session.selectedCity.value.let { city ->
        city?.id == cityId && city.capabilities.schedules
    }
}
