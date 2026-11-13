package com.denis.georgiatransit.shared.presentation.map

import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

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
    private var loadingRemoteStyle = false
    private var destroyed = false
    private val styleLoadedListener = MapView.OnDidFinishLoadingStyleListener {
        val loadedStyle = map?.style ?: return@OnDidFinishLoadingStyleListener
        val uri = loadedStyle.uri.takeIf(String::isNotBlank)
        if (loadingRemoteStyle && uri != requestedBffStyleUrl) return@OnDidFinishLoadingStyleListener
        if (!loadingRemoteStyle && uri != null) return@OnDidFinishLoadingStyleListener
        acceptStyle(loadedStyle)
    }
    private val styleFailureListener = MapView.OnDidFailLoadingMapListener {
        if (destroyed || !loadingRemoteStyle) return@OnDidFailLoadingMapListener
        // Keep the last requested URL to avoid an update-frame retry loop; a recreated map can
        // attempt it again. Transit overlays are reinstalled on the local style below.
        MapLibre.setConnected(false)
        map?.let { loadStyle(it, Style.Builder().fromJson(LOCAL_STYLE_JSON), remote = false) }
    }

    init {
        mapView.addOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.addOnDidFailLoadingMapListener(styleFailureListener)
    }

    fun attach(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        loadStyle(mapLibreMap, Style.Builder().fromJson(LOCAL_STYLE_JSON), remote = false)
    }

    fun request(bffStyleUrl: String?) {
        if (requestedBffStyleUrl == bffStyleUrl || destroyed) return
        val mapLibreMap = map ?: return
        requestedBffStyleUrl = bffStyleUrl
        // The URL is derived by BffEndpointConfiguration after strict validation. No upstream
        // provider URL is ever accepted by this native adapter.
        MapLibre.setConnected(bffStyleUrl != null)
        val builder = bffStyleUrl?.let { Style.Builder().fromUri(it) } ?: Style.Builder().fromJson(LOCAL_STYLE_JSON)
        loadStyle(mapLibreMap, builder, remote = bffStyleUrl != null)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        mapView.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.removeOnDidFailLoadingMapListener(styleFailureListener)
        map = null
        style = null
        styleRequestGeneration++
        acceptedStyleGeneration = styleRequestGeneration
        loadingRemoteStyle = false
    }

    private fun loadStyle(mapLibreMap: MapLibreMap, builder: Style.Builder, remote: Boolean) {
        val generation = ++styleRequestGeneration
        loadingRemoteStyle = remote
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
        loadingRemoteStyle = false
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
