package com.denis.georgiatransit.shared.presentation.location

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

class RuntimeLocationSession(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : LocationSession {
    private val mutableState = MutableStateFlow(LocationState())
    override val state: StateFlow<LocationState> = mutableState.asStateFlow()
    private var expiryJob: Job? = null
    private var expiryGeneration = 0L
    private var lastCommandId = 0L

    override fun nextCommandId() = LocationCommandId(++lastCommandId)

    override fun updatePermission(permission: LocationPermissionState) {
        val current = mutableState.value
        val compatibleFix = current.fix?.takeIf {
            permission is LocationPermissionState.Granted && permission.precision == it.precision
        }
        val preservedFailure = current.failure.takeIf {
            current.permission is LocationPermissionState.Granted &&
                permission is LocationPermissionState.Granted &&
                current.permission.precision == permission.precision
        }
        mutableState.value = current.copy(
            permission = permission,
            fix = compatibleFix,
            isLocating = current.isLocating && permission is LocationPermissionState.Granted,
            failure = preservedFailure,
            activeRequestId = current.activeRequestId.takeIf { permission is LocationPermissionState.Granted },
        )
        if (compatibleFix == null) cancelExpiry()
    }

    override fun beginLocationRequest(requestId: LocationCommandId): Boolean {
        val current = mutableState.value
        if (current.permission !is LocationPermissionState.Granted || current.activeRequestId != null) return false
        cancelExpiry()
        mutableState.value = current.copy(
            fix = null,
            isLocating = true,
            failure = null,
            activeRequestId = requestId,
        )
        return true
    }

    override fun accept(requestId: LocationCommandId, candidate: LocationFixCandidate) {
        val current = mutableState.value
        if (current.activeRequestId != requestId) return
        val permission = current.permission as? LocationPermissionState.Granted ?: return
        val now = nowMillis()
        val maximumAccuracy = when (permission.precision) {
            LocationPrecision.Precise -> MAX_PRECISE_ACCURACY_METERS
            LocationPrecision.Approximate -> MAX_APPROXIMATE_ACCURACY_METERS
        }
        val valid = candidate.latitude.isFinite() && candidate.latitude in -90.0..90.0 &&
            candidate.longitude.isFinite() && candidate.longitude in -180.0..180.0 &&
            candidate.accuracyMeters.isFinite() && candidate.accuracyMeters >= 0.0 &&
            candidate.accuracyMeters <= maximumAccuracy &&
            candidate.capturedAtEpochMillis in 0..now &&
            now - candidate.capturedAtEpochMillis <= MAX_FIX_AGE_MILLIS
        if (!valid) {
            fail(requestId, LocationFailure.InvalidFix)
            return
        }
        val expiresAt = candidate.capturedAtEpochMillis + MAX_FIX_AGE_MILLIS
        val fix = UserLocationFix(
            point = GeoPoint(candidate.latitude, candidate.longitude),
            accuracyMeters = candidate.accuracyMeters,
            precision = permission.precision,
            capturedAtEpochMillis = candidate.capturedAtEpochMillis,
            expiresAtEpochMillis = expiresAt,
        )
        mutableState.value = current.copy(
            fix = fix,
            isLocating = false,
            failure = null,
            activeRequestId = null,
        )
        scheduleExpiry(fix)
    }

    override fun fail(requestId: LocationCommandId, failure: LocationFailure) {
        val current = mutableState.value
        if (current.activeRequestId != requestId) return
        mutableState.value = current.copy(isLocating = false, failure = failure, activeRequestId = null)
    }

    override fun cancelRequest(requestId: LocationCommandId) {
        val current = mutableState.value
        if (current.activeRequestId != requestId) return
        mutableState.value = current.copy(isLocating = false, activeRequestId = null)
    }

    private fun scheduleExpiry(fix: UserLocationFix) {
        cancelExpiry()
        val generation = expiryGeneration
        expiryJob = scope.launch {
            while (true) {
                val remaining = fix.expiresAtEpochMillis - nowMillis()
                if (remaining <= 0L) break
                delay(remaining)
            }
            val current = mutableState.value
            if (expiryGeneration == generation && current.fix == fix) {
                mutableState.value = current.copy(fix = null)
                expiryJob = null
            }
        }
    }

    private fun cancelExpiry() {
        expiryGeneration++
        expiryJob?.cancel()
        expiryJob = null
    }
}
