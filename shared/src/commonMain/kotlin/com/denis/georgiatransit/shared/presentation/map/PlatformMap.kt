package com.denis.georgiatransit.shared.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.presentation.location.UserLocationFix

/** SDK-free camera input shared by the MapLibre platform adapters. */
@Immutable
data class MapViewport(
    val center: GeoPoint,
    val contentCenter: GeoPoint = center,
    val zoom: Double = 12.0,
)

/**
 * Native renderer boundary. It deliberately receives only product data, never map SDK objects,
 * provider DTOs, keys, or URLs.
 */
@Composable
expect fun PlatformMap(
    viewport: MapViewport,
    userLocation: UserLocationFix?,
    modifier: Modifier = Modifier,
)
