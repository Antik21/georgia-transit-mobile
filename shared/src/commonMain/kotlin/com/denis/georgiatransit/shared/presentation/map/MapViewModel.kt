package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import kotlinx.coroutines.flow.combine
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class MapViewModel(
    repository: TransitRepository,
    session: TransitSession,
    private val locationSession: LocationSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    private var lastCityId: CityId? = null
    private var lastAcceptedFix: UserLocationFix? = null
    private var cameraCommand: MapCameraCommand? = null
    private var nextCameraRevision = 0L

    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            combine(session.selectedCity, session.selectedRouteIds, locationSession.state) { city, selectedIds, location ->
                val routeNames = city?.let { selectedCity ->
                    repository.routes(selectedCity.id).filter { it.id in selectedIds }.map { it.shortName }
                }.orEmpty()
                ViewState(
                    cityName = city?.name.orEmpty(),
                    renderState = city?.let { renderStateFor(it, location.fix) },
                    // The BFF has no reviewed map-style/asset endpoint yet. This is an explicit
                    // local fallback, not a remotely sourced or synthetic transit experience.
                    contentState = MapContentState.LocalPreview,
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

    private fun renderStateFor(
        city: TransitCity,
        fix: UserLocationFix?,
    ): MapRenderState {
        val isCityChange = lastCityId != null && lastCityId != city.id
        if (lastCityId != city.id || cameraCommand == null) {
            lastCityId = city.id
            cameraCommand = nextCameraCommand(center = city.center, zoom = city.defaultZoom)
            // A city change deliberately centers on that city. Record an unchanged existing fix
            // only for comparison so it remains a marker instead of immediately overriding the
            // city camera; a new/refreshed fix below still recenters as intended.
            lastAcceptedFix = if (isCityChange) fix else null
        }
        if (fix == null) {
            // Losing an old fix changes the marker only. It must never recenter a user-panned map.
            lastAcceptedFix = null
        } else if (fix != lastAcceptedFix) {
            lastAcceptedFix = fix
            cameraCommand = nextCameraCommand(center = fix.point, zoom = USER_LOCATION_ZOOM)
        }
        return MapRenderState(camera = checkNotNull(cameraCommand), userLocation = fix)
    }

    private fun nextCameraCommand(
        center: GeoPoint,
        zoom: Double,
    ): MapCameraCommand = MapCameraCommand(center = center, zoom = zoom, revision = ++nextCameraRevision)

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

    private companion object {
        const val USER_LOCATION_ZOOM = 15.0
    }
}
