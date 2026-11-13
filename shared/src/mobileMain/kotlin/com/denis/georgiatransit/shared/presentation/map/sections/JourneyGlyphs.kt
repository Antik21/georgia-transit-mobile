package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The web planner's walker glyph, so both clients draw the same itinerary row. */
internal val WalkIcon: ImageVector by lazy {
    glyph(
        "Walk",
        "M13.5 5.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3A7.3 7.3 0 0 0 19 13v-2" +
            "c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6z",
    )
}

/** Material Symbols "swap_vert" geometry, used under the Apache-2.0 license. */
internal val SwapIcon: ImageVector by lazy {
    glyph("SwapVert", "M16 17.01V10h-2v7.01h-3L15 21l4-3.99h-3zM9 3 5 6.99h3V14h2V6.99h3L9 3z")
}

/** Material Symbols "arrow_back", "check" and "location_on" geometry, used under the Apache-2.0 license. */
internal val BackArrowIcon: ImageVector by lazy {
    glyph("ArrowBack", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z", autoMirror = true)
}

internal val CheckIcon: ImageVector by lazy { glyph("Check", "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z") }

internal val PlaceIcon: ImageVector by lazy {
    glyph(
        "Place",
        "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5z",
    )
}

private fun glyph(name: String, path: String, autoMirror: Boolean = false): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).apply {
    addPath(fill = SolidColor(Color.Black), pathData = PathParser().parsePathString(path).toNodes())
}.build()
