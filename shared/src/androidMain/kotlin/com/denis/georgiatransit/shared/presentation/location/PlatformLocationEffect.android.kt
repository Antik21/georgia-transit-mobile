package com.denis.georgiatransit.shared.presentation.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun PlatformLocationEffect(
    command: LocationPlatformCommand?,
    onCommandConsumed: () -> Unit,
    onEvent: (LocationPlatformEvent) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent = rememberUpdatedState(onEvent)
    val adapter = remember(context.applicationContext, activity) {
        AndroidLocationAdapter(context.applicationContext, activity)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        currentOnEvent.value(LocationPlatformEvent.PermissionChanged(adapter.permissionAfterRequest()))
    }

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
        }
    }

    LaunchedEffect(command?.id) {
        val current = command ?: return@LaunchedEffect
        when (current) {
            is LocationPlatformCommand.QueryPermission -> currentOnEvent.value(
                LocationPlatformEvent.PermissionChanged(adapter.permissionState()),
            )
            is LocationPlatformCommand.RequestPermission -> {
                if (activity == null) {
                    currentOnEvent.value(
                        LocationPlatformEvent.PermissionChanged(LocationPermissionState.Unavailable(false)),
                    )
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                }
            }
            is LocationPlatformCommand.RequestLocation -> adapter.requestLocation(
                requestId = current.id,
                onEvent = currentOnEvent.value,
            )
            is LocationPlatformCommand.OpenAppSettings -> if (!adapter.openAppSettings()) {
                currentOnEvent.value(LocationPlatformEvent.PermissionChanged(LocationPermissionState.Error(false)))
            }
            is LocationPlatformCommand.OpenLocationSettings -> if (!adapter.openLocationSettings()) {
                currentOnEvent.value(LocationPlatformEvent.PermissionChanged(LocationPermissionState.Error(false)))
            }
        }
        onCommandConsumed()
    }
}

private class AndroidLocationAdapter(
    private val context: Context,
    private val activity: ComponentActivity?,
) {
    private data class ActiveRequest(
        val id: LocationCommandId,
        val cancellationSignal: CancellationSignal,
        val timeout: Runnable,
        val onEvent: (LocationPlatformEvent) -> Unit,
    )

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var activeRequest: ActiveRequest? = null

    fun permissionState(): LocationPermissionState = try {
        permissionStateUnsafe()
    } catch (_: RuntimeException) {
        LocationPermissionState.Error(true)
    }

    private fun permissionStateUnsafe(): LocationPermissionState {
        val manager = locationManager ?: return LocationPermissionState.Unavailable(false)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)) {
            return LocationPermissionState.Unavailable(false)
        }
        if (!manager.isLocationEnabled) return LocationPermissionState.ServicesDisabled
        val coarseGranted = context.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) return LocationPermissionState.Granted(LocationPrecision.Precise)
        if (coarseGranted) return LocationPermissionState.Granted(LocationPrecision.Approximate)
        if (!preferences.getBoolean(KEY_PERMISSION_REQUESTED, false)) {
            return LocationPermissionState.NotDetermined
        }
        val canRequestAgain = activity?.let {
            it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        } == true
        return if (canRequestAgain) LocationPermissionState.Denied else LocationPermissionState.SettingsRequired
    }

    fun permissionAfterRequest(): LocationPermissionState {
        preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
        return permissionState()
    }

    fun requestLocation(
        requestId: LocationCommandId,
        onEvent: (LocationPlatformEvent) -> Unit,
    ) {
        val permission = permissionState()
        if (permission !is LocationPermissionState.Granted) {
            onEvent(LocationPlatformEvent.PermissionChanged(permission))
            return
        }
        val manager = locationManager
        if (manager == null) {
            onEvent(LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable))
            return
        }
        val enabledProviders = try {
            manager.getProviders(true)
        } catch (_: RuntimeException) {
            onEvent(LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable))
            return
        }
        val provider = when {
            permission.precision == LocationPrecision.Precise &&
                LocationManager.GPS_PROVIDER in enabledProviders -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabledProviders -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in enabledProviders -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null || activeRequest != null) {
            onEvent(LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable))
            return
        }

        val signal = CancellationSignal()
        val timeout = Runnable {
            val request = takeActiveRequest(requestId) ?: return@Runnable
            request.cancellationSignal.cancel()
            request.onEvent(LocationPlatformEvent.Failed(request.id, LocationFailure.TimedOut))
        }
        activeRequest = ActiveRequest(requestId, signal, timeout, onEvent)
        handler.postDelayed(timeout, LOCATION_TIMEOUT_MILLIS)
        try {
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                val request = takeActiveRequest(requestId) ?: return@getCurrentLocation
                val event = location?.let {
                    LocationPlatformEvent.FixReceived(request.id, it.toCandidate())
                } ?: LocationPlatformEvent.Failed(request.id, LocationFailure.Unavailable)
                request.onEvent(event)
            }
        } catch (_: SecurityException) {
            takeActiveRequest(requestId)?.let { request ->
                val currentPermission = permissionState()
                request.onEvent(
                    if (currentPermission is LocationPermissionState.Granted) {
                        LocationPlatformEvent.Failed(request.id, LocationFailure.Unavailable)
                    } else {
                        LocationPlatformEvent.PermissionChanged(currentPermission)
                    },
                )
            }
        } catch (_: RuntimeException) {
            takeActiveRequest(requestId)?.onEvent?.invoke(
                LocationPlatformEvent.Failed(requestId, LocationFailure.Unavailable),
            )
        }
    }

    fun cancel() {
        val request = activeRequest ?: return
        activeRequest = null
        handler.removeCallbacks(request.timeout)
        request.cancellationSignal.cancel()
        request.onEvent(LocationPlatformEvent.Cancelled(request.id))
    }

    fun openAppSettings(): Boolean = launchSettings(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )

    fun openLocationSettings(): Boolean = launchSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun launchSettings(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun takeActiveRequest(requestId: LocationCommandId): ActiveRequest? {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return null
        activeRequest = null
        handler.removeCallbacks(request.timeout)
        return request
    }
}

private fun Location.toCandidate(): LocationFixCandidate = LocationFixCandidate(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.toDouble(),
    capturedAtEpochMillis = time,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val PREFERENCES_NAME = "location_permission"
private const val KEY_PERMISSION_REQUESTED = "requested"
private const val LOCATION_TIMEOUT_MILLIS = 10_000L
