package com.denis.georgiatransit.shared.presentation.map

import androidx.lifecycle.Lifecycle
import org.maplibre.android.maps.MapView

internal class MapViewLifecycle(
    private val mapView: MapView,
    private val onResumedChanged: (Boolean) -> Unit,
) {
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun syncTo(state: Lifecycle.State) {
        if (state == Lifecycle.State.DESTROYED) {
            destroy()
            return
        }
        if (state.isAtLeast(Lifecycle.State.STARTED)) start()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        pause()
        stop()
        mapView.onDestroy()
        onResumedChanged(false)
        destroyed = true
    }

    private fun start() {
        if (!destroyed && !started) {
            mapView.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!destroyed && !resumed) {
            start()
            mapView.onResume()
            resumed = true
            onResumedChanged(true)
        }
    }

    private fun pause() {
        if (!destroyed && resumed) {
            mapView.onPause()
            resumed = false
            onResumedChanged(false)
        }
    }

    private fun stop() {
        if (!destroyed && started) {
            pause()
            mapView.onStop()
            started = false
        }
    }
}
