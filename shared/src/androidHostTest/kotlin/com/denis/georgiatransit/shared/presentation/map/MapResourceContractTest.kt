package com.denis.georgiatransit.shared.presentation.map

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

/**
 * Resource and native-boundary checks that are deliberately host tests: Compose UI test APIs are
 * not on this source set, but the locale-independent automation and adapter contracts are.
 */
class MapResourceContractTest {
    @Test
    fun englishGeorgianAndRussianMapResourcesStayInParity() {
        val resourceRoot = projectRoot().resolve("shared/src/commonMain/composeResources")
        val english = stringValues(resourceRoot.resolve("values/strings.xml"))
        val georgian = stringValues(resourceRoot.resolve("values-ka/strings.xml"))
        val russian = stringValues(resourceRoot.resolve("values-ru/strings.xml"))

        assertEquals(english.keys, georgian.keys, "Georgian string keys must match the default resource set")
        assertEquals(english.keys, russian.keys, "Russian string keys must match the default resource set")
        assertTrue(MapResourceKeys.all { it in english.keys })
        english.keys.forEach { key ->
            val expected = placeholderSignature(english.getValue(key))
            assertEquals(
                expected,
                placeholderSignature(georgian.getValue(key)),
                "Georgian placeholder indexes and conversion types must match for '$key'",
            )
            assertEquals(
                expected,
                placeholderSignature(russian.getValue(key)),
                "Russian placeholder indexes and conversion types must match for '$key'",
            )
        }
    }

    @Test
    fun indexedPlaceholderContractsIgnoreOrderButPreserveIndexesAndConversionTypes() {
        val english = ResourceString("%1\$s has %2\$04d arrivals")
        val reordered = ResourceString("%2\$04d arrivals — %1\$S")
        val wrongIndexType = ResourceString("%1\$d arrivals — %2\$s")

        assertEquals(
            mapOf(1 to setOf("s"), 2 to setOf("d")),
            placeholderSignature(english),
        )
        assertEquals(placeholderSignature(english), placeholderSignature(reordered))
        assertFalse(
            placeholderSignature(english) == placeholderSignature(wrongIndexType),
            "Moving a conversion type to another argument index must change the contract.",
        )
    }

    @Test
    fun repeatedArgumentIndexMayUseMultipleConversionsInAnyOrder() {
        val dateThenTime = ResourceString("%1\$tF %1\$tT")
        val timeThenDate = ResourceString("%1\$tT %1\$tF")
        val decimalAndHex = ResourceString("%1\$d (%1\$x)")

        assertEquals(mapOf(1 to setOf("tf", "tt")), placeholderSignature(dateThenTime))
        assertEquals(placeholderSignature(dateThenTime), placeholderSignature(timeThenDate))
        assertEquals(mapOf(1 to setOf("d", "x")), placeholderSignature(decimalAndHex))
    }

    @Test
    fun resourceParserAcceptsAdditionalXmlAttributesAndHonorsFormattedFalse() {
        val resources = stringValues(
            """
            <resources>
                <string translatable="false" product="default" name="sample">%2${'$'}04d / %1${'$'}s</string>
                <string product="default" name="literal_percent" formatted="false">100% ready</string>
            </resources>
            """.trimIndent(),
        )

        assertEquals(
            mapOf(1 to setOf("s"), 2 to setOf("d")),
            placeholderSignature(resources.getValue("sample")),
        )
        assertEquals(emptyMap(), placeholderSignature(resources.getValue("literal_percent")))
    }

