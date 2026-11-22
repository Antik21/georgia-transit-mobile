package com.denis.georgiatransit.shared.presentation.map

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.maplibre.android.maps.MapView
import kotlin.math.abs

/** Observe a completed double-tap without taking touch ownership from MapLibre. */
internal class DoubleTapMapView(context: Context, onDoubleTap: () -> Unit) : MapView(context) {
    var zoomEnabled: () -> Boolean = { false }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var tapX = 0f
        private var tapY = 0f
        private var moved = false

        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTapEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapX = event.x
                    tapY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> moved = moved || abs(event.x - tapX) > slop || abs(event.y - tapY) > slop
                MotionEvent.ACTION_UP -> if (!moved && zoomEnabled()) onDoubleTap()
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}
