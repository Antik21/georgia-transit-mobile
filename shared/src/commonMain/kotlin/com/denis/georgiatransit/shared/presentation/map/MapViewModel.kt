package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import kotlinx.coroutines.flow.combine
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class MapViewModel(
    repository: TransitRepository,
    session: TransitSession,
    private val locationSession: LocationSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            combine(session.selectedCity, session.selectedRouteIds, locationSession.state) { city, selectedIds, location ->
                val routeNames = city?.let { selectedCity ->
                    repository.routes(selectedCity.id).filter { it.id in selectedIds }.map { it.shortName }
                }.orEmpty()
                ViewState(
                    cityName = city?.name.orEmpty(),
                    viewport = city?.let {
                        MapViewport(
                            center = location.fix?.point ?: it.center,
                            contentCenter = it.center,
                            zoom = if (location.fix == null) it.defaultZoom else 15.0,
                        )
                    },
                    selectedRouteNames = routeNames,
                    attribution = city?.attribution.orEmpty(),
                    location = location,
                )
            }.collect { newState -> reduce { newState } }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            Action.RoutesClicked -> intent { postSideEffect(NavigationEffect.OpenRoutes) }
            Action.ChangeCityClicked -> intent { postSideEffect(NavigationEffect.OpenCitySelection) }
            Action.MyLocationClicked -> onMyLocationClicked()
            is Action.LocationEventReceived -> onLocationEvent(action.event)
        }
    }

    private fun onMyLocationClicked() = intent {
        val command = when (val permission = state.location.permission) {
            is LocationPermissionState.Granted -> {
                beginLocationRequest()?.let { postSideEffect(SideEffect.HandleLocation(it)) }
                return@intent
            }
            LocationPermissionState.NotDetermined,
            LocationPermissionState.Denied,
            -> LocationPlatformCommand.RequestPermission(nextCommandId())

            LocationPermissionState.SettingsRequired -> LocationPlatformCommand.OpenAppSettings(nextCommandId())
            LocationPermissionState.ServicesDisabled -> LocationPlatformCommand.OpenLocationSettings(nextCommandId())
            LocationPermissionState.Restricted -> return@intent
            is LocationPermissionState.Unavailable -> {
                if (!permission.canRetry) return@intent
                LocationPlatformCommand.QueryPermission(nextCommandId())
            }
            is LocationPermissionState.Error -> {
                if (!permission.canRetry) return@intent
                LocationPlatformCommand.QueryPermission(nextCommandId())
            }
        }
        postSideEffect(SideEffect.HandleLocation(command))
    }

    private fun onLocationEvent(event: LocationPlatformEvent) = intent {
        when (event) {
            is LocationPlatformEvent.PermissionChanged -> {
                locationSession.updatePermission(event.permission)
                val location = locationSession.state.value
                if (
                    event.permission is LocationPermissionState.Granted &&
                    location.failure == null &&
                    location.fix == null &&
                    location.activeRequestId == null
                ) {
                    beginLocationRequest()?.let { postSideEffect(SideEffect.HandleLocation(it)) }
                }
            }
            is LocationPlatformEvent.FixReceived -> locationSession.accept(event.requestId, event.candidate)
            is LocationPlatformEvent.Failed -> locationSession.fail(event.requestId, event.failure)
            is LocationPlatformEvent.Cancelled -> locationSession.cancelRequest(event.requestId)
        }
    }

    private fun beginLocationRequest(): LocationPlatformCommand.RequestLocation? {
        val requestId = nextCommandId()
        return LocationPlatformCommand.RequestLocation(requestId)
            .takeIf { locationSession.beginLocationRequest(requestId) }
    }

    private fun nextCommandId(): LocationCommandId = locationSession.nextCommandId()
}
