package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.location.LocationCommandId
import com.denis.georgiatransit.shared.presentation.location.LocationFailure
import com.denis.georgiatransit.shared.presentation.location.LocationFixCandidate
import com.denis.georgiatransit.shared.presentation.location.LocationPermissionState
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformCommand
import com.denis.georgiatransit.shared.presentation.location.LocationPlatformEvent
import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLocationActionsTest {
    @Test
    fun acceptedPermissionPreservesTheCameraTapThroughTheFirstLocationAttempt() = runTest {
        val actions = MapLocationActions(RuntimeLocationSession(scope = this))
        assertIs<LocationPlatformCommand.RequestPermission>(actions.forAction(Action.MyLocationClicked, visible = true).command)
        val command = assertIs<LocationPlatformCommand.RequestLocation>(
            actions.receive(LocationPlatformEvent.PermissionChanged(precisePermission)).command,
        )

        assertTrue(actions.receive(failure(command.id)).showCameraError)
        assertFalse(actions.receive(failure(command.id)).showCameraError)
    }

    @Test
    fun backgroundRefreshBecomesEligibleForFeedbackOnlyAfterTheCameraTap() = runTest {
        val session = RuntimeLocationSession(scope = this).also { it.updatePermission(precisePermission) }
        val actions = MapLocationActions(session)
        val refresh = assertIs<LocationPlatformCommand.RequestLocation>(actions.forAction(Action.RefreshLocation, visible = true).command)
        assertFalse(actions.receive(failure(refresh.id)).showCameraError)

        val adoptedRefresh = assertIs<LocationPlatformCommand.RequestLocation>(actions.forAction(Action.RefreshLocation, visible = true).command)
        assertNull(actions.forAction(Action.MyLocationClicked, visible = true).command)
        assertTrue(actions.receive(failure(adoptedRefresh.id)).showCameraError)
    }

    @Test
    fun cancellationSilencesLateRepliesAndAnInvalidNewFixShowsFeedback() = runTest {
        val session = RuntimeLocationSession(scope = this).also { it.updatePermission(precisePermission) }
        val actions = MapLocationActions(session)
        val cancelled = assertIs<LocationPlatformCommand.RequestLocation>(actions.forAction(Action.MyLocationClicked, visible = true).command)
        assertFalse(actions.receive(LocationPlatformEvent.Cancelled(cancelled.id)).showCameraError)
        val retry = assertIs<LocationPlatformCommand.RequestLocation>(actions.forAction(Action.MyLocationClicked, visible = true).command)
        assertFalse(actions.receive(failure(cancelled.id)).showCameraError)

        val invalidFix = LocationFixCandidate(latitude = 200.0, longitude = 0.0, accuracyMeters = 10.0, capturedAtEpochMillis = 0)
        assertTrue(actions.receive(LocationPlatformEvent.FixReceived(retry.id, invalidFix)).showCameraError)
    }

    private fun MapLocationActions.receive(event: LocationPlatformEvent) =
        forAction(Action.LocationEventReceived(event), visible = true)

    private fun failure(id: LocationCommandId) = LocationPlatformEvent.Failed(id, LocationFailure.TimedOut)

    private val precisePermission = LocationPermissionState.Granted(LocationPrecision.Precise)
}
