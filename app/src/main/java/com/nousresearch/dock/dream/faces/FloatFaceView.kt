package com.nousresearch.dock.dream.faces

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Oversized numerals in soft colours, each drifting slowly on its own path. */
class FloatFaceView(context: Context) : BaseFaceView(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = roundedTypeface(context)
        textAlign = Paint.Align.LEFT
    }
    private val bounds = Rect()
    private val hsv = FloatArray(3)

    // The phone charges all night: redraw far less often once the room is dark.
    override fun nextDelayMs(nowMs: Long): Long = if (nightMode) NIGHT_FRAME_MS else FRAME_MS

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cal = now()
        val time = ClockMath.formatTime(
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour
        )

        paint.textSize = REFERENCE_SIZE
        val refWidth = paint.measureText(time)
        paint.getTextBounds("0", 0, 1, bounds)
        val refDigitHeight = bounds.height().toFloat()
        val scale = minOf(
            MAX_WIDTH_FRACTION * width / refWidth,
            MAX_HEIGHT_FRACTION * height / refDigitHeight
        )
        paint.textSize = REFERENCE_SIZE * scale

        Color.colorToHSV(accentColor, hsv)
        val baseHue = hsv[0]
        val amplitude = DRIFT_DP * density
        // Double: a Float loses sub-second precision after days of uptime.
        val t = SystemClock.uptimeMillis() / 1000.0

        var x = (width - refWidth * scale) / 2f
        val baseline = (height + refDigitHeight * scale) / 2f
        for (i in time.indices) {
            val ch = time.substring(i, i + 1)
            hsv[0] = (baseHue + i * HUE_STEP) % 360f
            hsv[1] = SATURATION
            hsv[2] = 1f
            paint.color = Color.HSVToColor(hsv)
            val dx = sin(t * 0.5 + i * 1.7).toFloat() * amplitude
            val dy = cos(t * 0.4 + i * 2.3).toFloat() * amplitude
            canvas.drawText(ch, x + dx, baseline + dy, paint)
            x += paint.measureText(ch)
        }
    }

    private companion object {
        const val REFERENCE_SIZE = 100f
        const val MAX_WIDTH_FRACTION = 0.82f
        const val MAX_HEIGHT_FRACTION = 0.6f
        const val DRIFT_DP = 6f
        const val HUE_STEP = 28f
        const val SATURATION = 0.55f
        const val FRAME_MS = 66L
        const val NIGHT_FRAME_MS = 250L
    }
}
