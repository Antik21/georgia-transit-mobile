package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationSession

/** Translates location actions and platform replies into the next one-shot command. */
internal class MapLocationActions(private val session: LocationSession) {
    fun onClick(permission: LocationPermissionState): LocationPlatformCommand? = when (permission) {
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

    fun onEvent(event: LocationPlatformEvent): LocationPlatformCommand? {
        when (event) {
            is LocationPlatformEvent.PermissionChanged -> {
                session.updatePermission(event.permission)
                val location = session.state.value
                if (event.permission is LocationPermissionState.Granted && location.failure == null &&
                    location.fix == null && location.activeRequestId == null) {
                    return beginLocationRequest()
                }
            }
            is LocationPlatformEvent.FixReceived -> session.accept(event.requestId, event.candidate)
            is LocationPlatformEvent.Failed -> session.fail(event.requestId, event.failure)
            is LocationPlatformEvent.Cancelled -> session.cancelRequest(event.requestId)
        }
        return null
    }

    fun refresh(): LocationPlatformCommand.RequestLocation? = beginLocationRequest(isRefresh = true)

    private fun beginLocationRequest(isRefresh: Boolean = false): LocationPlatformCommand.RequestLocation? {
        val requestId = session.nextCommandId()
        return LocationPlatformCommand.RequestLocation(requestId, isRefresh)
            .takeIf { session.beginLocationRequest(requestId, isRefresh) }
    }
}
