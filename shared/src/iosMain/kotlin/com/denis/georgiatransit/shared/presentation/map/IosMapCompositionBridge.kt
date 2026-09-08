package com.denis.georgiatransit.shared.presentation.map

import platform.UIKit.UIView

/**
 * iOS composition seam owned by the host. Swift receives one typed, SDK-free render state rather
 * than a positional scalar list, and owns all MapLibre/UIKit/CoreLocation types beyond this edge.
 */
internal object IosMapCompositionBridge {
    private var mapViewFactory: (() -> UIView)? = null
    private var mapViewUpdater: ((UIView, MapRenderState) -> Unit)? = null
    private var mapViewReleaser: ((UIView) -> Unit)? = null

    fun install(
        factory: () -> UIView,
        updater: (UIView, MapRenderState) -> Unit,
        releaser: (UIView) -> Unit,
    ) {
        mapViewFactory = factory
        mapViewUpdater = updater
        mapViewReleaser = releaser
    }

    fun createMapView(): UIView = checkNotNull(mapViewFactory) {
        "The iOS host must install its MapLibre UIView adapter before composing App."
    }.invoke()

    fun updateMapView(view: UIView, renderState: MapRenderState) {
        checkNotNull(mapViewUpdater) {
            "The iOS host must install its MapLibre UIView adapter before composing App."
        }.invoke(view, renderState)
    }

    fun releaseMapView(view: UIView) {
        mapViewReleaser?.invoke(view)
    }
}
