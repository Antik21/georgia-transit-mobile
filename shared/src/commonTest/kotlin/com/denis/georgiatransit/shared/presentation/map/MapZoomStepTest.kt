package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.orbitmvi.orbit.test.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class MapZoomStepTest {
    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun zoomClicksPublishStepsWithoutMovingTheCameraCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession().also { it.selectCity(repository.cities().first()) }
        val locationSession = RuntimeLocationSession(scope = this, nowMillis = { testScheduler.currentTime })
        val viewModel = MapViewModel(repository, session, locationSession)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            val initial = checkNotNull(viewModel.container.stateFlow.value.renderState)
            assertNull(initial.zoomStep)

            viewModel.dispatchAction(Action.ZoomClicked(MapZoomDirection.In))
            viewModel.dispatchAction(Action.ZoomClicked(MapZoomDirection.Out))
            viewModel.dispatchAction(Action.ZoomClicked(MapZoomDirection.Out))
            this@runTest.runCurrent()
            val zoomed = checkNotNull(viewModel.container.stateFlow.value.renderState)
            assertEquals(MapZoomStep(totalDelta = -1.0), zoomed.zoomStep)
            assertEquals(initial.camera, zoomed.camera)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun trackerIgnoresTheAttachTimeSumAndAppliesOnlyChanges() {
        val tracker = MapZoomStepTracker(minZoom = 0.0, maxZoom = 22.0, timeSource = TestTimeSource())

        assertNull(tracker.targetZoom(MapZoomStep(totalDelta = 4.0), currentZoom = 14.0))
        assertNull(tracker.targetZoom(MapZoomStep(totalDelta = 4.0), currentZoom = 14.0))
        assertEquals(15.0, tracker.targetZoom(MapZoomStep(totalDelta = 5.0), currentZoom = 14.0))
    }

    @Test
    fun tapsDuringTheAnimationBuildOnItsTargetAndLaterTapsOnTheLiveZoom() {
        val time = TestTimeSource()
        val tracker = MapZoomStepTracker(minZoom = 0.0, maxZoom = 16.0, timeSource = time)
        tracker.targetZoom(null, currentZoom = 13.0)

        assertEquals(15.0, tracker.targetZoom(MapZoomStep(totalDelta = 2.0), currentZoom = 13.0))
        time += 100.milliseconds
        assertEquals(16.0, tracker.targetZoom(MapZoomStep(totalDelta = 3.0), currentZoom = 13.8))
        time += 100.milliseconds
        assertEquals(15.0, tracker.targetZoom(MapZoomStep(totalDelta = 2.0), currentZoom = 14.9))

        time += 1.seconds
        assertEquals(12.5, tracker.targetZoom(MapZoomStep(totalDelta = -1.0), currentZoom = 15.5))
    }

    @Test
    fun anotherCameraMoveDropsThePendingTargetInsideTheAnimationWindow() {
        val tracker = MapZoomStepTracker(minZoom = 0.0, maxZoom = 22.0, timeSource = TestTimeSource())
        tracker.targetZoom(null, currentZoom = 13.0)

        assertEquals(14.0, tracker.targetZoom(MapZoomStep(totalDelta = 1.0), currentZoom = 13.0))
        tracker.cameraMovedElsewhere()
        assertEquals(14.0, tracker.targetZoom(MapZoomStep(totalDelta = 0.0), currentZoom = 15.0))
    }
}