    @Test
    fun mapAutomationIdsAreFixedAndCoverAllInformationalOverlaysAndActions() {
        val mapIds = listOf(
            AutomationId.MapScreen,
            AutomationId.MapRoutes,
            AutomationId.MapChangeCity,
            AutomationId.MapMyLocation,
            AutomationId.MapLocationSettings,
            AutomationId.MapUserLocation,
            AutomationId.MapLoading,
            AutomationId.MapEmpty,
            AutomationId.MapError,
            AutomationId.MapUnavailable,
            AutomationId.MapOffline,
            AutomationId.MapLocalPreview,
            AutomationId.MapSelectedStop,
            AutomationId.MapNearbyStops,
            AutomationId.MapNearbyStop,
            AutomationId.MapRetry,
            AutomationId.MapAttribution,
        )

        assertEquals(mapIds.size, mapIds.toSet().size)
        assertTrue(mapIds.all { it.startsWith("map.") })
        assertEquals("map.screen", AutomationId.MapScreen)
        assertEquals("map.my-location", AutomationId.MapMyLocation)
        assertEquals("map.location-settings", AutomationId.MapLocationSettings)
        assertEquals("map.content.local-preview", AutomationId.MapLocalPreview)
        assertEquals("map.selected-stop", AutomationId.MapSelectedStop)
        assertEquals("map.nearby-stops", AutomationId.MapNearbyStops)
        assertEquals("map.nearby-stop", AutomationId.MapNearbyStop)
        assertEquals("map.retry", AutomationId.MapRetry)
        assertEquals("map.attribution.link.transitous", AutomationId.mapAttributionLink("transitous"))
    }

