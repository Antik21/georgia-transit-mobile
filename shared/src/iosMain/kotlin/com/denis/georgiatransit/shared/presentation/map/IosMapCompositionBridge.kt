package com.denis.georgiatransit.shared.presentation.map

import platform.UIKit.UIView
import com.denis.georgiatransit.shared.domain.model.StopId

/**
 * iOS composition seam owned by the host. Swift receives one typed, SDK-free render state rather
 * than a positional scalar list, and owns all MapLibre/UIKit/CoreLocation types beyond this edge.
 */
internal object IosMapCompositionBridge {
    private var mapViewFactory: (((MapViewport) -> Unit, (StopId) -> Unit) -> UIView)? = null
    private var mapViewUpdater: ((UIView, MapRenderState) -> Unit)? = null
    private var mapViewReleaser: ((UIView) -> Unit)? = null

    fun install(
        factory: ((MapViewport) -> Unit, (StopId) -> Unit) -> UIView,
        updater: (UIView, MapRenderState) -> Unit,
        releaser: (UIView) -> Unit,
    ) {
        mapViewFactory = factory
        mapViewUpdater = updater
        mapViewReleaser = releaser
    }

    fun createMapView(onEvent: (MapPlatformEvent) -> Unit): UIView = checkNotNull(mapViewFactory) {
        "The iOS host must install its MapLibre UIView adapter before composing App."
    }.invoke(
        { onEvent(MapPlatformEvent.ViewportSettled(it)) },
        { onEvent(MapPlatformEvent.StopTapped(it)) },
    )

    fun updateMapView(view: UIView, renderState: MapRenderState) {
        checkNotNull(mapViewUpdater) {
            "The iOS host must install its MapLibre UIView adapter before composing App."
        }.invoke(view, renderState)
    }

    fun releaseMapView(view: UIView) {
        mapViewReleaser?.invoke(view)
    }
}
