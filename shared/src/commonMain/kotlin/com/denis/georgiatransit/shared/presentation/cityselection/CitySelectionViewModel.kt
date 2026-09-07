package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.viewmodel.container

class CitySelectionViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val locationSession: LocationSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            reduce {
                ViewState(
                    cities = repository.cities().map { city ->
                        CityItemUiModel(
                            id = city.id,
                            name = city.name,
                            isEnabled = city.capabilities.stops,
                            isExperimental = city.capabilities.experimental,
                        )
                    },
                    selectedCityId = session.selectedCity.value?.id,
                    location = locationSession.state.value,
                )
            }
            locationSession.state.collect { location -> reduce { state.copy(location = location) } }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.CityClicked -> onCityClicked(action.cityId)
            Action.ContinueClicked -> onContinueClicked()
            Action.LocationClicked -> onLocationClicked()
            is Action.LocationEventReceived -> onLocationEvent(action.event)
        }
    }

    private fun onCityClicked(cityId: CityId) = intent {
        if (state.cities.any { it.id == cityId && it.isEnabled }) reduce { state.copy(selectedCityId = cityId) }
    }

    private fun onContinueClicked() = intent {
        val selectedId = state.selectedCityId ?: return@intent
        val city = repository.cities().firstOrNull { it.id == selectedId } ?: return@intent
        session.selectCity(city)
        postSideEffect(NavigationEffect.OpenMap)
    }

    private fun onLocationClicked() = intent {
        val command = when (val permission = state.location.permission) {
            LocationPermissionState.NotDetermined,
            LocationPermissionState.Denied,
            -> LocationPlatformCommand.RequestPermission(nextCommandId())

            is LocationPermissionState.Granted -> {
                beginLocationRequest()?.let { postSideEffect(SideEffect.HandleLocation(it)) }
                return@intent
            }
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
