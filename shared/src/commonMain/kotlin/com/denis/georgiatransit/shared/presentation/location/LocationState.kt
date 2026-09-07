package com.denis.georgiatransit.shared.presentation.location

import androidx.compose.runtime.Immutable
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlinx.coroutines.flow.StateFlow

@Immutable
enum class LocationPrecision {
    Precise,
    Approximate,
}

@Immutable
sealed interface LocationPermissionState {
    data object NotDetermined : LocationPermissionState
    data class Granted(val precision: LocationPrecision) : LocationPermissionState
    data object Denied : LocationPermissionState
    data object SettingsRequired : LocationPermissionState
    data object Restricted : LocationPermissionState
    data object ServicesDisabled : LocationPermissionState
    data class Unavailable(val canRetry: Boolean) : LocationPermissionState
    data class Error(val canRetry: Boolean) : LocationPermissionState
}

@Immutable
data class UserLocationFix(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val precision: LocationPrecision,
    val capturedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Immutable
enum class LocationFailure {
    TimedOut,
    InvalidFix,
    Unavailable,
}

@Immutable
data class LocationState(
    val permission: LocationPermissionState = LocationPermissionState.NotDetermined,
    val fix: UserLocationFix? = null,
    val isLocating: Boolean = false,
    val failure: LocationFailure? = null,
    val activeRequestId: LocationCommandId? = null,
)

@Immutable
data class LocationFixCandidate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAtEpochMillis: Long,
)

interface LocationSession {
    val state: StateFlow<LocationState>

    fun nextCommandId(): LocationCommandId
    fun updatePermission(permission: LocationPermissionState)
    fun beginLocationRequest(requestId: LocationCommandId): Boolean
    fun accept(requestId: LocationCommandId, candidate: LocationFixCandidate)
    fun fail(requestId: LocationCommandId, failure: LocationFailure)
    fun cancelRequest(requestId: LocationCommandId)
}

internal const val MAX_FIX_AGE_MILLIS = 120_000L
internal const val MAX_PRECISE_ACCURACY_METERS = 250.0
internal const val MAX_APPROXIMATE_ACCURACY_METERS = 5_000.0
