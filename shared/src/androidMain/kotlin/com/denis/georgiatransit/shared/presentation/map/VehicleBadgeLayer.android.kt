package com.denis.georgiatransit.shared.presentation.map

import android.content.res.Resources
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 * Vehicle animation replaces only the grouped GeoJSON source; bitmap badge styles are cached
 * separately and keyed by their exact render input.
 */
internal class VehicleBadgeLayer(private val resources: Resources) {
    private val badgeImageIds = linkedMapOf<VehicleBadgeStyle, String>()
    private var nextBadgeImageIndex = 0L
    private var overflowBadgeImagesInstalled = false
    private var lastVehicleSourceRevision: Long? = null
    private var lastVehicleBadgeRevision: Long? = null
    private var vehicleLabelCameraBearing: Double? = null

    fun layer(cameraBearing: Double): SymbolLayer = SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
        iconImage(vehicleBadgeImage(cameraBearing)),
        iconSize(VEHICLE_ICON_SCALE),
        iconOpacity(Expression.get(VEHICLE_OPACITY_PROPERTY)),
        iconRotate(Expression.get(BEARING_PROPERTY)),
        iconRotationAlignment("map"),
        iconAllowOverlap(true),
        iconIgnorePlacement(true),
    )

    fun render(loadedStyle: Style, renderState: MapRenderState) {
        if (lastVehicleSourceRevision == renderState.vehicleSourceRevision) return
        if (lastVehicleBadgeRevision != renderState.vehicleBadgeRevision) {
            updateBadgeImages(loadedStyle, renderState.vehicles.badgeRenderInput())
            lastVehicleBadgeRevision = renderState.vehicleBadgeRevision
        }
        loadedStyle.setSourceFeatures(
            VEHICLES_SOURCE_ID,
            vehicleFeatures(renderState.vehicles, renderState.vehicleSourceRevision),
        )
        lastVehicleSourceRevision = renderState.vehicleSourceRevision
    }

    /** Labels flip on the upside-down half of the circle so route numbers stay readable. */
    fun updateLabelOrientation(loadedStyle: Style?, cameraBearing: Double?) {
        val bearing = cameraBearing ?: return
        if (bearing == vehicleLabelCameraBearing) return
        val layer = loadedStyle?.getLayerAs<SymbolLayer>(VEHICLES_LAYER_ID) ?: return
        layer.setProperties(iconImage(vehicleBadgeImage(bearing)))
        vehicleLabelCameraBearing = bearing
    }

    fun clear() {
        lastVehicleSourceRevision = null
        lastVehicleBadgeRevision = null
        badgeImageIds.clear()
        nextBadgeImageIndex = 0L
        overflowBadgeImagesInstalled = false
        vehicleLabelCameraBearing = null
    }

    private fun vehicleFeatures(
        vehicles: List<MapVehicleMarker>,
        sourceRevision: Long,
    ): FeatureCollection = FeatureCollection.fromFeatures(
        vehicles.asSequence()
            .filter(MapVehicleMarker::isRenderable)
            .sortedBy { it.id.value }
            .take(MAX_VEHICLE_MARKERS)
            .map { marker ->
                Feature.fromGeometry(marker.position.asMapPoint()).also { feature ->
                    feature.addStringProperty(FEATURE_ID_PROPERTY, marker.id.value)
                    feature.addStringProperty(FEATURE_KIND_PROPERTY, FEATURE_KIND_VEHICLE)
                    feature.addStringProperty(SOURCE_REVISION_PROPERTY, sourceRevision.toString())
                    feature.addStringProperty(VEHICLE_BADGE_IMAGE_PROPERTY, badgeImageIds[marker.badgeStyle()] ?: OVERFLOW_BADGE_IMAGE_ID)
                    feature.addNumberProperty(VEHICLE_OPACITY_PROPERTY, if (marker.isStale) STALE_VEHICLE_OPACITY else 1.0)
                    feature.addStringProperty(POSITION_KIND_PROPERTY, marker.positionKind.name)
                    feature.addNumberProperty(
                        BEARING_PROPERTY,
                        marker.bearingDegrees?.takeIf(Double::isFinite)
                            ?.let { (it - EASTWARD_ARTWORK_BEARING).normalizedBearing() } ?: DEFAULT_VEHICLE_ROTATION,
                    )
                }
            }
            .toList(),
    )

    private fun updateBadgeImages(style: Style, renderInput: VehicleBadgeRenderInput) {
        val styles = renderInput.styles
        val obsolete = badgeImageIds.keys.filter { it !in styles }
        obsolete.forEach { badgeStyle ->
            val imageId = checkNotNull(badgeImageIds.remove(badgeStyle))
            style.removeImage(imageId)
            style.removeImage(imageId + FLIPPED_VEHICLE_LABEL_SUFFIX)
        }
        styles.forEach { badgeStyle ->
            if (badgeStyle !in badgeImageIds) {
                val imageId = "gt-vehicle-badge-${++nextBadgeImageIndex}"
                addVehicleBadgeImages(style, imageId, badgeStyle)
                badgeImageIds[badgeStyle] = imageId
            }
        }
        if (renderInput.needsOverflow && !overflowBadgeImagesInstalled) {
            val overflow = VehicleBadgeStyle("?", 0xFF455A64, 0xFFFFFFFF, false)
            addVehicleBadgeImages(style, OVERFLOW_BADGE_IMAGE_ID, overflow)
            overflowBadgeImagesInstalled = true
        }
    }

    private fun addVehicleBadgeImages(style: Style, imageId: String, badge: VehicleBadgeStyle) {
        val metrics = resources.displayMetrics
        style.addImage(imageId, badge.toBitmap(metrics, flipLabel = false))
        style.addImage(imageId + FLIPPED_VEHICLE_LABEL_SUFFIX, badge.toBitmap(metrics, flipLabel = true))
    }
}

