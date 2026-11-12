@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import com.denis.georgiatransit.shared.presentation.map.MapStopCluster
import com.denis.georgiatransit.shared.presentation.map.MapStopMarker
import com.denis.georgiatransit.shared.presentation.map.StopPinArtwork
import com.denis.georgiatransit.shared.presentation.map.StopRouteBadgeCornerRadius
import com.denis.georgiatransit.shared.presentation.map.StopRouteCardCornerRadius

@JsExport
class StopPinRouteBadge(val label: String, val color: String, val textColor: String, val y: Int)

/** Pin geometry in CSS pixels, computed by common [StopPinArtwork] so every client draws the same pin. */
@JsExport
class StopPin(
    val imageId: String,
    val color: String,
    val glyphColor: String,
    val width: Int,
    val height: Int,
    val bodyTop: Double,
    val bodyScale: Double,
    val cardTop: Double,
    val cardWidth: Int,
    val cardHeight: Int,
    val cardCornerRadius: Double,
    val badgeX: Int,
    val badgeWidth: Int,
    val badgeCornerRadius: Double,
    val badges: Array<StopPinRouteBadge>,
)

@JsExport
class MapStopPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val pin: StopPin,
    val scale: Double,
    val selected: Boolean,
    val highlighted: Boolean,
    val outsideSelectedRoutes: Boolean,
    val representsGroup: Boolean,
)

@JsExport
class MapStopClusterItem(val id: String, val latitude: Double, val longitude: Double, val count: Int, val label: String)

/** [revision] is the stop source revision every stop tap must echo back. */
@JsExport
class MapStopLayer(
    val revision: String,
    val stops: Array<MapStopPin>,
    val clusters: Array<MapStopClusterItem>,
)

internal fun stopLayerOf(revision: Long, stops: List<MapStopMarker>, clusters: List<MapStopCluster>): MapStopLayer {
    val pins = mutableMapOf<String, StopPin>()
    return MapStopLayer(
        revision = revision.toString(),
        stops = stops.map { marker ->
            val artwork = marker.routeHighlight.pinArtwork
            MapStopPin(
                id = marker.id.value,
                latitude = marker.position.latitude,
                longitude = marker.position.longitude,
                label = marker.accessibilityLabel,
                pin = pins.getOrPut(artwork.imageId) { artwork.toJs() },
                scale = marker.pinScale.toDouble(),
                selected = marker.isSelected,
                highlighted = marker.routeHighlight.isHighlighted,
                outsideSelectedRoutes = marker.isOutsideSelectedRoutes,
                representsGroup = marker.representsGroup,
            )
        }.toTypedArray(),
        clusters = clusters.map {
            MapStopClusterItem(it.stableId, it.position.latitude, it.position.longitude, it.stopCount, it.accessibilityLabel)
        }.toTypedArray(),
    )
}

private fun StopPinArtwork.toJs() = StopPin(
    imageId = imageId,
    color = backgroundArgb.toCssColor(),
    glyphColor = glyphArgb.toCssColor(),
    width = width,
    height = height,
    bodyTop = bodyTop.toDouble(),
    bodyScale = bodyScale.toDouble(),
    cardTop = cardTop.toDouble(),
    cardWidth = cardWidth,
    cardHeight = cardHeight,
    cardCornerRadius = StopRouteCardCornerRadius.toDouble(),
    badgeX = badgeX,
    badgeWidth = badgeWidth,
    badgeCornerRadius = StopRouteBadgeCornerRadius.toDouble(),
    badges = badges.mapIndexed { index, badge ->
        StopPinRouteBadge(badge.label, badge.backgroundArgb.toCssColor(), badge.textArgb.toCssColor(), badgeY(index))
    }.toTypedArray(),
)
