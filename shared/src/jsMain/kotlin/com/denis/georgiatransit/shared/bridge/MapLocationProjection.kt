@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.LocationState
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import com.denis.georgiatransit.shared.presentation.location.accuracyPolygon

/** [actionable] mirrors the mobile button: disabled while locating or when retrying cannot help. */
@JsExport
class LocationStatus(
    val permission: String,
    val precise: Boolean,
    val locating: Boolean,
    val failure: String?,
    val actionable: Boolean,
)

/** [showDot] is false for approximate fixes, which render only their accuracy area. */
@JsExport
class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val showDot: Boolean,
    val accuracyRing: Array<RoutePoint>,
)

@JsExport
class LocationCommand(val id: String, val kind: String, val isRefresh: Boolean)

internal fun LocationState.toJs(): LocationStatus {
    val permission = permission
    val canAct = when (permission) {
        LocationPermissionState.Restricted -> false
        is LocationPermissionState.Unavailable -> permission.canRetry
        is LocationPermissionState.Error -> permission.canRetry
        else -> true
    }
    return LocationStatus(
        permission = when (permission) {
            LocationPermissionState.NotDetermined -> "notDetermined"
            is LocationPermissionState.Granted -> "granted"
            LocationPermissionState.Denied -> "denied"
            LocationPermissionState.SettingsRequired -> "settingsRequired"
            LocationPermissionState.Restricted -> "restricted"
            LocationPermissionState.ServicesDisabled -> "servicesDisabled"
            is LocationPermissionState.Unavailable -> "unavailable"
            is LocationPermissionState.Error -> "error"
        },
        precise = (permission as? LocationPermissionState.Granted)?.precision == LocationPrecision.Precise,
        locating = isLocating,
        failure = when (failure) {
            LocationFailure.TimedOut -> "timedOut"
            LocationFailure.InvalidFix -> "invalidFix"
            LocationFailure.Unavailable -> "unavailable"
            null -> null
        },
        actionable = canAct && !isLocating,
    )
}

internal fun UserLocationFix.toJs(): UserLocation? {
    if (!point.isFiniteCoordinate() || !accuracyMeters.isFinite() || accuracyMeters < 0.0) return null
    return UserLocation(
        latitude = point.latitude,
        longitude = point.longitude,
        accuracyMeters = accuracyMeters,
        showDot = precision == LocationPrecision.Precise,
        accuracyRing = accuracyPolygon(this).map { RoutePoint(it.latitude, it.longitude) }.toTypedArray(),
    )
}

internal fun LocationPlatformCommand.toJs() = LocationCommand(
    id = id.value.toString(),
    kind = when (this) {
        is LocationPlatformCommand.QueryPermission -> "queryPermission"
        is LocationPlatformCommand.RequestPermission -> "requestPermission"
        is LocationPlatformCommand.RequestLocation -> "requestLocation"
        is LocationPlatformCommand.OpenAppSettings -> "openAppSettings"
        is LocationPlatformCommand.OpenLocationSettings -> "openLocationSettings"
    },
    isRefresh = (this as? LocationPlatformCommand.RequestLocation)?.isRefresh == true,
)

/** Unknown host strings are rejected rather than guessed, so a host bug cannot grant access. */
internal fun permissionEvent(state: String, precise: Boolean, canRetry: Boolean): LocationPlatformEvent? {
    val permission = when (state) {
        "notDetermined" -> LocationPermissionState.NotDetermined
        "granted" -> LocationPermissionState.Granted(if (precise) LocationPrecision.Precise else LocationPrecision.Approximate)
        "denied" -> LocationPermissionState.Denied
        "settingsRequired" -> LocationPermissionState.SettingsRequired
        "restricted" -> LocationPermissionState.Restricted
        "servicesDisabled" -> LocationPermissionState.ServicesDisabled
        "unavailable" -> LocationPermissionState.Unavailable(canRetry)
        "error" -> LocationPermissionState.Error(canRetry)
        else -> return null
    }
    return LocationPlatformEvent.PermissionChanged(permission)
}

internal fun fixEvent(
    requestId: String,
    latitude: Double,
    longitude: Double,
    accuracyMeters: Double,
    capturedAtEpochMillis: Double,
): LocationPlatformEvent? {
    val id = requestId.toCommandId() ?: return null
    return LocationPlatformEvent.FixReceived(
        id,
        LocationFixCandidate(latitude, longitude, accuracyMeters, capturedAtEpochMillis.toLong()),
    )
}

internal fun failureEvent(requestId: String, failure: String): LocationPlatformEvent? {
    val id = requestId.toCommandId() ?: return null
    val reason = when (failure) {
        "timedOut" -> LocationFailure.TimedOut
        "invalidFix" -> LocationFailure.InvalidFix
        "unavailable" -> LocationFailure.Unavailable
        else -> return null
    }
    return LocationPlatformEvent.Failed(id, reason)
}

internal fun cancelledEvent(requestId: String): LocationPlatformEvent? =
    requestId.toCommandId()?.let(LocationPlatformEvent::Cancelled)

private fun String.toCommandId(): LocationCommandId? = toLongOrNull()?.let(::LocationCommandId)

private fun GeoPoint.isFiniteCoordinate() = latitude.isFinite() && longitude.isFinite()
