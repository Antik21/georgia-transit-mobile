package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/** Uses Android's built-in My Location glyph rather than a product-owned asset. */
@Composable
actual fun PlatformMyLocationButton(
    contentDescription: String,
    automationId: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(android.R.drawable.ic_menu_mylocation),
            contentDescription = contentDescription,
        )
    }
}
