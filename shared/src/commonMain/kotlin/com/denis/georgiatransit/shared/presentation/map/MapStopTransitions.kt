package com.denis.georgiatransit.shared.presentation.map

/** Atomically invalidates stop-derived data when the city or BFF stop contract changes. */
internal class MapStopTransitions(
    private val nearby: MapNearbyStops,
    private val supplemental: MapSupplementalStops,
    private val selection: MapStopSelection,
    private val camera: MapCameraController,
    private val arrivals: StopArrivalsFeature,
) {
    fun resetForCityChange() {
        val viewport = nearby.viewport
        val previousCamera = camera.command
        nearby.reset()
        if (supplemental.resetUser()) selection.nextSourceRevision()
        selection.clear()
        resetCamera(viewport, previousCamera)
        selection.nextSourceRevision()
        arrivals.reset()
    }

    fun clearForRecovery() {
        val viewport = nearby.viewport
        val previousCamera = camera.command
        nearby.clearStops()
        selection.clear()
        resetCamera(viewport, previousCamera)
        selection.nextSourceRevision()
        arrivals.reset()
    }

    private fun resetCamera(viewport: MapViewport?, previousCamera: MapCameraCommand?) {
        camera.insets = MapViewportInsets.None
        if (previousCamera != null && previousCamera.viewportInsets != MapViewportInsets.None) {
            camera.next(
                center = viewport?.center ?: previousCamera.center,
                zoom = viewport?.zoom ?: previousCamera.zoom,
                viewportInsets = MapViewportInsets.None,
            )
        }
    }
}
