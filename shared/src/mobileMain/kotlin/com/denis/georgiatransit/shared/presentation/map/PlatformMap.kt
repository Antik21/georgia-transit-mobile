package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Native renderer boundary. A re-created native view receives the current state and applies its
 * camera command once; later layer-only updates must not reset a user-controlled camera.
 */
@Composable
expect fun PlatformMap(
    renderState: MapRenderState,
    onEvent: (MapPlatformEvent) -> Unit,
    modifier: Modifier = Modifier,
)
