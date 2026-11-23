package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession

/** Translates location actions and platform replies into the next one-shot command. */
internal class MapLocationActions(private val session: LocationSession) {
    private var cameraAwaitingPermission = false
    private var cameraRequestId: LocationCommandId? = null

    fun forAction(action: Action, visible: Boolean): MapLocationUpdate = when (action) {
        Action.MyLocationClicked -> {
            val command = onClick(session.state.value.permission)
            cameraAwaitingPermission = command != null && command !is LocationPlatformCommand.RequestLocation
            // A tap can adopt a silent foreground refresh already in flight.
            val location = session.state.value
            cameraRequestId = location.activeRequestId.takeIf { location.isLocating }
            MapLocationUpdate(command)
        }
        Action.RefreshLocation -> MapLocationUpdate(if (visible) refresh() else null)
        is Action.LocationEventReceived -> onEvent(action.event)
        else -> MapLocationUpdate()
    }

    private fun onClick(permission: LocationPermissionState): LocationPlatformCommand? = when (permission) {
        is LocationPermissionState.Granted -> beginLocationRequest()
        LocationPermissionState.NotDetermined,
        LocationPermissionState.Denied -> LocationPlatformCommand.RequestPermission(session.nextCommandId())
        LocationPermissionState.SettingsRequired -> LocationPlatformCommand.OpenAppSettings(session.nextCommandId())
        LocationPermissionState.ServicesDisabled -> LocationPlatformCommand.OpenLocationSettings(session.nextCommandId())
        LocationPermissionState.Restricted -> null
        is LocationPermissionState.Unavailable -> if (permission.canRetry) {
            LocationPlatformCommand.QueryPermission(session.nextCommandId())
        } else null
        is LocationPermissionState.Error -> if (permission.canRetry) {
            LocationPlatformCommand.QueryPermission(session.nextCommandId())
        } else null
    }

    private fun onEvent(event: LocationPlatformEvent): MapLocationUpdate {
        val activeRequestId = session.state.value.activeRequestId
        return when (event) {
            is LocationPlatformEvent.PermissionChanged -> onPermissionChanged(event.permission)
            is LocationPlatformEvent.FixReceived -> {
                session.accept(event.requestId, event.candidate)
                finishCameraRequest(event.requestId, activeRequestId)
            }
            is LocationPlatformEvent.Failed -> {
                session.fail(event.requestId, event.failure)
                finishCameraRequest(event.requestId, activeRequestId)
            }
            is LocationPlatformEvent.Cancelled -> {
                session.cancelRequest(event.requestId)
                if (cameraRequestId == event.requestId) cameraRequestId = null
                MapLocationUpdate()
            }
        }
    }

    private fun onPermissionChanged(permission: LocationPermissionState): MapLocationUpdate {
        session.updatePermission(permission)
        val location = session.state.value
        if (cameraRequestId != location.activeRequestId) cameraRequestId = null
        val command = if (permission is LocationPermissionState.Granted && location.failure == null &&
            location.fix == null && location.activeRequestId == null) {
            beginLocationRequest(isRefresh = !cameraAwaitingPermission)
        } else null
        if (cameraAwaitingPermission && permission != LocationPermissionState.NotDetermined) {
            cameraAwaitingPermission = false
            cameraRequestId = command?.id
        }
        return MapLocationUpdate(command)
    }

    private fun finishCameraRequest(requestId: LocationCommandId, previousActiveRequestId: LocationCommandId?): MapLocationUpdate {
        if (cameraRequestId != requestId) return MapLocationUpdate()
        cameraRequestId = null
        return MapLocationUpdate(showCameraError = previousActiveRequestId == requestId && session.state.value.failure != null)
    }

    private fun refresh(): LocationPlatformCommand.RequestLocation? = beginLocationRequest(isRefresh = true)

    /** The planner asks for location only through the same explicit "My location" flow. */
    fun forPlanner(request: JourneyLocationRequest?, permission: LocationPermissionState): LocationPlatformCommand? =
        when (request) {
            JourneyLocationRequest.Explicit -> onClick(permission)
            JourneyLocationRequest.Refresh -> refresh()
            null -> null
        }

    private fun beginLocationRequest(isRefresh: Boolean = false): LocationPlatformCommand.RequestLocation? {
        val requestId = session.nextCommandId()
        return LocationPlatformCommand.RequestLocation(requestId, isRefresh)
            .takeIf { session.beginLocationRequest(requestId, isRefresh) }
    }
}

internal data class MapLocationUpdate(val command: LocationPlatformCommand? = null, val showCameraError: Boolean = false)
