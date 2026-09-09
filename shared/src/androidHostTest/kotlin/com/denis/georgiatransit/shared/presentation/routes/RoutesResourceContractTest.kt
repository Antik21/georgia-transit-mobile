package com.denis.georgiatransit.shared.presentation.routes

import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

class RoutesResourceContractTest {
    @Test
    fun routeCatalogStringsAndAccessibilityContractsHaveEnglishGeorgianRussianParity() {
        val resources = projectRoot().resolve("shared/src/commonMain/composeResources")
        val english = stringValues(resources.resolve("values/strings.xml"))
        val georgian = stringValues(resources.resolve("values-ka/strings.xml"))
        val russian = stringValues(resources.resolve("values-ru/strings.xml"))

        assertEquals(english.keys, georgian.keys, "Georgian route resources must stay in parity with English.")
        assertEquals(english.keys, russian.keys, "Russian route resources must stay in parity with English.")
        assertTrue(RouteResourceKeys.all { it in english.keys })
        english.keys.forEach { key ->
            val expected = placeholderSignature(english.getValue(key))
            assertEquals(
                expected,
                placeholderSignature(georgian.getValue(key)),
                "Georgian placeholder indexes and conversion types must match for '$key'.",
            )
            assertEquals(
                expected,
                placeholderSignature(russian.getValue(key)),
                "Russian placeholder indexes and conversion types must match for '$key'.",
            )
        }
    }

    @Test
    fun routeScreenUsesStableSelectorsKeyedRowsAndOneCheckableSemanticTargetPerRow() {
        val screen = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/routes/RoutesScreen.kt",
        ).readText()
        val automation = listOf(
            AutomationId.RoutesScreen,
            AutomationId.RoutesSearch,
            AutomationId.RoutesList,
            AutomationId.RoutesLoading,
            AutomationId.RoutesEmpty,
            AutomationId.RoutesOffline,
            AutomationId.RoutesUnavailable,
            AutomationId.RoutesError,
            AutomationId.RoutesRetry,
            AutomationId.RoutesOption,
            AutomationId.RoutesCancel,
            AutomationId.RoutesSelectionCount,
            AutomationId.RoutesSelectionWarning,
            AutomationId.RoutesConfirm,
        )

