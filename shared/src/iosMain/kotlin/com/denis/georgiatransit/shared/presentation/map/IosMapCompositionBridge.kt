package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.location.LocationPrecision
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix
import platform.UIKit.UIView

/**
 * iOS composition seam owned by the host. Swift supplies the MapLibre UIView factory and updater;
 * feature code never looks up a service or imports an Apple/MapLibre SDK type.
 */
internal object IosMapCompositionBridge {
    private var mapViewFactory: (() -> UIView)? = null
    private var mapViewUpdater: ((UIView, Double, Double, Double, Double, Double, Double?, Double?, Double?, Int?) -> Unit)? = null

    fun install(
        factory: () -> UIView,
        updater: (UIView, Double, Double, Double, Double, Double, Double?, Double?, Double?, Int?) -> Unit,
    ) {
        mapViewFactory = factory
        mapViewUpdater = updater
    }

    fun createMapView(): UIView = checkNotNull(mapViewFactory) {
        "The iOS host must install its MapLibre UIView adapter before composing App."
    }.invoke()

    fun updateMapView(view: UIView, viewport: MapViewport, userLocation: UserLocationFix?) {
        checkNotNull(mapViewUpdater) {
            "The iOS host must install its MapLibre UIView adapter before composing App."
        }.invoke(
            view,
            viewport.center.latitude,
            viewport.center.longitude,
            viewport.zoom,
            viewport.contentCenter.latitude,
            viewport.contentCenter.longitude,
            userLocation?.point?.latitude,
            userLocation?.point?.longitude,
            userLocation?.accuracyMeters,
            userLocation?.precision?.let {
                when (it) {
                    LocationPrecision.Approximate -> 0
                    LocationPrecision.Precise -> 1
                }
            },
        )
    }
}
