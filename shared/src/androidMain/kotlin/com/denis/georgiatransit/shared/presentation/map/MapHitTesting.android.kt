package com.denis.georgiatransit.shared.presentation.map

import android.graphics.RectF
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.model.VehicleId
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** Visible stop pins win over vehicles; vehicles get a finger-sized target around the tap. */
internal fun MapLibreMap.hitTestEntity(point: LatLng, density: Float): MapPlatformEvent? {
    val screenPoint = projection.toScreenLocation(point)
    val pinHitRect = RectF(screenPoint.x - 0.5f, screenPoint.y - 0.5f, screenPoint.x + 0.5f, screenPoint.y + 0.5f)
    val visibleStop = findEntityFeature(pinHitRect, stopPinLayerId(SELECTED_STOP_SOURCE_ID), FEATURE_KIND_STOP)
        ?: findEntityFeature(pinHitRect, stopPinLayerId(STOPS_SOURCE_ID), FEATURE_KIND_STOP)
    if (visibleStop != null) {
        return MapPlatformEvent.StopTapped(StopId(visibleStop.first), visibleStop.second)
    }
    val halfTarget = MIN_VEHICLE_TARGET_DP / 2f * density
    val hitRect = RectF(
        screenPoint.x - halfTarget,
        screenPoint.y - halfTarget,
        screenPoint.x + halfTarget,
        screenPoint.y + halfTarget,
    )
    return findEntityFeature(hitRect, VEHICLES_LAYER_ID, FEATURE_KIND_VEHICLE)?.let { feature ->
        MapPlatformEvent.VehicleTapped(VehicleId(feature.first), feature.second)
    }
}

private fun MapLibreMap.findEntityFeature(
    hitRect: RectF,
    layerId: String,
    expectedKind: String,
): Pair<String, Long>? = queryRenderedFeatures(hitRect, layerId)
    .asSequence()
    .filter { it.getStringProperty(FEATURE_KIND_PROPERTY) == expectedKind }
    .mapNotNull { feature ->
        val id = feature.getStringProperty(FEATURE_ID_PROPERTY)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val revision = feature.getStringProperty(SOURCE_REVISION_PROPERTY)?.toLongOrNull() ?: return@mapNotNull null
        id to revision
    }
    .firstOrNull()

private const val MIN_VEHICLE_TARGET_DP = 48f
