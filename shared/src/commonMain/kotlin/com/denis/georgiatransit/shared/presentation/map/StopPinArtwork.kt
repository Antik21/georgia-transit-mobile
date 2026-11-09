package com.denis.georgiatransit.shared.presentation.map

private const val BadgesPerRow = 2
private const val MaxVisibleRouteBadges = 3
private const val CardPadding = 2
private const val ConnectorHeight = 5
private const val BadgeRowHeight = 16

data class StopPinBadge(val label: String, val backgroundArgb: Long, val textArgb: Long)

class StopPinArtwork internal constructor(
    val backgroundArgb: Long,
    val glyphArgb: Long,
    routeBadges: List<StopPinBadge>,
) {
    val badges: List<StopPinBadge> = if (routeBadges.size > MaxVisibleRouteBadges) {
        routeBadges.take(MaxVisibleRouteBadges) + StopPinBadge(
            label = "+${routeBadges.size - MaxVisibleRouteBadges}",
            backgroundArgb = 0xFF747983L,
            textArgb = DefaultStopTextArgb,
        )
    } else {
        routeBadges
    }
    val badgeWidth: Int = 32
    val cardWidth: Int = if (badges.isEmpty()) 0 else minOf(badges.size, BadgesPerRow) * (badgeWidth + 2) + 2 * CardPadding - 2
    val cardHeight: Int = if (badges.isEmpty()) 0 else (badges.size + BadgesPerRow - 1) / BadgesPerRow * BadgeRowHeight + 3
    val width: Int = maxOf(24, cardWidth)
    val bodyTop: Int = if (badges.isEmpty()) 0 else cardHeight + ConnectorHeight
    val height: Int = bodyTop + 28

    val imageId: String = buildString {
        append("gt-stop-pin-$backgroundArgb-$glyphArgb")
        badges.forEach { badge ->
            append("-${badge.backgroundArgb}-${badge.textArgb}-${badge.label.length}:${badge.label}")
        }
    }

    fun badgeX(index: Int): Int = CardPadding + index % BadgesPerRow * (badgeWidth + 2)

    fun badgeY(index: Int): Int = CardPadding + index / BadgesPerRow * BadgeRowHeight
}
