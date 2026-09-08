package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView

@Composable
actual fun PlatformMap(renderState: MapRenderState, modifier: Modifier) {
    UIKitView(
        factory = IosMapCompositionBridge::createMapView,
        modifier = modifier,
        update = { view -> IosMapCompositionBridge.updateMapView(view, renderState) },
        onRelease = IosMapCompositionBridge::releaseMapView,
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