        assertEquals(automation.size, automation.toSet().size)
        assertTrue(automation.all { it.startsWith("routes.") })
        assertTrue(screen.contains("items(state.visibleRoutes, key = { it.id.value })"))
        assertTrue(screen.contains("rememberSaveable(state.cityId?.value, saver = LazyListState.Saver)"))
        assertCodePath(
            screen.functionBody("private fun RouteRow"),
            "testTag(AutomationId.RoutesOption)",
            "selectable(",
            "role = Role.Checkbox",
            "semantics(mergeDescendants = true)",
            "contentDescription = accessibilityDescription",
            "stateDescription = selectionState",
            "Checkbox(",
            "enabled = isToggleEnabled",
        )
        val row = screen.functionBody("private fun RouteRow")
        assertFalse(row.contains("route.id"), "Rows expose localized public labels, never opaque route identifiers.")
        assertTrue(row.contains("routes_row_limit_reached"))
        assertCodePath(
            row,
            "val isToggleEnabled = selected || !selectionLimitReached",
            "selectable(",
            "enabled = isToggleEnabled",
        )
        assertTrue(screen.contains("RouteSelectionPolicy.MaximumSelectedRoutes"))
        assertTrue(screen.contains("testTag(AutomationId.RoutesSelectionCount)"))
        assertTrue(screen.contains("testTag(AutomationId.RoutesSelectionWarning)"))
    }

    @Test
    fun modesAndDirectionAreLocalizedAndTheMapRouteActionIsCapabilityGated() {
        val root = projectRoot()
        val routesScreen = root.resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/routes/RoutesScreen.kt",
        ).readText()
        val mapScreen = root.resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapScreen.kt",
        ).readText()
        val mapViewModel = root.resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapViewModel.kt",
        ).readText()

        assertCodePath(
            routesScreen.functionBody("private fun RouteRow"),
            "routes_row_description_with_direction",
            "routes_row_description",
            "Res.string.routes_direction",
            "Text(mode",
        )
        assertCodePath(
            routesScreen.functionBody("private fun TransitMode.displayName"),
            "TransitMode.Bus -> Res.string.routes_mode_bus",
            "TransitMode.Metro -> Res.string.routes_mode_metro",
            "TransitMode.Tram -> Res.string.routes_mode_tram",
            "TransitMode.Ferry -> Res.string.routes_mode_ferry",
        )
        assertCodePath(
            mapScreen.functionBody("private fun Content"),
            "if (state.routesAvailable)",
            "testTag(AutomationId.MapRoutes)",
        )
        assertCodePath(
            mapViewModel.functionBody("fun dispatchAction"),
            "Action.RoutesClicked -> intent",
            "currentCity?.capabilities?.routes == true",
            "NavigationEffect.OpenRoutes",
        )
    }

    @Test
    fun routeMaestroSelectorsRemainLocaleIndependentAndUseTheCancelContract() {
        val root = projectRoot()
        val shell = root.resolve("ui-tests/maestro/flows/shell-smoke.yaml").readText()
        val offlineReopen = root.resolve("ui-tests/maestro/flows/offline-route-cache-reopen.yaml").readText()
        val startupPersistence = root.resolve("ui-tests/maestro/flows/startup-persistence-smoke.yaml").readText()
        val vehicleReenter = root.resolve("ui-tests/maestro/flows/vehicle-realtime-enter-leave.yaml").readText()
        val routeSelection = root.resolve("ui-tests/maestro/flows/route-selection-smoke.yaml").readText()
        val routeSelectionHarness = root.resolve("ui-tests/maestro/run-route-selection-smoke.sh").readText()

        listOf(shell, offlineReopen, startupPersistence, vehicleReenter, routeSelection).forEach { flow ->
            assertFalse(flow.contains("text:"), "Maestro selectors must not use localized text.")
            assertFalse(flow.contains("point:"), "Maestro selectors must not use raw coordinates.")
            assertTrue(flow.contains("id: ${AutomationId.RoutesScreen}"))
        }
        assertTrue(shell.contains("tapOn:\n    id: ${AutomationId.RoutesOption}\n    index: 0"))
        assertTrue(shell.contains("tapOn:\n    id: ${AutomationId.RoutesConfirm}"))
        assertTrue(shell.contains("id: ${AutomationId.RoutesSelectionCount}"))
        assertTrue(shell.contains("id: ${AutomationId.RoutesCancel}"))
        assertFalse(shell.contains("routes.back"))
        assertFalse(startupPersistence.contains("routes.back"))
        assertFalse(vehicleReenter.contains("routes.back"))
        assertTrue(startupPersistence.contains("id: ${AutomationId.RoutesCancel}"))
        assertTrue(vehicleReenter.contains("id: ${AutomationId.RoutesCancel}"))
        assertTrue(routeSelection.contains("id: ${AutomationId.RoutesSelectionCount}"))
        assertTrue(routeSelection.contains("id: ${AutomationId.RoutesSelectionWarning}"))
        assertTrue(routeSelection.contains("id: ${AutomationId.RoutesCancel}"))
        assertFalse(routeSelection.contains("route:"), "Opaque route IDs cannot become Maestro selectors.")
        assertTrue(routeSelectionHarness.contains("test-only-route-selection-fixture"))
        assertTrue(routeSelectionHarness.contains("emulator-*"))
        assertTrue(routeSelectionHarness.contains("simctl list devices booted"))
        assertTrue(routeSelectionHarness.contains("maestro --device \"\$target\" test \"\$flow_path\""))
        assertTrue(offlineReopen.contains("clearState: false"))
        assertTrue(offlineReopen.contains("id: ${AutomationId.RoutesOption}"))
        assertTrue(offlineReopen.contains("assertNotVisible:\n    id: ${AutomationId.RoutesError}"))
    }

    private fun stringValues(path: Path): Map<String, ResourceString> =
        path.toFile().inputStream().use(::stringValues)

    private fun stringValues(input: InputStream): Map<String, ResourceString> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val nodes = factory.newDocumentBuilder().parse(input).getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                require(name.isNotBlank()) { "Every <string> resource must have a non-blank name." }
                val previous = put(
                    name,
                    ResourceString(
                        value = element.textContent,
                        formatted = element.getAttribute("formatted") != "false",
                    ),
                )
                require(previous == null) { "Duplicate <string> resource '$name'." }
            }
        }
    }

    private fun placeholderSignature(resource: ResourceString): Map<Int, Set<String>> {
        if (!resource.formatted) return emptyMap()

        val signature = sortedMapOf<Int, MutableSet<String>>()
        var cursor = 0
        var nextOrdinaryIndex = 1
        var previousArgumentIndex: Int? = null
        while (true) {
            val tokenStart = resource.value.indexOf('%', cursor)
            if (tokenStart < 0) break
            val token = FormatterToken.matchAt(resource.value, tokenStart)
                ?: error("Malformed formatter token at index $tokenStart in '${resource.value}'.")
            val flags = token.groupValues[2]
            val dateTimePrefix = token.groupValues[5]
            val conversion = token.groupValues[6].single()
            val normalizedConversion = when {
                dateTimePrefix.isNotEmpty() -> {
                    require(conversion in DateTimeConversions) {
                        "Unsupported date/time conversion '${token.value}'."
                    }
                    "t${conversion.lowercaseChar()}"
                }

                conversion in NoArgumentConversions -> null
                conversion in ArgumentConversions -> conversion.lowercaseChar().toString()
                else -> error("Unsupported formatter conversion '${token.value}'.")
            }

            if (normalizedConversion == null) {
                require(token.groupValues[1].isEmpty() && '<' !in flags) {
                    "Non-argument formatter token '${token.value}' cannot select an argument."
                }
            } else {
                val argumentIndex = when {
                    token.groupValues[1].isNotEmpty() -> token.groupValues[1].toInt()
                    '<' in flags -> requireNotNull(previousArgumentIndex) {
                        "Relative formatter token '${token.value}' has no previous argument."
                    }

                    else -> nextOrdinaryIndex++
                }
                signature.getOrPut(argumentIndex, ::sortedSetOf).add(normalizedConversion)
                previousArgumentIndex = argumentIndex
            }
            cursor = token.range.last + 1
        }
        return signature
    }

    private fun String.functionBody(declaration: String): String {
        val declarationStart = indexOf(declaration).also { check(it >= 0) }
        val bodyStart = indexOf('{', declarationStart).also { check(it >= 0) }
        val bodyEnd = matchingDelimiter(bodyStart)
        return substring(bodyStart + 1, bodyEnd)
    }

    private fun String.matchingDelimiter(start: Int): Int {
        var depth = 0
        for (index in start until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("No matching delimiter")
    }

    private fun assertCodePath(source: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = source.indexOf(fragment, startIndex = previous + 1)
            assertTrue(index >= 0, "Expected '$fragment' after index $previous")
            previous = index
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("shared/src/commonMain/composeResources")) }
            ?: error("Could not locate the project root from ${Path.of("").toAbsolutePath()}")

    private companion object {
        data class ResourceString(
            val value: String,
            val formatted: Boolean = true,
        )

        val FormatterToken = Regex(
            """%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([tT])?([A-Za-z%])""",
        )
        val ArgumentConversions = "bBhHsScCdoxXeEfgGaA".toSet()
        val DateTimeConversions = "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc".toSet()
        val NoArgumentConversions = setOf('%', 'n')
        val RouteResourceKeys = setOf(
            "routes_cancel_action",
            "routes_confirm_action",
            "routes_selection_count",
            "routes_selection_limit_warning",
            "routes_loading",
            "routes_empty",
            "routes_empty_search",
            "routes_error",
            "routes_offline",
            "routes_offline_unavailable",
            "routes_unavailable",
            "routes_retry",
            "routes_search_label",
            "routes_search_placeholder",
            "routes_mode_bus",
            "routes_mode_metro",
            "routes_mode_tram",
            "routes_mode_ferry",
            "routes_direction",
            "routes_row_description",
            "routes_row_description_with_direction",
            "routes_row_selected",
            "routes_row_unselected",
            "routes_row_limit_reached",
        )
    }
}
