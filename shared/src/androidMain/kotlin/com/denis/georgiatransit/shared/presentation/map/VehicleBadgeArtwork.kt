package com.denis.georgiatransit.shared.presentation.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import kotlin.math.ceil

private const val BODY_HEIGHT_DP = 36f
private const val MIN_BODY_WIDTH_DP = 58f
private const val BODY_RADIUS_DP = 11f
private const val OUTLINE_WIDTH_DP = 2.5f
private const val TEXT_PADDING_DP = 44f
private const val TEXT_SIZE_SP = 17f
private const val WINDOW_RADIUS_DP = 4f
private const val HEADLIGHT_RADIUS_DP = 2.5f
private const val HEADLIGHT_COLOR = 0xFFFFDC7A.toInt()
private const val GLASS_BRIGHTNESS = 0.45f

internal data class VehicleBadgeStyle(
    val label: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val stale: Boolean,
)

internal fun VehicleBadgeStyle.toBitmap(metrics: DisplayMetrics, flipLabel: Boolean): Bitmap {
    val density = metrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, metrics) / density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textWidth = maxOf(textPaint.measureText(label), textPaint.measureText("00"))
    val width = maxOf(MIN_BODY_WIDTH_DP, textWidth + TEXT_PADDING_DP)
    val bitmap = Bitmap.createBitmap(
        ceil((width + OUTLINE_WIDTH_DP) * density).toInt(),
        ceil((BODY_HEIGHT_DP + OUTLINE_WIDTH_DP) * density).toInt(),
        Bitmap.Config.ARGB_8888,
    ).apply { this.density = metrics.densityDpi }
    val canvas = Canvas(bitmap)
    canvas.translate(bitmap.width / 2f, bitmap.height / 2f)
    canvas.scale(density, density)

    val halfHeight = BODY_HEIGHT_DP / 2f
    val body = RectF(-width / 2f, -halfHeight, width / 2f, halfHeight)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb.toInt() }
    canvas.drawRoundRect(body, BODY_RADIUS_DP, BODY_RADIUS_DP, paint)
    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = OUTLINE_WIDTH_DP
    canvas.drawRoundRect(body, BODY_RADIUS_DP, BODY_RADIUS_DP, paint)

    paint.style = Paint.Style.FILL
    drawBusDetails(canvas, body, paint)
    canvas.save()
    if (flipLabel) canvas.rotate(180f)
    canvas.drawText(label, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    canvas.restore()
    if (stale) drawStaleCue(canvas, body, paint)
    return bitmap
}

private fun VehicleBadgeStyle.drawBusDetails(canvas: Canvas, body: RectF, paint: Paint) {
    val routeColor = backgroundArgb.toInt()
    paint.color = Color.rgb(
        (Color.red(routeColor) * GLASS_BRIGHTNESS).toInt(),
        (Color.green(routeColor) * GLASS_BRIGHTNESS).toInt(),
        (Color.blue(routeColor) * GLASS_BRIGHTNESS).toInt(),
    )
    val rearLeft = body.left + 9f
    val frontRight = body.right - 5f
    canvas.drawRoundRect(rearLeft, -11f, rearLeft + 8f, 11f, WINDOW_RADIUS_DP, WINDOW_RADIUS_DP, paint)
    canvas.drawRoundRect(frontRight - 11f, -13f, frontRight, 13f, WINDOW_RADIUS_DP, WINDOW_RADIUS_DP, paint)

    paint.color = HEADLIGHT_COLOR
    val lightX = body.right - 2f
    val lightY = body.bottom - 7f
    canvas.drawCircle(lightX, -lightY, HEADLIGHT_RADIUS_DP, paint)
    canvas.drawCircle(lightX, lightY, HEADLIGHT_RADIUS_DP, paint)
}

private fun VehicleBadgeStyle.drawStaleCue(canvas: Canvas, body: RectF, paint: Paint) {
    // Keep a shape-based stale cue in addition to the layer opacity.
    val clip = Path().apply { addRoundRect(body, BODY_RADIUS_DP, BODY_RADIUS_DP, Path.Direction.CW) }
    paint.color = textArgb.toInt()
    paint.alpha = 180
    paint.strokeWidth = 1.4f
    canvas.save()
    canvas.clipPath(clip)
    canvas.drawLine(body.left, body.bottom - 3f, body.right, body.top + 3f, paint)
    canvas.drawLine(body.left + 8f, body.bottom, body.right, body.top + 11f, paint)
    canvas.restore()
}
