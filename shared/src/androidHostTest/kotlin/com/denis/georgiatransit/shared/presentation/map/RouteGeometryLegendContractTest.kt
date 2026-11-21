package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host guard for the compact design requirement; native smoke checks cover rendered actions. */
class RouteGeometryLegendContractTest {
    @Test
    fun compactRouteChipsKeepIndependentAccessibleTypedActions() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("shared/src/mobileMain/composeResources")) }
        val source = root.resolve(
            "shared/src/mobileMain/kotlin/com/denis/georgiatransit/shared/presentation/map/sections/RouteGeometryLegend.kt",
        ).readText()
        val automation = root.resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/ui/automation/AutomationId.kt",
        ).readText()

        listOf(
            AutomationId.MapRouteGeometryLegend,
            AutomationId.MapRouteGeometryChip,
            AutomationId.MapRouteGeometryRetry,
            AutomationId.MapRouteGeometryRemove,
        ).forEach { id ->
            assertTrue(id.startsWith("map.route-geometry."))
            assertFalse(id.contains('$'), "Automation IDs must not include runtime values")
            assertTrue(automation.contains("\"$id\""))
        }
        assertTrue(source.contains("private val RouteGeometryChipHeight = InputChipDefaults.Height"))
        assertFalse(source.contains(".widthIn("), "Route chips must wrap their content without a minimum width")
        assertFalse(source.contains("RouteGeometryActionSize"), "Do not restore the oversized action slots")
        assertTrue(source.contains("PRODUCT REQUIREMENT"))
        assertCodePath(
            source,
            "LazyRow(",
            "items(routes, key = { it.routeId.value })",
            "RouteGeometryChip(",
            "if (route.canRetry)",
            "RouteGeometryRetry(",
        )
        assertCodePath(
            source,
            "private fun RouteGeometryChip(",
            ".heightIn(min = RouteGeometryChipHeight)",
            "shape = InputChipDefaults.shape",
            "BorderStroke(2.dp, foreground)",
            "MapRouteGeometryChip",
            "stateDescription = focused",
            "CustomAccessibilityAction(retryDescription)",
            ".clickable(enabled = route.canFocus, onClickLabel = focusAction, role = Role.Button, onClick = onFocus)",
            ".size(InputChipDefaults.IconSize)",
            "fontWeight = FontWeight.Medium",
            "VerticalDivider(",
            ".size(RouteGeometryRemoveSize)",
            "MapRouteGeometryRemove",
            ".clickable(role = Role.Button, onClick = onRemove)",
            "CloseIcon",
        )
        assertCodePath(
            source,
            "private fun RouteGeometryRetry(",
            "MapRouteGeometryRetry",
            ".clickable(role = Role.Button, onClick = onClick)",
            "contentColor = MaterialTheme.colorScheme.onSurface",
        )
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
