package com.denis.georgiatransit.shared.app

import androidx.compose.ui.window.ComposeUIViewController
import com.denis.georgiatransit.shared.presentation.map.IosMapCompositionBridge
import com.denis.georgiatransit.shared.presentation.map.MapPlatformEvent
import com.denis.georgiatransit.shared.presentation.map.MapRenderState
import com.denis.georgiatransit.shared.presentation.map.MapViewport
import platform.UIKit.UIView
import platform.UIKit.UIViewController

fun MainViewController(
    mapViewFactory: ((MapViewport) -> Unit, (MapPlatformEvent) -> Unit) -> UIView,
    updateMapView: (UIView, MapRenderState) -> Unit,
    releaseMapView: (UIView) -> Unit,
): UIViewController {
    IosMapCompositionBridge.install(factory = mapViewFactory, updater = updateMapView, releaser = releaseMapView)
    return ComposeUIViewController { App() }
}
