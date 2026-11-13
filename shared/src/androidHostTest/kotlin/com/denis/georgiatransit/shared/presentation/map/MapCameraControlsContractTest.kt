package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host guard for the bottom-end map controls; native and browser smoke checks cover the zoom itself. */
class MapCameraControlsContractTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("shared/src/mobileMain/composeResources")) }

    @Test
    fun locationSitsAboveLocalizedZoomButtonsWithFixedAutomationIds() {
        val source = root.resolve(
            "shared/src/mobileMain/kotlin/com/denis/georgiatransit/shared/presentation/map/sections/MapCameraControls.kt",
        ).readText()

        assertEquals("map.zoom-in", AutomationId.MapZoomIn)
        assertEquals("map.zoom-out", AutomationId.MapZoomOut)
        assertCodePath(
            source,
            "Column(",
            "modifier = modifier",
            "PlatformMyLocationButton(",
            "testTag(locationActionAutomationId)",
            "MapZoomDirection.In",
            "Res.string.map_zoom_in_action",
            "AutomationId.MapZoomIn",
            "MapZoomDirection.Out",
            "Res.string.map_zoom_out_action",
            "AutomationId.MapZoomOut",
        )
        listOf("values", "values-ka", "values-ru").forEach { locale ->
            val strings = root.resolve("shared/src/mobileMain/composeResources/$locale/strings.xml").readText()
            assertTrue(strings.contains("name=\"map_zoom_in_action\""), "$locale must localize zoom in")
            assertTrue(strings.contains("name=\"map_zoom_out_action\""), "$locale must localize zoom out")
        }
        assertFalse(source.contains("painterResource"), "Zoom glyphs are drawn, not product-owned assets")
    }

    private fun assertCodePath(source: String, vararg fragments: String) {
        val compact = source.replace(Regex("//[^\\r\\n]*"), "").replace(Regex("\\s+"), " ")
        var previous = -1
        fragments.forEach { fragment ->
            val index = compact.indexOf(fragment, startIndex = previous + 1)
            assertTrue(index >= 0, "Expected '$fragment' after index $previous")
            previous = index
        }
    }
}
