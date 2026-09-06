package com.denis.georgiatransit.shared.presentation.location

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeLocationSessionTest {
    @Test
    fun permissionTransitionsClearIncompatibleStateAndPreserveCompatibleFix() = runTest {
        val session = locationSession()
        assertEquals(LocationPermissionState.NotDetermined, session.state.value.permission)

        listOf(
            LocationPermissionState.Denied,
            LocationPermissionState.SettingsRequired,
            LocationPermissionState.Restricted,
            LocationPermissionState.ServicesDisabled,
            LocationPermissionState.Unavailable(canRetry = true),
            LocationPermissionState.Error(canRetry = false),
        ).forEach { permission ->
            session.updatePermission(permission)
            assertEquals(permission, session.state.value.permission)
            assertNull(session.state.value.fix)
            assertFalse(session.state.value.isLocating)
            assertNull(session.state.value.activeRequestId)
            assertNull(session.state.value.failure)
        }

        session.updatePermission(PRECISE_PERMISSION)
        val requestId = session.nextCommandId()
        assertTrue(session.beginLocationRequest(requestId))
        session.accept(requestId, validCandidate())
        val preciseFix = session.state.value.fix

        session.updatePermission(PRECISE_PERMISSION)
        assertEquals(preciseFix, session.state.value.fix)

        session.updatePermission(APPROXIMATE_PERMISSION)
        assertNull(session.state.value.fix)
        assertEquals(APPROXIMATE_PERMISSION, session.state.value.permission)
    }

    @Test
    fun requestBeginsOnlyWhenGrantedAndOnlyOneRequestCanBeActive() = runTest {
        val session = locationSession()
        val first = session.nextCommandId()

        assertFalse(session.beginLocationRequest(first))
        session.updatePermission(PRECISE_PERMISSION)
        assertTrue(session.beginLocationRequest(first))
        assertFalse(session.beginLocationRequest(session.nextCommandId()))
        assertEquals(first, session.state.value.activeRequestId)
        assertTrue(session.state.value.isLocating)
    }

    @Test
    fun permissionRevocationCancelsActiveSessionRequest() = runTest {
        val session = locationSession()
        session.updatePermission(PRECISE_PERMISSION)
        val requestId = session.nextCommandId()
        assertTrue(session.beginLocationRequest(requestId))

        session.updatePermission(LocationPermissionState.SettingsRequired)

        assertEquals(LocationPermissionState.SettingsRequired, session.state.value.permission)
        assertFalse(session.state.value.isLocating)
        assertNull(session.state.value.activeRequestId)
        assertNull(session.state.value.fix)
        session.accept(requestId, validCandidate())
        assertNull(session.state.value.fix)
    }

    @Test
    fun lateAndUnsolicitedTerminalEventsCannotChangeAnotherRequest() = runTest {
        val session = locationSession()
        session.updatePermission(PRECISE_PERMISSION)
        val active = session.nextCommandId()
        val unrelated = session.nextCommandId()
        assertTrue(session.beginLocationRequest(active))

        session.accept(unrelated, validCandidate())
        session.fail(unrelated, LocationFailure.Unavailable)
        session.cancelRequest(unrelated)

        assertEquals(active, session.state.value.activeRequestId)
        assertTrue(session.state.value.isLocating)
        assertNull(session.state.value.fix)
        assertNull(session.state.value.failure)

        session.accept(active, validCandidate())
        val accepted = session.state.value
        session.fail(active, LocationFailure.TimedOut)
        session.cancelRequest(active)
        session.accept(active, validCandidate(latitude = 42.0))
        assertEquals(accepted, session.state.value)
    }

    @Test
    fun cancellationAndFailureEndOnlyTheCorrelatedRequest() = runTest {
        val session = locationSession()
        session.updatePermission(PRECISE_PERMISSION)

        val cancelled = session.nextCommandId()
        assertTrue(session.beginLocationRequest(cancelled))
        session.cancelRequest(cancelled)
        assertFalse(session.state.value.isLocating)
        assertNull(session.state.value.activeRequestId)
        assertNull(session.state.value.failure)

        val failed = session.nextCommandId()
        assertTrue(session.beginLocationRequest(failed))
        session.fail(failed, LocationFailure.TimedOut)
        assertFalse(session.state.value.isLocating)
        assertNull(session.state.value.activeRequestId)
        assertEquals(LocationFailure.TimedOut, session.state.value.failure)
    }

    @Test
    fun preciseAndApproximateAccuracyThresholdsAreEnforced() = runTest {
        val preciseAtLimit = freshSession(PRECISE_PERMISSION)
        preciseAtLimit.accept(preciseAtLimit.activeRequest(), validCandidate(accuracyMeters = 250.0))
        assertEquals(250.0, preciseAtLimit.state.value.fix?.accuracyMeters)

        val preciseOverLimit = freshSession(PRECISE_PERMISSION)
        preciseOverLimit.accept(preciseOverLimit.activeRequest(), validCandidate(accuracyMeters = 250.01))
        assertEquals(LocationFailure.InvalidFix, preciseOverLimit.state.value.failure)

        val approximateAtLimit = freshSession(APPROXIMATE_PERMISSION)
        approximateAtLimit.accept(
            approximateAtLimit.activeRequest(),
            validCandidate(accuracyMeters = 5_000.0),
        )
        assertEquals(5_000.0, approximateAtLimit.state.value.fix?.accuracyMeters)

        val approximateOverLimit = freshSession(APPROXIMATE_PERMISSION)
        approximateOverLimit.accept(
            approximateOverLimit.activeRequest(),
            validCandidate(accuracyMeters = 5_000.01),
        )
        assertEquals(LocationFailure.InvalidFix, approximateOverLimit.state.value.failure)
    }

    @Test
    fun invalidCoordinatesAccuracyAndTimestampsAreRejected() = runTest {
        val invalidCandidates = listOf(
            validCandidate(latitude = Double.NaN),
            validCandidate(latitude = 90.01),
            validCandidate(latitude = -90.01),
            validCandidate(longitude = Double.POSITIVE_INFINITY),
            validCandidate(longitude = 180.01),
            validCandidate(longitude = -180.01),
            validCandidate(accuracyMeters = Double.NaN),
            validCandidate(accuracyMeters = Double.POSITIVE_INFINITY),
            validCandidate(accuracyMeters = -0.01),
            validCandidate(capturedAtEpochMillis = NOW_MILLIS + 1L),
            validCandidate(capturedAtEpochMillis = NOW_MILLIS - MAX_FIX_AGE_MILLIS - 1L),
            validCandidate(capturedAtEpochMillis = -1L),
        )

        invalidCandidates.forEach { candidate ->
            val session = freshSession(PRECISE_PERMISSION)
            session.accept(session.activeRequest(), candidate)
            assertNull(session.state.value.fix, "Unexpectedly accepted $candidate")
            assertEquals(LocationFailure.InvalidFix, session.state.value.failure, candidate.toString())
            assertFalse(session.state.value.isLocating)
            assertNull(session.state.value.activeRequestId)
        }
    }

    @Test
    fun twoMinuteOldBoundaryIsAcceptedWithCompleteMetadata() = runTest {
        val session = freshSession(PRECISE_PERMISSION)
        val candidate = validCandidate(capturedAtEpochMillis = NOW_MILLIS - MAX_FIX_AGE_MILLIS)

        session.accept(session.activeRequest(), candidate)

        assertEquals(
            UserLocationFix(
                point = GeoPoint(candidate.latitude, candidate.longitude),
                accuracyMeters = candidate.accuracyMeters,
                precision = LocationPrecision.Precise,
                capturedAtEpochMillis = candidate.capturedAtEpochMillis,
                expiresAtEpochMillis = candidate.capturedAtEpochMillis + MAX_FIX_AGE_MILLIS,
            ),
            session.state.value.fix,
        )
        assertFalse(session.state.value.isLocating)
        assertNull(session.state.value.activeRequestId)
        assertNull(session.state.value.failure)
    }

    @Test
    fun acceptedFixExpiresAtItsCapturedTimeDeadline() = runTest {
        val session = locationSession()
        session.updatePermission(PRECISE_PERMISSION)
        val requestId = session.nextCommandId()
        assertTrue(session.beginLocationRequest(requestId))
        session.accept(requestId, validCandidate(capturedAtEpochMillis = NOW_MILLIS - 1_000L))

        advanceTimeBy(MAX_FIX_AGE_MILLIS - 1_001L)
        runCurrent()
        assertTrue(session.state.value.fix != null)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(session.state.value.fix)
    }

    @Test
    fun cancelledOldExpiryCannotRemoveReplacementFix() = runTest {
        val session = locationSession()
        session.updatePermission(PRECISE_PERMISSION)
        val first = session.nextCommandId()
        assertTrue(session.beginLocationRequest(first))
        session.accept(first, validCandidate())

        advanceTimeBy(60_000L)
        runCurrent()
        val second = session.nextCommandId()
        assertTrue(session.beginLocationRequest(second))
        session.accept(
            second,
            validCandidate(latitude = 42.0, capturedAtEpochMillis = NOW_MILLIS + 60_000L),
        )

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(42.0, session.state.value.fix?.point?.latitude)

        advanceTimeBy(60_000L)
        runCurrent()
        assertNull(session.state.value.fix)
    }

    private fun TestScope.locationSession(): RuntimeLocationSession = RuntimeLocationSession(
        scope = this,
        nowMillis = { NOW_MILLIS + testScheduler.currentTime },
    )

    private fun TestScope.freshSession(permission: LocationPermissionState): RuntimeLocationSession =
        locationSession().also { session ->
            session.updatePermission(permission)
            assertTrue(session.beginLocationRequest(session.nextCommandId()))
        }

    private fun RuntimeLocationSession.activeRequest(): LocationCommandId =
        requireNotNull(state.value.activeRequestId)

    private fun validCandidate(
        latitude: Double = 41.7151,
        longitude: Double = 44.8271,
        accuracyMeters: Double = 25.0,
        capturedAtEpochMillis: Long = NOW_MILLIS,
    ) = LocationFixCandidate(latitude, longitude, accuracyMeters, capturedAtEpochMillis)

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
        val PRECISE_PERMISSION = LocationPermissionState.Granted(LocationPrecision.Precise)
        val APPROXIMATE_PERMISSION = LocationPermissionState.Granted(LocationPrecision.Approximate)
    }
}