internal fun MapVehicleMarker.isRenderable(): Boolean =
    id.value.isNotBlank() && routeId.value.isNotBlank() && position.isMapCoordinate()

/** Exact badge inputs are independent from geometry so animation frames never rebuild bitmaps. */
private data class VehicleBadgeRenderInput(
    val styles: Set<VehicleBadgeStyle>,
    val needsOverflow: Boolean,
)

private fun List<MapVehicleMarker>.badgeRenderInput(): VehicleBadgeRenderInput {
    val allStyles = asSequence()
        .filter(MapVehicleMarker::isRenderable)
        .map(MapVehicleMarker::badgeStyle)
        .distinct()
        .sortedWith(compareBy<VehicleBadgeStyle> { it.label }.thenBy { it.backgroundArgb }.thenBy { it.textArgb }.thenBy { it.stale })
        .toList()
    return VehicleBadgeRenderInput(
        styles = allStyles.take(MAX_VEHICLE_BADGE_IMAGES).toSet(),
        needsOverflow = allStyles.size > MAX_VEHICLE_BADGE_IMAGES,
    )
}

private fun MapVehicleMarker.badgeStyle(): VehicleBadgeStyle = VehicleBadgeStyle(
    label = routeLabel.asNativeBadgeLabel(),
    backgroundArgb = routeColorArgb or 0xFF000000L,
    textArgb = routeTextColorArgb or 0xFF000000L,
    stale = isStale,
)

private fun String.asNativeBadgeLabel(): String = asSequence()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
    .joinToString(separator = "")
    .trim()
    .take(MAX_BADGE_LABEL_LENGTH)
    .ifBlank { "?" }

private fun vehicleBadgeImage(cameraBearing: Double): Expression {
    val image = Expression.get(VEHICLE_BADGE_IMAGE_PROPERTY)
    val screenRotation = Expression.mod(
        Expression.sum(
            Expression.toNumber(Expression.get(BEARING_PROPERTY)),
            Expression.literal(360.0 - cameraBearing.normalizedBearing()),
        ),
        Expression.literal(360.0),
    )
    return Expression.switchCase(
        Expression.all(Expression.gt(screenRotation, 90), Expression.lt(screenRotation, 270)),
        Expression.concat(image, Expression.literal(FLIPPED_VEHICLE_LABEL_SUFFIX)),
        image,
    )
}

private const val BEARING_PROPERTY = "bearing"
private const val POSITION_KIND_PROPERTY = "positionKind"
private const val VEHICLE_BADGE_IMAGE_PROPERTY = "vehicleBadgeImage"
private const val VEHICLE_OPACITY_PROPERTY = "vehicleOpacity"
private const val MAX_VEHICLE_MARKERS = 2_000
private const val MAX_VEHICLE_BADGE_IMAGES = 256
private const val FLIPPED_VEHICLE_LABEL_SUFFIX = "-flipped-label"
private const val OVERFLOW_BADGE_IMAGE_ID = "gt-vehicle-badge-overflow"
private const val MAX_BADGE_LABEL_LENGTH = 8
private const val STALE_VEHICLE_OPACITY = 0.62
private const val VEHICLE_ICON_SCALE = 1f / 1.5f
private const val EASTWARD_ARTWORK_BEARING = 90.0
private const val DEFAULT_VEHICLE_ROTATION = 0.0
