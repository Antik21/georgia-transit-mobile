package com.denis.georgiatransit.shared.presentation.map

import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.tile.TileOperation

/**
 * Owns the base style: the asset-free local fallback, the optional same-BFF remote style, and the
 * generation bookkeeping that drops callbacks from superseded style requests.
 */
internal class MapStyleLoader(
    private val mapView: MapView,
    private val onStyleReset: () -> Unit,
    private val onStyleAccepted: (Style) -> Unit,
) {
    private var map: MapLibreMap? = null
    var style: Style? = null
        private set
    var requestedBffStyleUrl: String? = null
        private set
    private var styleRequestGeneration = 0L
    private var acceptedStyleGeneration = 0L
    private var activeBffStyleUrl: String? = null
    private var destroyed = false
    private val styleLoadedListener = MapView.OnDidFinishLoadingStyleListener {
        val loadedStyle = map?.style ?: return@OnDidFinishLoadingStyleListener
        val uri = loadedStyle.uri.takeIf(String::isNotBlank)
        if (uri != activeBffStyleUrl) return@OnDidFinishLoadingStyleListener
        acceptStyle(loadedStyle)
    }
    private val styleFailureListener = MapView.OnDidFailLoadingMapListener {
        scheduleFallback()
    }
    private val tileListener = MapView.OnTileActionListener { operation, _, _, _, _, _, source ->
        if (operation == TileOperation.Error && source.startsWith("gt-vector-")) scheduleFallback()
    }
    private val glyphErrorListener = MapView.OnGlyphsErrorListener { _, _, _ -> scheduleFallback() }

    init {
        mapView.addOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.addOnDidFailLoadingMapListener(styleFailureListener)
        mapView.addOnTileActionListener(tileListener)
        mapView.addOnGlyphsErrorListener(glyphErrorListener)
    }

    fun attach(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        loadStyle(mapLibreMap, Style.Builder().fromJson(LOCAL_STYLE_JSON))
    }

    fun request(bffStyleUrl: String?) {
        if (requestedBffStyleUrl == bffStyleUrl || destroyed) return
        if (map == null) return
        requestedBffStyleUrl = bffStyleUrl
        loadStyleUrl(bffStyleUrl)
    }

    private fun scheduleFallback() {
        if (activeBffStyleUrl == null || destroyed) return
        val generation = styleRequestGeneration
        // Errors from an older request must not replace a newer language style.
        mapView.post {
            if (!destroyed && generation == styleRequestGeneration) loadStyleUrl(null)
        }
    }

    private fun loadStyleUrl(url: String?) {
        val mapLibreMap = map ?: return
        activeBffStyleUrl = url
        MapLibre.setConnected(url != null)
        val builder = url?.let { Style.Builder().fromUri(it) } ?: Style.Builder().fromJson(LOCAL_STYLE_JSON)
        loadStyle(mapLibreMap, builder)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        mapView.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.removeOnDidFailLoadingMapListener(styleFailureListener)
        mapView.removeOnTileActionListener(tileListener)
        mapView.removeOnGlyphsErrorListener(glyphErrorListener)
        map = null
        activeBffStyleUrl = null
        style = null
        styleRequestGeneration++
        acceptedStyleGeneration = styleRequestGeneration
    }

    private fun loadStyle(mapLibreMap: MapLibreMap, builder: Style.Builder) {
        val generation = ++styleRequestGeneration
        style = null
        onStyleReset()
        mapLibreMap.setStyle(builder) styleLoaded@{ loadedStyle ->
            if (destroyed || generation != styleRequestGeneration) return@styleLoaded
            acceptStyle(loadedStyle)
        }
    }

    private fun acceptStyle(loadedStyle: Style) {
        if (destroyed || acceptedStyleGeneration == styleRequestGeneration) return
        acceptedStyleGeneration = styleRequestGeneration
        style = loadedStyle
        onStyleAccepted(loadedStyle)
    }
}

/** A deliberately asset-free, local-only MapLibre style. */
private const val LOCAL_STYLE_JSON = """
    {
      "version": 8,
      "name": "Geo Transit local fallback",
      "sources": {},
      "layers": [
        {
          "id": "gt-local-background",
          "type": "background",
          "paint": { "background-color": "#E7F1EB" }
        }
      ]
    }
"""
