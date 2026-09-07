package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix

@Composable
actual fun PlatformMap(viewport: MapViewport, userLocation: UserLocationFix?, modifier: Modifier) {
    UIKitView(
        factory = IosMapCompositionBridge::createMapView,
        modifier = modifier,
        update = { view -> IosMapCompositionBridge.updateMapView(view, viewport, userLocation) },
        onRelease = { view -> view.removeFromSuperview() },
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
