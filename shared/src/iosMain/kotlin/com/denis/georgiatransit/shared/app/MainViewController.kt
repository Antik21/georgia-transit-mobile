package com.denis.georgiatransit.shared.app

import androidx.compose.ui.window.ComposeUIViewController
import com.denis.georgiatransit.shared.presentation.map.IosMapCompositionBridge
import platform.UIKit.UIViewController
import platform.UIKit.UIView

fun MainViewController(
    mapViewFactory: () -> UIView,
    updateMapView: (UIView, Double, Double, Double, Double, Double, Double?, Double?, Double?, Int?) -> Unit,
): UIViewController {
    IosMapCompositionBridge.install(factory = mapViewFactory, updater = updateMapView)
    return ComposeUIViewController { App() }
}
