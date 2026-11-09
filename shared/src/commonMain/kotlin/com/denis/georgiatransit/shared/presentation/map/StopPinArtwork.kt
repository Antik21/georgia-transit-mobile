package com.denis.georgiatransit.shared.presentation.map

import kotlin.math.ceil

const val StopPinScale = 1.3f
const val StopRouteCardCornerRadius = 4.5f
const val StopRouteBadgeCornerRadius = 3.5f

private const val CardPadding = 2
private const val BadgeRowHeight = 16

data class StopPinBadge(val label: String, val backgroundArgb: Long, val textArgb: Long)

class StopPinArtwork internal constructor(
    val backgroundArgb: Long,
    val glyphArgb: Long,
    routeBadges: List<StopPinBadge>,
) {
    val badges: List<StopPinBadge> = when (routeBadges.size) {
        0, 1 -> emptyList()
        2 -> routeBadges
        else -> listOf(
            StopPinBadge("+${routeBadges.size - 1}", MultiRouteStopBackgroundArgb, DefaultStopTextArgb),
            routeBadges.first(),
        )
    }
    val badgeWidth: Int = 22
    val badgeX: Int = CardPadding
    val width: Int = badgeWidth + 2 * CardPadding
    val cardWidth: Int = if (badges.isEmpty()) 0 else width
    val cardHeight: Int = if (badges.isEmpty()) 0 else badges.size * BadgeRowHeight + 2 * CardPadding - 1
    val bodyScale: Float = width / 24f
    private val scaledBodyHeight = 28f * bodyScale
    val height: Int = ceil(scaledBodyHeight).toInt() + cardHeight
    val bodyTop: Float = height - scaledBodyHeight
    val cardTop: Float = if (badges.isEmpty()) 0f else bodyTop - cardHeight

    val imageId: String = buildString {
        append("gt-stop-pin-$backgroundArgb-$glyphArgb")
        badges.forEach { badge ->
            append("-${badge.backgroundArgb}-${badge.textArgb}-${badge.label.length}:${badge.label}")
        }
    }

    fun badgeY(index: Int): Int = CardPadding + index * BadgeRowHeight
}
