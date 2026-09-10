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
            AutomationId.MapVehicles,
            AutomationId.MapVehiclesLiveNonempty,
            AutomationId.MapRetry,
            AutomationId.MapAttribution,
            AutomationId.MapStopArrivalsSheet,
            AutomationId.MapStopArrivalsLoading,
            AutomationId.MapStopArrivalsEmpty,
            AutomationId.MapStopArrivalsPartial,
            AutomationId.MapStopArrivalsRouteDetailsUnavailable,
            AutomationId.MapStopArrivalsOffline,
            AutomationId.MapStopArrivalsError,
            AutomationId.MapStopArrivalsUnavailable,
            AutomationId.MapStopArrivalsRetry,
            AutomationId.MapStopArrivalsClose,
            AutomationId.MapStopArrivalsRows,
            AutomationId.MapStopArrivalsRow,
            AutomationId.MapStopArrivalsSource,
            AutomationId.MapStopArrivalsStale,
            AutomationId.MapStopArrivalsRefreshing,
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
        assertEquals("map.vehicles", AutomationId.MapVehicles)
        assertEquals("map.vehicles.live-nonempty", AutomationId.MapVehiclesLiveNonempty)
        assertEquals("map.retry", AutomationId.MapRetry)
        assertEquals("map.attribution.link.transitous", AutomationId.mapAttributionLink("transitous"))
        assertEquals("map.stop-arrivals.sheet", AutomationId.MapStopArrivalsSheet)
        assertEquals("map.stop-arrivals.close", AutomationId.MapStopArrivalsClose)
        assertEquals("map.stop-arrivals.row", AutomationId.MapStopArrivalsRow)
    }

    @Test
    fun commonStopArrivalsSheetContractUsesLocalizedLabelsAndNeverRendersOpaqueIds() {
        val screen = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapScreen.kt",
        ).readText()
        val sheet = screen.functionBody("private fun StopArrivalsSheet")
        val status = screen.functionBody("private fun StopArrivalsStatus")
        val row = screen.functionBody("private fun StopArrivalRow")

        assertCodePath(
            sheet,
            "MapStopArrivalsSheet",
            "sheet.stopName",
            "map_stop_arrivals_stop_code",
            "MapStopArrivalsClose",
            "passingRouteShortNames",
            "MapStopArrivalsRows",
        )
        assertCodePath(
            status,
            "StopArrivalsSheetState.Loading",
            "MapStopArrivalsLoading",
            "StopArrivalsSheetState.NoArrivals",
            "MapStopArrivalsEmpty",
            "StopArrivalsSheetState.PartialData",
            "MapStopArrivalsPartial",
            "StopArrivalsSheetState.Offline",
            "MapStopArrivalsOffline",
            "StopArrivalsSheetState.UpstreamError",
            "MapStopArrivalsError",
            "StopArrivalsSheetState.Unavailable",
            "MapStopArrivalsUnavailable",
            "StopArrivalsSheetState.Ready",
        )
        assertCodePath(
            row,
            "row.routeShortName ?: stringResource",
            "row.headsign.ifBlank",
            "arrivalSourceLabel(row.source)",
            "MapStopArrivalsSource",
            "arrivalTimeLabel(row.time)",
        )
        assertFalse(row.contains("routeId"), "Rows must render catalogue labels, never opaque route IDs.")
        assertFalse(row.contains("stopId"), "Rows must never surface opaque stop IDs.")
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
        val content = screen.functionBody("private fun Content")
        val stopArrivalsSheet = screen.functionBody("private fun StopArrivalsSheet")
        val selectedStopUi = viewModel.functionBody("private fun selectedStopUi")
        val stopDisplayName = viewModel.functionBody("private fun TransitStop.displayName")
        val stopAccessibilityLabel = viewModel.functionBody("private fun TransitStop.accessibilityLabel")

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
        assertFalse(content.contains("NearbyStopsAccessibility"), "Nearby stops must not consume visual space below the map.")
        assertCodePath(
            canvas,
            "accessibleStops = localizedRenderState.stops.filter",
            "MapNearbyStops",
            "MapNearbyStop",
            "contentDescription = nearbyStopsLabel",
            "onClick(label = firstStop.accessibilityLabel)",
            "customActions = accessibleStops.take(MAX_ACCESSIBILITY_CUSTOM_ACTIONS).map",
            "MapPlatformEvent.StopTapped",
        )
        assertTrue(screen.contains("private const val MAX_ACCESSIBILITY_CUSTOM_ACTIONS = 31"))
        assertTrue(
            viewModel.compactWhitespace().contains("accessibilityLabel = stop.accessibilityLabel(currentLocale)"),
        )
        assertCodePath(selectedStopUi, "stop.displayName(currentLocale)", "takeIf(String::isNotBlank)")
        assertCodePath(stopDisplayName, "name.forLocale(locale)", "code")
        assertFalse(stopDisplayName.contains("id.value"), "Stop labels must never surface opaque stop IDs.")
        assertCodePath(stopAccessibilityLabel, "displayName(locale)")
        assertFalse(
            stopAccessibilityLabel.contains("id.value"),
            "Accessibility labels must never surface opaque stop IDs.",
        )
        assertCodePath(content, "StopArrivalsSheet(", "onDismiss = { onAction(Action.StopArrivalsDismissed) }")
        assertCodePath(stopArrivalsSheet, "onDismissRequest = onDismiss", "onClick = onDismiss")
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
    fun realtimeVehicleFlowUsesOnlyStableIdsAndRequiresTheOptInDevelopmentBffFixture() {
        val root = projectRoot()
        val flow = root.resolve(VehicleRealtimeFlow).readText()
        val readme = root.resolve(MaestroReadme).readText()

        assertFalse(flow.contains("text:"), "Maestro selectors must not depend on localized text")
        assertFalse(flow.contains("point:"), "Maestro selectors must not use raw coordinates")
        assertTrue(flow.contains("id: ${AutomationId.MapScreen}"))
        assertTrue(flow.contains("id: ${AutomationId.MapVehiclesLiveNonempty}"))
        assertTrue(flow.contains("id: ${AutomationId.MapRoutes}"))
        assertTrue(readme.contains("run-android-vehicle-realtime.sh"))
        assertTrue(readme.contains("BFF_MODE=development BFF_FIXTURES_ENABLED=true"))
        assertTrue(readme.contains("never carries"))
        assertTrue(readme.contains("provider secret"))
    }

    @Test
    fun stopArrivalsFlowUsesOnlyStableIdsAndItsLoopbackFixtureIsExplicitAndPidOwned() {
        val root = projectRoot()
        val flow = root.resolve(StopArrivalsFlow).readText()
        val harness = root.resolve(StopArrivalsHarness).readText()
        val fixture = root.resolve(StopArrivalsFixture).readText()
        val readme = root.resolve(MaestroReadme).readText()

        assertFalse(flow.contains("text:"), "Maestro selectors must not depend on localized text")
        assertFalse(flow.contains("point:"), "Maestro selectors must not use raw coordinates")
        listOf(
            AutomationId.MapScreen,
            AutomationId.MapNearbyStop,
            AutomationId.MapStopArrivalsSheet,
            AutomationId.MapStopArrivalsLoading,
            AutomationId.MapStopArrivalsRows,
            AutomationId.MapStopArrivalsRow,
            AutomationId.MapStopArrivalsSource,
            AutomationId.MapStopArrivalsClose,
        ).forEach { id -> assertTrue(flow.contains("id: $id"), "Missing stable stop-arrivals selector '$id'") }
        assertTrue(flow.contains("tapOn:\n    id: ${AutomationId.MapStopArrivalsClose}"))
        assertTrue(harness.contains("fixture_is_our_process"))
        assertTrue(harness.contains("verify_owned_fixture"))
        assertTrue(harness.contains("kill \"\$fixture_pid\""))
        assertTrue(harness.contains("emulator-*"))
        assertTrue(harness.contains("simctl list devices booted"))
        assertTrue(fixture.contains("127.0.0.1"))
        assertTrue(fixture.contains("test-only-stop-arrivals-fixture"))
        assertFalse(fixture.contains("provider secret"))
        assertTrue(readme.contains("run-stop-arrivals-smoke.sh"))
        assertTrue(readme.contains("never enables release"))
    }

    @Test
    fun realtimeVehicleAdapterSourceSmokePinsGroupedNativeLayerAndReleaseHooks() {
        val root = projectRoot()
        val android = root.resolve(
            "shared/src/androidMain/kotlin/com/denis/georgiatransit/shared/presentation/map/PlatformMap.android.kt",
        ).readText()
        val swift = root.resolve("iosApp/iosApp/MapLibreMapViewBridge.swift").readText()

        assertCodePath(
            android,
            "GeoJsonSource(VEHICLES_SOURCE_ID",
            "SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID)",
            "lastVehicleSourceRevision != renderState.vehicleSourceRevision",
            "lastVehicleBadgeRevision != renderState.vehicleBadgeRevision",
            "MAX_VEHICLE_BADGE_IMAGES = 256",
            "STALE_VEHICLE_OPACITY",
        )
        assertTrue(android.contains("clearRenderedLayerState()"))
        assertTrue(android.contains("layersInstalled = false"))
        assertFalse(android.contains("MarkerView"), "Source smoke: no Android per-vehicle view adapter")
        assertCodePath(
            swift,
            "MLNShapeSource(identifier: Self.vehiclesSourceID",
            "MLNSymbolStyleLayer(identifier: Self.vehiclesLayerID, source: vehiclesSource)",
            "lastVehicleSourceRevision != state.vehicleSourceRevision",
            "lastVehicleBadgeRevision != state.vehicleBadgeRevision",
            "maximumVehicleBadgeImages = 256",
            "staleVehicleOpacity",
        )
        assertTrue(swift.contains("func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle)"))
        assertTrue(swift.contains("func releaseResources()"))
        assertTrue(swift.contains("badgeImageNames.removeAll()"))
        assertFalse(swift.contains("AnnotationView"), "Source smoke: no iOS per-vehicle view adapter")
    }

    @Test
    fun highlightedStopsKeepCommonStyleRevisionAndSelectedTapPriorityOnBothAdapters() {
        val root = projectRoot()
        val android = root.resolve(
            "shared/src/androidMain/kotlin/com/denis/georgiatransit/shared/presentation/map/PlatformMap.android.kt",
        ).readText()
        val swift = root.resolve("iosApp/iosApp/MapLibreMapViewBridge.swift").readText()
        val androidStops = android.declarationSection(
            "private fun ordinaryStopFeatures",
            "private fun selectedStopFeatures",
        )
        val androidSources = android.functionBody("private fun updateSources")
        val androidTap = android.functionBody("private fun onMapClick")
        val iosStops = swift.functionBody(
            "private func ordinaryStopFeatures(_ markers: [MapStopMarker], sourceRevision: Int64)",
        )
        val iosRender = swift.functionBody("private func render")
        val iosTap = swift.functionBody("private func handleMapTap")

        assertCodePath(
            androidStops,
            "!it.isSelected",
            "!it.routeHighlight.isHighlighted",
            "SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString()",
            "marker.routeHighlight.backgroundArgb.asMapColor()",
            "marker.routeHighlight.markerRadius",
            "marker.routeHighlight.textArgb.asMapColor()",
            "marker.routeHighlight.markerStrokeWidth",
        )
        assertCodePath(
            iosStops,
            "!$0.isSelected",
            "first.routeHighlight.isHighlighted",
            "Self.sourceRevisionProperty: String(sourceRevision)",
            "mapColor(marker.routeHighlight.backgroundArgb)",
            "marker.routeHighlight.markerRadius",
            "mapColor(marker.routeHighlight.textArgb)",
            "marker.routeHighlight.markerStrokeWidth",
        )
        assertCodePath(
            androidSources,
            "lastStopSourceRevision != renderState.stopSourceRevision",
            "ordinaryStopFeatures(renderState)",
            "selectedStopFeatures(renderState)",
            "lastStopSourceRevision = renderState.stopSourceRevision",
        )
        assertCodePath(
            iosRender,
            "ordinaryStopFeatures(state.stops, sourceRevision: state.stopSourceRevision)",
            "selectedStopFeatures(state.stops, sourceRevision: state.stopSourceRevision)",
        )
        assertCodePath(
            androidTap,
            "SELECTED_STOP_LAYER_ID, FEATURE_KIND_STOP",
            "STOPS_LAYER_ID, FEATURE_KIND_STOP",
            "VEHICLES_LAYER_ID, FEATURE_KIND_VEHICLE",
        )
        assertCodePath(
            iosTap,
            "Self.selectedStopLayerID, kind: Self.stopFeatureKind",
            "Self.stopsLayerID, kind: Self.stopFeatureKind",
            "Self.vehiclesLayerID, kind: Self.vehicleFeatureKind",
        )
    }

    @Test
    fun routeGeometryLegendUsesStaticAccessibleSelectorsLocalizedStatusAndTypedActions() {
        val screen = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapScreen.kt",
        ).readText()
        val automation = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/ui/automation/AutomationId.kt",
        ).readText()
        val legend = screen.functionBody("private fun RouteGeometryLegend")

        listOf(
            AutomationId.MapRouteGeometryLegend,
            AutomationId.MapRouteGeometryChip,
            AutomationId.MapRouteGeometryRetry,
            AutomationId.MapRouteGeometryRemove,
        ).forEach { id ->
            assertTrue(id.startsWith("map.route-geometry."))
            assertFalse(id.contains('$'), "DEN-69 automation IDs must not include runtime values")
            assertTrue(automation.contains("\"$id\""))
        }
        assertCodePath(
            legend,
            "MapRouteGeometryLegend",
            "items(routes, key = { it.routeId.value })",
            "MapRouteGeometryChip",
            "selected = route.isFocused",
            "MapRouteGeometryRemove",
            "if (route.canRetry)",
            "MapRouteGeometryRetry",
        )
        assertCodePath(
            screen.functionBody("private fun routeGeometryStatusLabel"),
            "RouteGeometryLegendState.Loading",
            "map_route_geometry_loading",
            "RouteGeometryLegendState.Ready",
            "map_route_geometry_ready",
            "RouteGeometryLegendState.Partial",
            "map_route_geometry_partial",
            "RouteGeometryLegendState.Retryable",
            "map_route_geometry_retryable",
            "RouteGeometryLegendState.Unavailable",
            "map_route_geometry_unavailable",
            "RouteGeometryLegendState.PaletteOverflow",
        )
        assertCodePath(
            legend,
            "onFocus(route.routeId)",
            "onRemove(route.routeId)",
            "onRetry(route.routeId)",
        )
    }

    @Test
    fun routeGeometryAdaptersKeepOneOrderedCappedSourceWithRevisionEarlyExitAndStyleReset() {
        val root = projectRoot()
        val android = root.resolve(
            "shared/src/androidMain/kotlin/com/denis/georgiatransit/shared/presentation/map/PlatformMap.android.kt",
        ).readText()
        val swift = root.resolve("iosApp/iosApp/MapLibreMapViewBridge.swift").readText()
        val androidUpdates = android.functionBody("private fun updateSources")
        val androidPolylines = android.declarationSection("private fun polylineFeatures", "private fun userLocationFeatures")
        val androidReset = android.functionBody("private fun clearRenderedLayerState")
        val iosRender = swift.functionBody("private func render")
        val iosPolylines = swift.functionBody("private func polylineFeatures")
        val iosReset = swift.functionBody("private func clearRenderedLayerState")

        assertCodePath(
            android,
            "GeoJsonSource(POLYLINES_SOURCE_ID",
            "LineLayer(POLYLINES_LAYER_ID, POLYLINES_SOURCE_ID)",
            "lineColor(Expression.get(ROUTE_COLOR_PROPERTY))",
            "lineOpacity(Expression.get(ROUTE_OPACITY_PROPERTY))",
            "lineWidth(Expression.get(ROUTE_WIDTH_PROPERTY))",
        )
        assertCodePath(
            swift,
            "MLNShapeSource(identifier: Self.polylinesSourceID",
            "MLNLineStyleLayer(identifier: Self.polylinesLayerID, source: polylinesSource)",
            "polylinesLayer.lineColor = NSExpression(forKeyPath: Self.routeColorProperty)",
            "polylinesLayer.lineWidth = NSExpression(forKeyPath: Self.routeWidthProperty)",
            "polylinesLayer.lineOpacity = NSExpression(forKeyPath: Self.routeOpacityProperty)",
        )
        assertCodePath(
            androidUpdates,
            "if (lastPolylineSourceRevision != renderState.polylineSourceRevision)",
            "polylineFeatures(renderState.polylines)",
            "lastPolylineSourceRevision = renderState.polylineSourceRevision",
        )
        assertCodePath(
            iosRender,
            "if lastPolylineSourceRevision != state.polylineSourceRevision",
            "polylineFeatures(state.polylines)",
            "lastPolylineSourceRevision = state.polylineSourceRevision",
        )
        assertFalse(androidPolylines.contains("sorted"), "Android must preserve common route/direction order, not opaque-ID sort.")
        assertFalse(iosPolylines.contains(".sorted"), "iOS must preserve common route/direction order, not opaque-ID sort.")
        assertCodePath(
            androidPolylines,
            ".take(MAX_POLYLINES)",
            ".take(MAX_POLYLINE_POINTS)",
            "ROUTE_WIDTH_PROPERTY, line.strokeWidth.safePolylineWidth()",
            "ROUTE_OPACITY_PROPERTY, line.opacity.safePolylineOpacity()",
        )
        assertCodePath(
            iosPolylines,
            ".prefix(Self.maximumPolylines)",
            ".prefix(Self.maximumPolylinePoints)",
            "Self.routeWidthProperty: safePolylineWidth(polyline.strokeWidth)",
            "Self.routeOpacityProperty: safePolylineOpacity(polyline.opacity)",
        )
        assertEquals(256.0, android.numericDeclaration("MAX_POLYLINES"))
        assertTrue(android.contains("MAX_POLYLINE_POINTS = 20_000"))
        assertEquals(256.0, swift.numericDeclaration("maximumPolylines"))
        assertTrue(swift.contains("maximumPolylinePoints = 20_000"))
        assertCodePath(androidReset, "lastPolylineSourceRevision = null")
        assertCodePath(iosReset, "lastPolylineSourceRevision = nil")
        assertCodePath(
            swift.functionBody("func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle)"),
            "clearRenderedLayerState()",
            "installSourcesAndLayersIfNeeded(style: style)",
        )
    }

    @Test
    fun routeGeometryMaestroFixtureFlowAndPerformanceHarnessStayLoopbackOnlyAndSelectorStable() {
        val root = projectRoot()
        val flow = root.resolve(RouteGeometryFlow).readText()
        val loadPrime = root.resolve(RouteGeometryLoadPrimeFlow).readText()
        val fixture = root.resolve(RouteGeometryFixture).readText()
        val smokeHarness = root.resolve(RouteGeometrySmokeHarness).readText()
        val loadHarness = root.resolve(RouteGeometryLoadHarness).readText()
        val readme = root.resolve(MaestroReadme).readText()

        val fixtureShortNameSelector = Regex("""(?m)^      - text: "\^G(?:10|[1-9])\$"$""")
        assertEquals(10, fixtureShortNameSelector.findAll(flow).count())
        assertEquals(
            fixtureShortNameSelector.findAll(flow).count(),
            Regex("""(?m)^      - text:""").findAll(flow).count(),
            "Maestro text selectors may use only fixed, nonlocalized DEN-69 fixture short names",
        )
        assertFalse(flow.contains("point:"), "Maestro selectors must not use raw coordinates")
        listOf(
            AutomationId.MapScreen,
            AutomationId.MapRoutes,
            AutomationId.MapRouteGeometryLegend,
            AutomationId.MapRouteGeometryChip,
            AutomationId.MapRouteGeometryRetry,
            AutomationId.MapRouteGeometryRemove,
        ).forEach { id -> assertTrue(flow.contains("id: $id"), "Missing stable route-geometry selector '$id'") }
        assertTrue(flow.contains("start: 50%, 75%"))
        assertTrue(flow.contains("end: 50%, 45%"))
        assertTrue(flow.contains("end: 50%, 62%"))
        assertTrue(flow.contains("text: \"^G10$\""))
        assertTrue(flow.contains("id: ${AutomationId.RoutesOption}\n    containsDescendants:"))
        assertTrue(flow.contains("id: ${AutomationId.RoutesSelectionWarning}"))
        assertTrue(
            flow.contains(
                "notVisible:\n      id: ${AutomationId.MapRouteGeometryRetry}",
            ),
            "Retry must settle before the smoke flow treats the recovered legend as ready.",
        )
        assertTrue(flow.contains("id: ${AutomationId.RoutesSelectionCount}"))
        assertTrue(
            flow.contains("assertNotVisible:\n    id: ${AutomationId.RoutesSelectionWarning}"),
            "After remove/reopen, the portable limit semantic must prove the persisted selection is below ten.",
        )
        assertFalse(flow.contains("inputText:"), "Route geometry flow must not open the iOS keyboard.")
        assertFalse(flow.contains("hideKeyboard"), "iOS hideKeyboard pops Routes in this Compose host.")
        assertTrue(loadPrime.contains("id: ${AutomationId.MapRouteGeometryLegend}"))
        assertTrue(loadPrime.contains("id: ${AutomationId.MapRouteGeometryChip}\n    index: 0"))
        assertFalse(loadPrime.contains("id: ${AutomationId.MapRouteGeometryChip}\n    index: 9"))
        assertTrue(fixture.contains("127.0.0.1"))
        assertTrue(fixture.contains("test-only-route-geometry-fixture"))
        assertTrue(fixture.contains("routes.length * 2"))
        assertTrue(fixture.contains("precision5: true"))
        assertTrue(fixture.contains("precision6: true"))
        assertTrue(fixture.contains("stops: true"), "Fixture Demo City must be selectable by CitySelection.")
        assertTrue(fixture.contains("stops\\/nearby"), "Selectable fixture cities must serve the map's nearby-stops request.")
        assertTrue(fixture.contains("id: `${'$'}{cityId}:fixture:direction:${'$'}{name}-${'$'}{routeIndex}`"))
        assertTrue(fixture.contains("UPSTREAM_BAD_RESPONSE"))
        assertTrue(fixture.contains("}, 502)"))
        assertTrue(fixture.contains("partialDirectionRequests += 1"))
        assertFalse(fixture.contains("provider secret"))
        listOf(smokeHarness, loadHarness).forEach { harness ->
            assertTrue(harness.contains("fixture_is_our_process"))
            assertTrue(harness.contains("verify_owned_fixture"))
            assertTrue(harness.contains("kill \"\$fixture_pid\""))
            assertTrue(harness.contains("emulator-*"))
            assertTrue(harness.contains("simctl list devices booted"))
        }
        assertTrue(loadHarness.contains("dumpsys gfxinfo"))
        assertTrue(loadHarness.contains("dumpsys meminfo"))
        assertTrue(loadHarness.contains("vmmap"))
        assertTrue(loadHarness.contains("rss="))
        assertTrue(smokeHarness.contains("verify_owned_fixture 4"))
        assertTrue(readme.contains("run-route-geometry-smoke.sh"))
        assertTrue(readme.contains("run-route-geometry-load-sample.sh"))
        assertTrue(readme.contains("cannot prove iOS FPS or jank"))
        assertTrue(readme.contains("physical-device performance"))
    }

    @Test
    fun sourcePathHelperIgnoresLineAndBlockComments() {
        val source = """
            // precondition text is not executable
            fun checked() { /* ignored */ val actual = 1 // trailing text
            }
        """.trimIndent()

        assertCodePath(source, "fun checked()", "val actual = 1")
        assertFalse(source.compactWhitespace().contains("precondition text"))
        assertFalse(source.compactWhitespace().contains("ignored"))
    }

    @Test
    fun vehicleTapEventsRouteThroughCurrentFrameAndKnownVehicleGuards() {
        val viewModel = projectRoot().resolve(
            "shared/src/commonMain/kotlin/com/denis/georgiatransit/shared/presentation/map/MapViewModel.kt",
        ).readText()
        val eventHandler = viewModel.functionBody("private fun onMapEvent")
        val vehicleTapHandler = viewModel.functionBody("private fun acceptVehicleTap")
        val compactVehicleTapHandler = vehicleTapHandler.compactWhitespace().trim()

        assertTrue(
            compactVehicleTapHandler.startsWith("if (sourceRevision != vehicleFrameRevision) return"),
            "Vehicle taps must reject an obsolete source revision before inspecting marker identity.",
        )
        assertCodePath(
            vehicleTapHandler,
            "if (sourceRevision != vehicleFrameRevision) return",
            "if (vehicleMarkers.none { it.id == vehicleId }) return",
        )
        assertTrue(
            eventHandler.compactWhitespace().contains(
                "is MapPlatformEvent.VehicleTapped -> acceptVehicleTap(event.vehicleId, event.sourceRevision)",
            ),
            "VehicleTapped must route both its typed ID and source revision through the guarded handler.",
        )
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
        val androidLayers = android.functionBody("private fun installSourcesAndLayers")
        val iosLayers = swift.functionBody("private func installSourcesAndLayersIfNeeded")
        val androidCamera = android.functionBody("private fun applyCameraIfNeeded")
        val iosCamera = swift.functionBody("private func applyCameraIfNeeded")
        val androidSourceUpdates = android.functionBody("private fun updateSources")
        val iosSourceUpdates = swift.functionBody("private func render")
        val androidOrdinaryStopFactories = android.declarationSection(
            "private fun ordinaryStopFeatures",
            "private fun selectedStopFeatures",
        )
        val androidOrdinaryStopFeature = androidOrdinaryStopFactories.functionBody(".map { marker ->")
        val androidClusterFeature = androidOrdinaryStopFactories.functionBody(".map { cluster ->")
        val androidSelectedStopFeature = android.declarationSection(
            "private fun selectedStopFeatures",
            "private fun polylineFeatures",
        )
        val androidVehicleFeatures = android.declarationSection(
            "private fun vehicleFeatures",
            "private fun updateBadgeImages",
        )
        val iosOrdinaryStopFeatures = swift.functionBody(
            "private func ordinaryStopFeatures(_ markers: [MapStopMarker], sourceRevision: Int64)",
        )
        val iosSelectedStopFeatures = swift.functionBody(
            "private func selectedStopFeatures(_ markers: [MapStopMarker], sourceRevision: Int64)",
        )
        val iosClusterFeatures = swift.functionBody(
            "private func clusterFeatures(_ clusters: [MapStopCluster], sourceRevision: Int64)",
        )
        val iosVehicleFeatures = swift.functionBody(
            "private func vehicleFeatures(_ markers: [MapVehicleMarker], sourceRevision: Int64)",
        )

        assertTrue(android.contains("actual fun PlatformMap("))
        assertTrue(android.contains("onEvent: (MapPlatformEvent) -> Unit"))
        assertTrue(android.contains("MapPlatformEvent.ViewportSettled"))
        assertCodePath(
            androidHitTest,
            "MIN_STOP_TARGET_DP / 2f",
            "RectF(",
            "screenPoint.x - halfTarget",
            "screenPoint.x + halfTarget",
            "findEntityFeature(currentMap, hitRect, SELECTED_STOP_LAYER_ID, FEATURE_KIND_STOP)",
            "MapPlatformEvent.StopTapped",
            "findEntityFeature(currentMap, hitRect, STOPS_LAYER_ID, FEATURE_KIND_STOP)",
            "MapPlatformEvent.StopTapped",
            "findEntityFeature(currentMap, hitRect, VEHICLES_LAYER_ID, FEATURE_KIND_VEHICLE)",
            "MapPlatformEvent.VehicleTapped",
        )
        assertFalse(androidHitTest.contains("FEATURE_KIND_CLUSTER"), "Clusters must never emit StopTapped")
        assertCodePath(
            androidOrdinaryStopFeature,
            "FEATURE_KIND_PROPERTY, FEATURE_KIND_STOP",
            "SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString()",
        )
        assertCodePath(
            androidClusterFeature,
            "FEATURE_KIND_PROPERTY, FEATURE_KIND_CLUSTER",
            "SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString()",
        )
        assertCodePath(
            androidSelectedStopFeature,
            "FEATURE_KIND_PROPERTY, FEATURE_KIND_STOP",
            "SOURCE_REVISION_PROPERTY, renderState.stopSourceRevision.toString()",
        )
        assertCodePath(
            androidVehicleFeatures,
            "FEATURE_KIND_PROPERTY, FEATURE_KIND_VEHICLE",
            "SOURCE_REVISION_PROPERTY, sourceRevision.toString()",
        )
        assertCodePath(
            androidSourceUpdates,
            "ordinaryStopFeatures(renderState)",
            "selectedStopFeatures(renderState)",
            "vehicleFeatures(renderState.vehicles, renderState.vehicleSourceRevision)",
        )
        assertCodePath(
            androidLayers,
            "CircleLayer(STOPS_LAYER_ID, STOPS_SOURCE_ID)",
            "SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID)",
            "CircleLayer(SELECTED_STOP_LAYER_ID, SELECTED_STOP_SOURCE_ID)",
        )
        assertTrue(
            android.numericDeclaration("MIN_STOP_TARGET_DP") >= 48.0,
            "Android stop activation target must remain at least 48dp.",
        )
        assertTrue(android.contains("MapLibre.setConnected(false)"))
        assertCodePath(
            androidCamera,
            "lastAppliedCameraRevision == renderState.camera.revision",
            "viewportBottomPadding(command)",
            "lastAppliedCameraRevision = command.revision",
        )
        assertCodePath(
            android.functionBody("private fun viewportBottomPadding"),
            "takeIf(Double::isFinite)",
            "coerceIn(0.0, MAX_BOTTOM_OCCLUSION_FRACTION)",
        )
        assertTrue(android.contains("if (destroyed) return"))
        assertTrue(
            android.compactWhitespace().contains("onRelease = { lifecycle.destroy() controller.destroy() }"),
        )

        assertTrue(ios.contains("actual fun PlatformMap("))
        assertTrue(ios.contains("onEvent: (MapPlatformEvent) -> Unit"))
        assertTrue(ios.contains("UIKitInteropInteractionMode.NonCooperative"))
        assertTrue(ios.contains("onRelease = IosMapCompositionBridge::releaseMapView"))
        assertTrue(iosBridge.contains("((MapViewport) -> Unit, (MapPlatformEvent) -> Unit) -> UIView"))
        assertTrue(iosBridge.contains("MapPlatformEvent.ViewportSettled"))
        assertTrue(iosBridge.contains("(UIView, MapRenderState) -> Unit"))
        assertTrue(iosBridge.contains("fun releaseMapView(view: UIView)"))
        assertCodePath(
            iosHitTest,
            "Self.minimumStopTargetPoints / 2",
            "CGRect(",
            "width: Self.minimumStopTargetPoints",
            "height: Self.minimumStopTargetPoints",
            "entityFeature(in: hitRect, layerID: Self.selectedStopLayerID, kind: Self.stopFeatureKind)",
            "MapPlatformEventStopTapped(stopId: stop.id, sourceRevision: stop.sourceRevision)",
            "entityFeature(in: hitRect, layerID: Self.stopsLayerID, kind: Self.stopFeatureKind)",
            "MapPlatformEventStopTapped(stopId: stop.id, sourceRevision: stop.sourceRevision)",
            "entityFeature(in: hitRect, layerID: Self.vehiclesLayerID, kind: Self.vehicleFeatureKind)",
            "MapPlatformEventVehicleTapped(vehicleId: vehicle.id, sourceRevision: vehicle.sourceRevision)",
        )
        assertFalse(iosHitTest.contains("clusterFeatureKind"), "Clusters must never emit StopTapped")
        assertCodePath(
            iosOrdinaryStopFeatures,
            "Self.featureKindProperty: Self.stopFeatureKind",
            "Self.sourceRevisionProperty: String(sourceRevision)",
        )
        assertCodePath(
            iosSelectedStopFeatures,
            "Self.featureKindProperty: Self.stopFeatureKind",
            "Self.sourceRevisionProperty: String(sourceRevision)",
        )
        assertCodePath(
            iosClusterFeatures,
            "Self.featureKindProperty: Self.clusterFeatureKind",
            "Self.sourceRevisionProperty: String(sourceRevision)",
        )
        assertCodePath(
            iosVehicleFeatures,
            "Self.featureKindProperty: Self.vehicleFeatureKind",
            "Self.sourceRevisionProperty: String(sourceRevision)",
        )
        assertCodePath(
            iosSourceUpdates,
            "ordinaryStopFeatures(state.stops, sourceRevision: state.stopSourceRevision)",
            "clusterFeatures(state.stopClusters, sourceRevision: state.stopSourceRevision)",
            "selectedStopFeatures(state.stops, sourceRevision: state.stopSourceRevision)",
            "vehicleFeatures(state.vehicles, sourceRevision: state.vehicleSourceRevision)",
        )
        assertCodePath(
            iosLayers,
            "MLNCircleStyleLayer(identifier: Self.stopsLayerID, source: stopsSource)",
            "MLNSymbolStyleLayer(identifier: Self.vehiclesLayerID, source: vehiclesSource)",
            "MLNCircleStyleLayer(identifier: Self.selectedStopLayerID, source: selectedStopSource)",
        )
        assertTrue(
            swift.numericDeclaration("minimumStopTargetPoints") >= 44.0,
            "iOS stop activation target must remain at least 44pt.",
        )
        assertTrue(swift.contains("func update(view: UIView, renderState: MapRenderState)"))
        assertCodePath(
            iosCamera,
            "guard lastAppliedCameraRevision != command.revision",
            "applyViewportInsets(command.viewportInsets)",
            "lastAppliedCameraRevision = command.revision",
        )
        assertCodePath(
            swift.functionBody("private func applyViewportInsets"),
            "bottomOcclusionFraction.isFinite",
            "min(max(insets.bottomOcclusionFraction, 0), Self.maximumBottomOcclusionFraction)",
        )
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

    private fun String.declarationSection(declaration: String, nextDeclaration: String): String {
        val declarationStart = indexOf(declaration).also { check(it >= 0) }
        val declarationEnd = indexOf(nextDeclaration, declarationStart + declaration.length).also { check(it >= 0) }
        return substring(declarationStart, declarationEnd)
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

    private fun String.compactWhitespace(): String =
        replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\\r\\n]*"), "")
            .replace(Regex("\\s+"), " ")

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
            "map_stop_arrivals_title",
            "map_stop_arrivals_stop_details_unavailable",
            "map_stop_arrivals_stop_code",
            "map_stop_arrivals_routes",
            "map_stop_arrivals_route_details_unavailable",
            "map_stop_arrivals_loading",
            "map_stop_arrivals_empty",
            "map_stop_arrivals_partial",
            "map_stop_arrivals_offline",
            "map_stop_arrivals_error",
            "map_stop_arrivals_unavailable",
            "map_stop_arrivals_retry",
            "map_stop_arrivals_close",
            "map_stop_arrivals_refreshing",
            "map_stop_arrivals_stale",
            "map_stop_arrivals_route_unavailable",
            "map_stop_arrivals_headsign_unavailable",
            "map_stop_arrivals_arriving",
            "map_stop_arrivals_minutes",
            "map_stop_arrivals_time_unavailable",
            "map_stop_arrivals_source_official",
            "map_stop_arrivals_source_aggregator",
            "map_stop_arrivals_source_schedule",
            "map_stop_arrivals_source_approximate",
            "map_stop_arrivals_page_source",
            "map_nearby_stops_title",
            "map_vehicles_summary",
            "map_vehicle_route_summary",
            "map_vehicle_status_loading",
            "map_vehicle_status_live",
            "map_vehicle_status_stale",
            "map_vehicle_status_retryable",
            "map_vehicle_status_unavailable",
            "map_vehicle_status_mixed",
            "map_cluster_stops",
            "map_retry_action",
            "map_attribution_title",
        )
        const val ShellSmokeFlow = "ui-tests/maestro/flows/shell-smoke.yaml"
        const val LocationSettingsFallbackFlow = "ui-tests/maestro/flows/location-settings-fallback-smoke.yaml"
        const val LocationGrantedFlow = "ui-tests/maestro/flows/location-granted-smoke.yaml"
        const val VehicleRealtimeFlow = "ui-tests/maestro/flows/vehicle-realtime-smoke.yaml"
        const val StopArrivalsFlow = "ui-tests/maestro/flows/stop-arrivals-smoke.yaml"
        const val StopArrivalsHarness = "ui-tests/maestro/run-stop-arrivals-smoke.sh"
        const val StopArrivalsFixture = "ui-tests/maestro/fixtures/stop-arrivals-fixture-server.mjs"
        const val RouteGeometryFlow = "ui-tests/maestro/flows/route-geometry-smoke.yaml"
        const val RouteGeometryLoadPrimeFlow = "ui-tests/maestro/flows/route-geometry-load-prime.yaml"
        const val RouteGeometryFixture = "ui-tests/maestro/fixtures/route-geometry-fixture-server.mjs"
        const val RouteGeometrySmokeHarness = "ui-tests/maestro/run-route-geometry-smoke.sh"
        const val RouteGeometryLoadHarness = "ui-tests/maestro/run-route-geometry-load-sample.sh"
        const val MaestroReadme = "ui-tests/maestro/README.md"
    }
}
