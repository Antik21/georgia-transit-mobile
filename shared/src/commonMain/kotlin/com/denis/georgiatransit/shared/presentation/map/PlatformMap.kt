package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.denis.georgiatransit.shared.domain.model.GeoPoint

/** SDK-free camera input shared by the MapLibre platform adapters. */
@Immutable
data class MapViewport(
    val center: GeoPoint,
    val zoom: Double = 12.0,
)

/**
 * Native renderer boundary. It deliberately receives only product data, never map SDK objects,
 * provider DTOs, keys, or URLs.
 */
@Composable
expect fun PlatformMap(
    viewport: MapViewport,
    modifier: Modifier = Modifier,
)
