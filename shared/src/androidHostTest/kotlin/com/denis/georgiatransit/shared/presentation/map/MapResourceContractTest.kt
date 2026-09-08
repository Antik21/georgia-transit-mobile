package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resource and native-boundary checks that are deliberately host tests: Compose UI test APIs are
 * not on this source set, but the locale-independent automation and adapter contracts are.
 */
class MapResourceContractTest {
    @Test
    fun englishGeorgianAndRussianMapResourcesStayInParity() {
        val resourceRoot = projectRoot().resolve("shared/src/commonMain/composeResources")
        val english = stringKeys(resourceRoot.resolve("values/strings.xml"))
        val georgian = stringKeys(resourceRoot.resolve("values-ka/strings.xml"))
        val russian = stringKeys(resourceRoot.resolve("values-ru/strings.xml"))

        assertEquals(english, georgian, "Georgian string keys must match the default resource set")
        assertEquals(english, russian, "Russian string keys must match the default resource set")
        assertTrue(MapResourceKeys.all { it in english })
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
            AutomationId.MapAttribution,
        )

        assertEquals(mapIds.size, mapIds.toSet().size)
        assertTrue(mapIds.all { it.startsWith("map.") })
        assertEquals("map.screen", AutomationId.MapScreen)
        assertEquals("map.my-location", AutomationId.MapMyLocation)
        assertEquals("map.location-settings", AutomationId.MapLocationSettings)
        assertEquals("map.content.local-preview", AutomationId.MapLocalPreview)
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

        assertTrue(screen.contains("MapContentState.Ready -> return"))
        assertTrue(viewModel.contains("contentState = MapContentState.LocalPreview"))
        assertTrue(screen.contains("MapContentState.LocalPreview -> stringResource(Res.string.map_preview_note) to AutomationId.MapLocalPreview"))
        assertTrue(screen.contains("MapContentState.Loading -> stringResource(Res.string.map_content_loading) to AutomationId.MapLoading"))
        assertTrue(screen.contains("MapContentState.Empty -> stringResource(Res.string.map_content_empty) to AutomationId.MapEmpty"))
        assertTrue(screen.contains("is MapContentState.RetryableError -> stringResource(Res.string.map_content_error) to AutomationId.MapError"))
        assertTrue(screen.contains("MapContentState.Unavailable -> stringResource(Res.string.map_content_unavailable) to AutomationId.MapUnavailable"))
        assertTrue(screen.contains("if (contentState.isStale) Res.string.map_content_stale else Res.string.map_content_offline"))
        assertTrue(screen.contains("AutomationId.MapOffline"))
        assertTrue(screen.contains("AutomationId.MapLocationSettings"))
        assertTrue(screen.contains("AutomationId.MapMyLocation"))
        val userLocationTag = canvas.indexOf("AutomationId.MapUserLocation")
        val platformMapCall = canvas.indexOf("PlatformMap(renderState = renderState")
        assertTrue(userLocationTag >= 0)
        assertTrue(platformMapCall >= 0)
        assertTrue(
            userLocationTag < platformMapCall,
            "The conditional user-location ID must live on the Compose wrapper, before PlatformMap.",
        )
        val overlayIndex = canvas.indexOf("MapContentOverlay(")
        val buttonIndex = canvas.indexOf("Button(")
        assertTrue(overlayIndex >= 0, "MapCanvas must render the informational overlay.")
        assertTrue(buttonIndex >= 0, "MapCanvas must retain its actionable controls.")
        assertTrue(overlayIndex < buttonIndex, "Informational overlays must not replace map actions.")
        assertTrue(canvas.contains(".testTag(locationActionAutomationId)"))
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

        assertTrue(android.contains("actual fun PlatformMap(renderState: MapRenderState"))
        assertTrue(android.contains("MapLibre.setConnected(false)"))
        assertTrue(android.contains("lastAppliedCameraRevision == renderState.camera.revision"))
        assertTrue(android.contains("if (destroyed) return"))
        assertTrue(
            android.compactWhitespace().contains("onRelease = { lifecycle.destroy() controller.destroy() }"),
        )

        assertTrue(ios.contains("actual fun PlatformMap(renderState: MapRenderState"))
        assertTrue(ios.contains("onRelease = IosMapCompositionBridge::releaseMapView"))
        assertTrue(iosBridge.contains("(UIView, MapRenderState) -> Unit"))
        assertTrue(iosBridge.contains("fun releaseMapView(view: UIView)"))
        assertTrue(swift.contains("func update(view: UIView, renderState: MapRenderState)"))
        assertTrue(swift.contains("guard lastAppliedCameraRevision != command.revision"))
        assertTrue(swift.contains("func releaseResources()"))
        assertTrue(swift.contains("mapView.delegate = nil"))
        assertTrue(swift.contains("mapView.shouldRequestAuthorizationToUseLocationServices = false"))
        assertTrue(swift.contains("mapView.disableLocationManager()"))
        assertFalse(swift.contains("http://"))
        assertFalse(swift.contains("https://"))
    }

    private fun stringKeys(path: Path): Set<String> =
        StringKey.findAll(path.readText()).map { it.groupValues[1] }.toSet()

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

    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("shared/src/commonMain/composeResources")) }
            ?: error("Could not locate the project root from ${Path.of("").toAbsolutePath()}")

    private companion object {
        val StringKey = Regex("""<string\s+name="([^"]+)">""")
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
            "map_attribution_title",
        )
        const val ShellSmokeFlow = "ui-tests/maestro/flows/shell-smoke.yaml"
        const val LocationSettingsFallbackFlow = "ui-tests/maestro/flows/location-settings-fallback-smoke.yaml"
        const val LocationGrantedFlow = "ui-tests/maestro/flows/location-granted-smoke.yaml"
    }
}
