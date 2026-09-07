package com.denis.georgiatransit.shared.presentation.location

import androidx.compose.runtime.Composable

data class LocationCommandId(val value: Long)

sealed interface LocationPlatformCommand {
    val id: LocationCommandId

    data class QueryPermission(override val id: LocationCommandId) : LocationPlatformCommand
    data class RequestPermission(override val id: LocationCommandId) : LocationPlatformCommand
    data class RequestLocation(override val id: LocationCommandId) : LocationPlatformCommand
    data class OpenAppSettings(override val id: LocationCommandId) : LocationPlatformCommand
    data class OpenLocationSettings(override val id: LocationCommandId) : LocationPlatformCommand
}

sealed interface LocationPlatformEvent {
    data class PermissionChanged(val permission: LocationPermissionState) : LocationPlatformEvent
    data class FixReceived(
        val requestId: LocationCommandId,
        val candidate: LocationFixCandidate,
    ) : LocationPlatformEvent

    data class Failed(
        val requestId: LocationCommandId,
        val failure: LocationFailure,
    ) : LocationPlatformEvent

    data class Cancelled(val requestId: LocationCommandId) : LocationPlatformEvent
}

/** Executes foreground-only OS work and re-queries authorization whenever the screen resumes. */
@Composable
expect fun PlatformLocationEffect(
    command: LocationPlatformCommand?,
    onCommandConsumed: () -> Unit,
    onEvent: (LocationPlatformEvent) -> Unit,
)
