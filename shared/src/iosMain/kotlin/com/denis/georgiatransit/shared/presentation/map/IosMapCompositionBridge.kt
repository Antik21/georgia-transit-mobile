package com.denis.georgiatransit.shared.presentation.map

import platform.UIKit.UIView

/**
 * iOS composition seam owned by the host. Swift supplies the MapLibre UIView factory and updater;
 * feature code never looks up a service or imports an Apple/MapLibre SDK type.
 */
internal object IosMapCompositionBridge {
    private var mapViewFactory: (() -> UIView)? = null
    private var mapViewUpdater: ((UIView, Double, Double, Double) -> Unit)? = null

    fun install(
        factory: () -> UIView,
        updater: (UIView, Double, Double, Double) -> Unit,
    ) {
        mapViewFactory = factory
        mapViewUpdater = updater
    }

    fun createMapView(): UIView = checkNotNull(mapViewFactory) {
        "The iOS host must install its MapLibre UIView adapter before composing App."
    }.invoke()

    fun updateMapView(view: UIView, viewport: MapViewport) {
        checkNotNull(mapViewUpdater) {
            "The iOS host must install its MapLibre UIView adapter before composing App."
        }.invoke(view, viewport.center.latitude, viewport.center.longitude, viewport.zoom)
    }
}
