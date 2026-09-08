package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView

@Composable
actual fun PlatformMap(
    renderState: MapRenderState,
    onEvent: (MapPlatformEvent) -> Unit,
    modifier: Modifier,
) {
    val currentOnEvent = rememberUpdatedState(onEvent)
    UIKitView(
        factory = { IosMapCompositionBridge.createMapView { currentOnEvent.value(it) } },
        modifier = modifier,
        update = { view -> IosMapCompositionBridge.updateMapView(view, renderState) },
        onRelease = IosMapCompositionBridge::releaseMapView,
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
