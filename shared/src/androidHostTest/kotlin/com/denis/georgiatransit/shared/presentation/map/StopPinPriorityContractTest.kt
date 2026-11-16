package com.denis.georgiatransit.shared.presentation.map

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the selected-stop z-order without reintroducing independently updated stop sources. */
class StopPinPriorityContractTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("shared/src/mobileMain/composeResources")) }

    @Test
    fun androidKeepsTheBasePinAndDrawsTheSelectedCopyAboveVehiclesFromTheSameSource() {
        val layers = androidSource("MapOverlayLayers.android.kt")
        val sources = androidSource("MapOverlaySources.android.kt")
        val taps = androidSource("MapHitTesting.android.kt")

        assertBefore(layers, "addLayer(stopPinLayer(STOPS_SOURCE_ID))", "addLayer(vehicleLayer)")
        assertBefore(
            layers,
            "addLayerBelow(attentionLayer, VEHICLES_LAYER_ID)",
            "stopPinLayer(STOPS_SOURCE_ID, SELECTED_STOP_PIN_LAYER_ID)",
        )
        assertTrue(layers.contains("Expression.eq(Expression.get(STOP_PIN_SELECTED_PROPERTY), Expression.literal(true))"))
        assertTrue(sources.contains("feature.addBooleanProperty(STOP_PIN_SELECTED_PROPERTY, marker.isSelected)"))
        assertTrue(sources.contains("setSourceFeatures(STOPS_SOURCE_ID, ordinaryStopFeatures(renderState))"))
        assertFalse(sources.contains(".filter { !it.isSelected"), "The base pin must stay present during selection")
        assertFalse(layers.contains("SELECTED_STOP_SOURCE_ID"))
        assertFalse(sources.contains("SELECTED_STOP_SOURCE_ID"))
        assertBefore(
            taps,
            "findEntityFeature(pinHitRect, SELECTED_STOP_PIN_LAYER_ID",
            "findEntityFeature(pinHitRect, stopPinLayerId(STOPS_SOURCE_ID)",
        )
    }

    @Test
    fun iosKeepsTheBasePinAndDrawsTheSelectedCopyAboveVehiclesFromTheSameSource() {
        val swift = root.resolve("iosApp/iosApp/MapLibreMapViewBridge.swift").readText()

        assertBefore(swift, "style.addLayer(stopPinLayer(source: stopsSource))", "style.addLayer(vehiclesLayer)")
        assertBefore(
            swift,
            "style.insertLayer(attentionLayer, below: vehiclesLayer)",
            "stopPinLayer(source: stopsSource, identifier: Self.selectedStopPinLayerID)",
        )
        assertTrue(swift.contains("selectedStopPinLayer.predicate = NSPredicate(format: \"%K == YES\", Self.stopPinSelectedProperty)"))
        assertTrue(swift.contains("style.addLayer(selectedStopPinLayer)"))
        assertTrue(swift.contains("Self.stopPinSelectedProperty: marker.isSelected"))
        assertFalse(swift.contains(".filter { !$0.isSelected &&"), "The base pin must stay present during selection")
        assertFalse(swift.contains("selectedStopSourceID"))
        assertBefore(
            swift,
            "in: pinHitRect, layerID: Self.selectedStopPinLayerID",
            "in: pinHitRect, layerID: Self.stopPinLayerID(sourceID: Self.stopsSourceID)",
        )
    }

    private fun androidSource(name: String): String = root.resolve(
        "shared/src/androidMain/kotlin/com/denis/georgiatransit/shared/presentation/map/$name",
    ).readText()

    private fun assertBefore(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing code path: $first")
        assertTrue(secondIndex > firstIndex, "Expected $first before $second")
    }
}
