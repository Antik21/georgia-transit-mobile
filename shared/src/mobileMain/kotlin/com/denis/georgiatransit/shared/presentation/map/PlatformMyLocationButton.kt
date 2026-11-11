package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-owned location control. Its visual symbol stays native, while common presentation
 * retains the permission and camera behavior in [MapViewModel].
 */
@Composable
expect fun PlatformMyLocationButton(
    contentDescription: String,
    automationId: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
)
