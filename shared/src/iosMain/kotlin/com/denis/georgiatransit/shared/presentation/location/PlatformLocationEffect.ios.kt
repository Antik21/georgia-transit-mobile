package com.denis.georgiatransit.shared.presentation.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

@Composable
actual fun PlatformLocationEffect(
    command: LocationPlatformCommand?,
    onCommandConsumed: () -> Unit,
    onEvent: (LocationPlatformEvent) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent = rememberUpdatedState(onEvent)
    val coroutineScope = rememberCoroutineScope()
    val adapter = remember { IosLocationAdapter { currentOnEvent.value(it) } }

    DisposableEffect(lifecycleOwner, adapter) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentOnEvent.value(
                    LocationPlatformEvent.PermissionChanged(adapter.permissionState()),
                )
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> adapter.cancel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            currentOnEvent.value(LocationPlatformEvent.PermissionChanged(adapter.permissionState()))
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            adapter.cancel()
            adapter.dispose()
        }
    }

    LaunchedEffect(command?.id) {
        val current = command ?: return@LaunchedEffect
        when (current) {
            is LocationPlatformCommand.QueryPermission -> currentOnEvent.value(
                LocationPlatformEvent.PermissionChanged(adapter.permissionState()),
            )
            is LocationPlatformCommand.RequestPermission -> adapter.requestPermission()
            is LocationPlatformCommand.RequestLocation -> {
                if (adapter.requestLocation(current.id)) {
                    coroutineScope.launch {
                        delay(LOCATION_TIMEOUT_MILLIS)
                        adapter.timeout(current.id)
                    }
                }
            }
            is LocationPlatformCommand.OpenAppSettings,
            is LocationPlatformCommand.OpenLocationSettings,
            -> if (!adapter.openSettings()) {
                currentOnEvent.value(LocationPlatformEvent.PermissionChanged(LocationPermissionState.Error(false)))
            }
        }
        onCommandConsumed()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosLocationAdapter(
    private val onEvent: (LocationPlatformEvent) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager()
    private var activeRequestId: LocationCommandId? = null

    init {
        manager.delegate = this
    }

    fun permissionState(): LocationPermissionState {
        if (!CLLocationManager.locationServicesEnabled()) return LocationPermissionState.ServicesDisabled
        return manager.authorizationStatus.toPermissionState()
    }

    fun requestPermission() {
        val permission = permissionState()
        if (permission == LocationPermissionState.NotDetermined) {
            manager.requestWhenInUseAuthorization()
        } else {
            onEvent(LocationPlatformEvent.PermissionChanged(permission))
        }
    }

    fun requestLocation(id: LocationCommandId): Boolean {
        val permission = permissionState()
        if (permission !is LocationPermissionState.Granted) {
            onEvent(LocationPlatformEvent.PermissionChanged(permission))
            return false
        }
        if (activeRequestId != null) {
            onEvent(LocationPlatformEvent.Failed(id, LocationFailure.Unavailable))
            return false
        }
        activeRequestId = id
        manager.desiredAccuracy = if (permission.precision == LocationPrecision.Precise) {
            kCLLocationAccuracyBest
        } else {
            kCLLocationAccuracyKilometer
        }
        manager.requestLocation()
        return true
    }

    fun timeout(id: LocationCommandId) {
        if (activeRequestId != id) return
        activeRequestId = null
        manager.stopUpdatingLocation()
        onEvent(LocationPlatformEvent.Failed(id, LocationFailure.TimedOut))
    }

    fun cancel() {
        val requestId = activeRequestId ?: return
        activeRequestId = null
        manager.stopUpdatingLocation()
        onEvent(LocationPlatformEvent.Cancelled(requestId))
    }

    fun openSettings(): Boolean = NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
        UIApplication.sharedApplication.openURL(it)
    } ?: false

    fun dispose() {
        cancel()
        manager.delegate = null
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onEvent(LocationPlatformEvent.PermissionChanged(permissionState()))
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val requestId = activeRequestId ?: return
        activeRequestId = null
        val location = didUpdateLocations.lastOrNull() as? CLLocation
        if (location == null) {
            onEvent(LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable))
            return
        }
        val capturedAtEpochMillis = (location.timestamp.timeIntervalSince1970 * 1_000.0).toLong()
        val coordinate = location.coordinate.useContents { latitude to longitude }
        onEvent(
            LocationPlatformEvent.FixReceived(
                requestId = requestId,
                candidate = LocationFixCandidate(
                    latitude = coordinate.first,
                    longitude = coordinate.second,
                    accuracyMeters = location.horizontalAccuracy,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                ),
            ),
        )
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        val requestId = activeRequestId ?: return
        activeRequestId = null
        onEvent(LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable))
    }

    private fun CLAuthorizationStatus.toPermissionState(): LocationPermissionState = when (this) {
        kCLAuthorizationStatusNotDetermined -> LocationPermissionState.NotDetermined
        kCLAuthorizationStatusRestricted -> LocationPermissionState.Restricted
        kCLAuthorizationStatusDenied -> LocationPermissionState.SettingsRequired
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusAuthorizedAlways,
        -> LocationPermissionState.Granted(
            if (manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy) {
                LocationPrecision.Precise
            } else {
                LocationPrecision.Approximate
            },
        )
        else -> LocationPermissionState.Error(true)
    }
}

private const val LOCATION_TIMEOUT_MILLIS = 10_000L
