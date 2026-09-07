package com.denis.georgiatransit.shared.presentation.cityselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.viewmodel.container

class CitySelectionViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
    private val locationSession: LocationSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    private val catalogRefreshGate = RequestGate()
    private val cityConfirmationGate = RequestGate()
    private var currentCatalog: List<TransitCity> = emptyList()

    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            refreshCatalog()
            locationSession.state.collect { location -> reduce { state.copy(location = location) } }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.CityClicked -> onCityClicked(action.cityId)
            Action.RetryClicked -> refreshCatalog()
            Action.ContinueClicked -> onContinueClicked()
            Action.LocationClicked -> onLocationClicked()
            is Action.LocationEventReceived -> onLocationEvent(action.event)
        }
    }

    private fun onCityClicked(cityId: CityId) = intent {
        if (
            state.catalog is CityCatalogState.Populated &&
            state.cities.any { it.id == cityId && it.isEnabled }
        ) {
            reduce { state.copy(selectedCityId = cityId) }
        }
    }

    private fun refreshCatalog() {
        if (!catalogRefreshGate.tryClaim()) return
        intent {
            try {
                val previousSelection = state.selectedCityId
                currentCatalog = emptyList()
                reduce {
                    state.copy(
                        cities = emptyList(),
                        selectedCityId = null,
                        catalog = CityCatalogState.Loading,
                    )
                }
                when (val result = repository.refreshCityCapabilities()) {
                    is TransitLoadResult.Data -> {
                        val snapshot = result.value.toList()
                        currentCatalog = snapshot
                        if (snapshot.isEmpty()) {
                            reduce {
                                state.copy(
                                    cities = emptyList(),
                                    selectedCityId = null,
                                    catalog = CityCatalogState.Empty(result.freshness, result.revalidationFailure),
                                )
                            }
                        } else {
                            reduce {
                                state.copy(
                                    cities = snapshot.map { it.toCityItemUiModel() },
                                    selectedCityId = selectedIdIn(snapshot, previousSelection),
                                    catalog = CityCatalogState.Populated(result.freshness, result.revalidationFailure),
                                )
                            }
                        }
                    }

                    is TransitLoadResult.Empty -> {
                        currentCatalog = emptyList()
                        reduce {
                            state.copy(
                                cities = emptyList(),
                                selectedCityId = null,
                                catalog = CityCatalogState.Empty(result.freshness, result.revalidationFailure),
                            )
                        }
                    }

                    is TransitLoadResult.Failure -> {
                        currentCatalog = emptyList()
                        reduce {
                            state.copy(
                                cities = emptyList(),
                                selectedCityId = null,
                                catalog = CityCatalogState.RetryableError(result.error),
                            )
                        }
                    }
                }
            } finally {
                catalogRefreshGate.release()
            }
        }
    }

    private fun selectedIdIn(snapshot: List<TransitCity>, previousSelection: CityId?): CityId? {
        val candidate = previousSelection ?: session.selectedCity.value?.id
        return candidate?.takeIf { selectedId ->
            snapshot.any { city -> city.id == selectedId && city.capabilities.stops }
        }
    }

    private fun TransitCity.toCityItemUiModel(): CityItemUiModel = CityItemUiModel(
        id = id,
        name = name,
        localizedName = localizedName,
        isEnabled = capabilities.stops,
        isExperimental = capabilities.experimental,
        attribution = attribution,
    )

    private fun onContinueClicked() {
        if (!cityConfirmationGate.tryClaim()) return
        intent {
            var confirmationPosted = false
            try {
                val selectedId = state.selectedCityId ?: return@intent
                val city = currentCatalog.firstOrNull { it.id == selectedId && it.capabilities.stops }
                    ?: run {
                        reduce { state.copy(selectedCityId = null) }
                        return@intent
                    }
                reduce { state.copy(isConfirming = true) }
                session.selectCity(city)
                postSideEffect(NavigationEffect.OpenMap)
                confirmationPosted = true
            } finally {
                if (!confirmationPosted) {
                    reduce { state.copy(isConfirming = false) }
                    cityConfirmationGate.release()
                }
            }
        }
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

@OptIn(ExperimentalAtomicApi::class)
private class RequestGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(expectedValue = false, newValue = true)

    fun release() {
        claimed.store(false)
    }
}