    @Test
    fun informationalOverlaysAreLocalizedAndDoNotReplaceMapActions() {
        val screen = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapScreen.kt",
        ).readText()
        val viewModel = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapViewModel.kt",
        ).readText()
        val canvas = screen.functionBody("private fun MapCanvas")
        val contentOverlay = screen.functionBody("private fun MapContentOverlay")
        val baseLayerOverlay = screen.functionBody("private fun BaseLayerOverlay")
        val nearbyStops = screen.functionBody("private fun NearbyStopsAccessibility")
        val content = screen.functionBody("private fun Content")

        assertCodePath(baseLayerOverlay, "MapBaseLayerState.LocalPreview", "map_preview_note", "MapLocalPreview")
        assertCodePath(contentOverlay, "MapContentState.Loading", "map_content_loading", "MapLoading")
        assertCodePath(contentOverlay, "MapContentState.Empty", "map_content_empty", "MapEmpty")
        assertCodePath(contentOverlay, "MapContentState.RetryableError", "map_content_error", "MapError")
        assertCodePath(contentOverlay, "MapContentState.Unavailable", "map_content_unavailable", "MapUnavailable")
        assertCodePath(contentOverlay, "MapContentState.Offline", "contentState.isStale", "map_content_stale", "map_content_offline", "MapOffline")
        assertTrue(contentOverlay.compactWhitespace().contains("MapContentState.Ready -> return"))
        assertTrue(viewModel.compactWhitespace().contains("contentState = MapContentState.Loading"))
        val userLocationTag = canvas.indexOf("AutomationId.MapUserLocation")
        val platformMapCall = canvas.indexOf("PlatformMap(")
        assertTrue(userLocationTag >= 0)
        assertTrue(platformMapCall >= 0)
        assertTrue(
            userLocationTag < platformMapCall,
            "The conditional user-location ID must live on the Compose wrapper, before PlatformMap.",
        )
        assertCodePath(canvas, "PlatformMap(", "onEvent = onMapEvent", "MapStatusOverlays(", "onRetry = onRetry", "Button(")
        assertTrue(canvas.compactWhitespace().contains("testTag(locationActionAutomationId)"))
        assertCodePath(contentOverlay, "contentState is MapContentState.RetryableError", "Button(", "onClick = onRetry", "MapRetry")
        assertCodePath(nearbyStops, "stops.isEmpty()", "MapNearbyStops", "items(", "key = { it.id.value }", "onStopSelected(stop.id)", "MapNearbyStop")
        assertCodePath(content, "state.selectedStop?.let", "MapSelectedStop", "liveRegion = LiveRegionMode.Polite")
    }

    @Test
    fun mapSmokeFlowsUseOnlyStableIdsAndCoverPreviewOverlayPermissionFallbackAndLocation() {
        val root = projectRoot()
        val shell = root.resolve(ShellSmokeFlow).readText()
        val denied = root.resolve(LocationSettingsFallbackFlow).readText()
        val granted = root.resolve(LocationGrantedFlow).readText()

        listOf(shell, denied, granted).forEach { flow ->
            assertFalse(flow.contains("text:"), "Maestro selectors must not depend on localized text")
            assertFalse(flow.contains("point:"), "Maestro selectors must not use raw coordinates")
            assertTrue(flow.contains("id: ${AutomationId.MapScreen}"))
        }
        assertTrue(shell.contains("id: ${AutomationId.MapLocalPreview}"))
        assertTrue(shell.contains("id: ${AutomationId.MapMyLocation}"))
        assertTrue(shell.contains("tapOn:\n    id: ${AutomationId.MapRoutes}"))
        assertTrue(denied.contains("id: ${AutomationId.MapLocalPreview}"))
        assertTrue(denied.contains("id: ${AutomationId.MapLocationSettings}"))
        assertTrue(denied.contains("tapOn:\n    id: ${AutomationId.MapRoutes}"))
        assertTrue(granted.contains("id: ${AutomationId.MapMyLocation}"))
        assertTrue(granted.contains("id: ${AutomationId.MapUserLocation}"))
    }

    @Test
    fun androidAndIosAdaptersReceiveTypedStateAndReleaseResourcesWithoutMapSdkPermissionOwnership() {
        val root = projectRoot()
        val android = root.resolve(
            "shared/src/androidMain/kotlin/com/denis/georgiatransit/shared/presentation/map/PlatformMap.android.kt",
        ).readText()
        val ios = root.resolve(
            "shared/src/iosMain/kotlin/com/denis/georgiatransit/shared/presentation/map/PlatformMap.ios.kt",
        ).readText()
        val iosBridge = root.resolve(
            "shared/src/iosMain/kotlin/com/denis/georgiatransit/shared/presentation/map/IosMapCompositionBridge.kt",
        ).readText()
        val swift = root.resolve("iosApp/iosApp/MapLibreMapViewBridge.swift").readText()
        val androidHitTest = android.functionBody("private fun onMapClick")
        val iosHitTest = swift.functionBody("private func handleMapTap")

        assertTrue(android.contains("actual fun PlatformMap("))
        assertTrue(android.contains("onEvent: (MapPlatformEvent) -> Unit"))
        assertTrue(android.contains("MapPlatformEvent.ViewportSettled"))
        assertCodePath(
            androidHitTest,
            "MIN_STOP_TARGET_DP / 2f",
            "RectF(",
            "screenPoint.x - halfTarget",
            "screenPoint.x + halfTarget",
            "queryRenderedFeatures(hitRect, STOPS_LAYER_ID)",
            "FEATURE_KIND_PROPERTY) == FEATURE_KIND_STOP",
            "MapPlatformEvent.StopTapped",
            "StopId(stopId)",
        )
        assertTrue(
            android.numericDeclaration("MIN_STOP_TARGET_DP") >= 48.0,
            "Android stop activation target must remain at least 48dp.",
        )
        assertTrue(android.contains("MapLibre.setConnected(false)"))
        assertTrue(android.contains("lastAppliedCameraRevision == renderState.camera.revision"))
        assertTrue(android.contains("if (destroyed) return"))
        assertTrue(
            android.compactWhitespace().contains("onRelease = { lifecycle.destroy() controller.destroy() }"),
        )

        assertTrue(ios.contains("actual fun PlatformMap("))
        assertTrue(ios.contains("onEvent: (MapPlatformEvent) -> Unit"))
        assertTrue(ios.contains("UIKitInteropInteractionMode.NonCooperative"))
        assertTrue(ios.contains("onRelease = IosMapCompositionBridge::releaseMapView"))
        assertTrue(iosBridge.contains("((MapViewport) -> Unit, (StopId) -> Unit) -> UIView"))
        assertTrue(iosBridge.contains("MapPlatformEvent.ViewportSettled"))
        assertTrue(iosBridge.contains("MapPlatformEvent.StopTapped"))
        assertTrue(iosBridge.contains("(UIView, MapRenderState) -> Unit"))
        assertTrue(iosBridge.contains("fun releaseMapView(view: UIView)"))
        assertCodePath(
            iosHitTest,
            "Self.minimumStopTargetPoints / 2",
            "CGRect(",
            "width: Self.minimumStopTargetPoints",
            "height: Self.minimumStopTargetPoints",
            "visibleFeatures(in: hitRect",
            "Self.featureKindProperty",
            "Self.stopFeatureKind",
            "onStopTapped(id)",
        )
        assertTrue(
            swift.numericDeclaration("minimumStopTargetPoints") >= 44.0,
            "iOS stop activation target must remain at least 44pt.",
        )
        assertTrue(swift.contains("func update(view: UIView, renderState: MapRenderState)"))
        assertTrue(swift.contains("guard lastAppliedCameraRevision != command.revision"))
        assertTrue(swift.contains("func releaseResources()"))
        assertTrue(swift.contains("mapView.delegate = nil"))
        assertTrue(swift.contains("mapView.shouldRequestAuthorizationToUseLocationServices = false"))
        assertTrue(swift.contains("mapView.disableLocationManager()"))
        assertFalse(swift.contains("http://"))
        assertFalse(swift.contains("https://"))
    }

    private fun stringValues(path: Path): Map<String, ResourceString> =
        Files.newInputStream(path).use(::stringValues)

    private fun stringValues(xml: String): Map<String, ResourceString> =
        xml.byteInputStream().use(::stringValues)

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
        val bodyEnd = matchingDelimiter(bodyStart, '{', '}')
        return substring(bodyStart + 1, bodyEnd)
    }

    private fun String.matchingDelimiter(start: Int, opening: Char, closing: Char): Int {
        var depth = 0
        for (index in start until length) {
            when (this[index]) {
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("No matching '$closing' for '$opening' at index $start")
    }

    private fun String.compactWhitespace(): String = replace(Regex("\\s+"), " ")

    private fun assertCodePath(source: String, vararg fragments: String) {
        val compact = source.compactWhitespace()
        var previous = -1
        fragments.forEach { fragment ->
            val index = compact.indexOf(fragment, startIndex = previous + 1)
            assertTrue(index >= 0, "Expected code path fragment '$fragment' after index $previous in: $compact")
            previous = index
        }
    }

    private fun String.numericDeclaration(name: String): Double {
        val declaration = Regex(
            """\b(?:private\s+)?(?:static\s+)?(?:const\s+)?(?:let|val)\s+${Regex.escape(name)}(?:\s*:\s*[A-Za-z0-9_.<>]+)?\s*=\s*([0-9]+(?:\.[0-9]+)?)(?:[fFdD])?\b""",
        )
        return declaration.find(this)?.groupValues?.get(1)?.toDouble()
            ?: error("Could not parse numeric declaration '$name'")
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
        val MapResourceKeys = setOf(
            "map_title",
            "map_preview_note",
            "map_content_loading",
            "map_content_empty",
            "map_content_error",
            "map_content_unavailable",
            "map_content_offline",
            "map_content_stale",
            "map_routes_action",
            "map_change_city_action",
            "map_my_location_action",
            "map_selected_routes",
            "map_selected_stop",
            "map_nearby_stops_title",
            "map_cluster_stops",
            "map_retry_action",
            "map_attribution_title",
        )
        const val ShellSmokeFlow = "ui-tests/maestro/flows/shell-smoke.yaml"
        const val LocationSettingsFallbackFlow = "ui-tests/maestro/flows/location-settings-fallback-smoke.yaml"
        const val LocationGrantedFlow = "ui-tests/maestro/flows/location-granted-smoke.yaml"
    }
}
