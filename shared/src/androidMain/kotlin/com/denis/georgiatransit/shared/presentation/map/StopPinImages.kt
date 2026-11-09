package com.denis.georgiatransit.shared.presentation.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.DisplayMetrics
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer

internal const val STOP_PIN_PROPERTY = "stopPin"
private const val ImagePixelRatio = 3

internal fun stopPinLayer(sourceId: String, scale: Float = 1f): SymbolLayer =
    SymbolLayer("$sourceId-pins", sourceId).withProperties(
        iconImage(Expression.get(STOP_PIN_PROPERTY)),
        iconAnchor("bottom"),
        iconSize(scale),
        iconAllowOverlap(true),
        iconIgnorePlacement(true),
    )

internal class StopPinImages {
    private val imageIds = mutableSetOf<String>()

    fun update(style: Style, stops: List<MapStopMarker>) {
        val pins = stops.asSequence().map { it.routeHighlight.pinArtwork }.associateBy(StopPinArtwork::imageId)
        (imageIds - pins.keys).forEach(style::removeImage)
        pins.forEach { (id, pin) ->
            if (id !in imageIds) style.addImage(id, pin.toBitmap())
        }
        imageIds.clear()
        imageIds.addAll(pins.keys)
    }

    fun clear() = imageIds.clear()
}

private fun StopPinArtwork.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width * ImagePixelRatio, height * ImagePixelRatio, Bitmap.Config.ARGB_8888)
    bitmap.density = ImagePixelRatio * DisplayMetrics.DENSITY_DEFAULT
    val canvas = Canvas(bitmap)
    canvas.scale(ImagePixelRatio.toFloat(), ImagePixelRatio.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (badges.isNotEmpty()) drawRouteCard(canvas, paint)
    canvas.translate(width / 2f, bodyTop.toFloat())
    drawPinBody(canvas, paint)
    return bitmap
}

private fun StopPinArtwork.drawRouteCard(canvas: Canvas, paint: Paint) {
    val center = width / 2f
    paint.color = Color.WHITE
    canvas.drawRect(center - 2, cardHeight - 1f, center + 2, bodyTop + 1f, paint)
    canvas.drawRoundRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat(), 7f, 7f, paint)

    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    badges.forEachIndexed { index, badge ->
        val x = badgeX(index).toFloat()
        val y = badgeY(index).toFloat()
        paint.color = badge.backgroundArgb.toInt()
        canvas.drawRoundRect(x, y, x + badgeWidth, y + 15, 5f, 5f, paint)

        paint.color = badge.textArgb.toInt()
        paint.textSize = 9f
        val labelWidth = paint.measureText(badge.label)
        if (labelWidth > badgeWidth - 4f) paint.textSize *= (badgeWidth - 4f) / labelWidth
        canvas.drawText(badge.label, x + badgeWidth / 2f, y + 7.5f - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}

private fun StopPinArtwork.drawPinBody(canvas: Canvas, paint: Paint) {
    paint.color = Color.WHITE
    val tip = Path().apply {
        moveTo(-5f, 20f)
        lineTo(5f, 20f)
        lineTo(0f, 28f)
        close()
    }
    canvas.drawPath(tip, paint)
    canvas.drawRoundRect(-12f, 0f, 12f, 23f, 7f, 7f, paint)
    paint.color = backgroundArgb.toInt()
    canvas.drawRoundRect(-10f, 2f, 10f, 21f, 5f, 5f, paint)

    paint.color = glyphArgb.toInt()
    canvas.drawRoundRect(-6f, 5f, 6f, 16f, 2f, 2f, paint)
    canvas.drawRoundRect(-5f, 15f, -2f, 18f, 1f, 1f, paint)
    canvas.drawRoundRect(2f, 15f, 5f, 18f, 1f, 1f, paint)
    paint.color = backgroundArgb.toInt()
    canvas.drawRoundRect(-4.5f, 6.5f, 4.5f, 11f, 1f, 1f, paint)
    canvas.drawCircle(-3.5f, 13.5f, 0.8f, paint)
    canvas.drawCircle(3.5f, 13.5f, 0.8f, paint)
}
