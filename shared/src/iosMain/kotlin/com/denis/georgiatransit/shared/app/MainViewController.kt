package com.denis.georgiatransit.shared.app

import androidx.compose.ui.window.ComposeUIViewController
import com.denis.georgiatransit.shared.presentation.map.IosMapCompositionBridge
import com.denis.georgiatransit.shared.presentation.map.MapRenderState
import platform.UIKit.UIViewController
import platform.UIKit.UIView

fun MainViewController(
    mapViewFactory: () -> UIView,
    updateMapView: (UIView, MapRenderState) -> Unit,
    releaseMapView: (UIView) -> Unit,
): UIViewController {
    IosMapCompositionBridge.install(factory = mapViewFactory, updater = updateMapView, releaser = releaseMapView)
    return ComposeUIViewController { App() }
}
