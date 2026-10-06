package com.nousresearch.dock.dream.faces

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A dial sized to the screen with hour ticks and hour, minute and second hands. */
class AnalogFaceView(context: Context) : BaseFaceView(context) {

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    override fun nextDelayMs(nowMs: Long): Long = ClockMath.msUntilNextSecond(nowMs)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * RADIUS_FRACTION

        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            tickPaint.strokeWidth = radius * if (isHour) 0.022f else 0.008f
            tickPaint.alpha = if (isHour) 255 else 110
            val inner = radius * if (isHour) 0.86f else 0.93f
            drawRadial(canvas, cx, cy, i * 6f, inner, radius, tickPaint)
        }

        val cal = now()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        handPaint.strokeWidth = radius * 0.05f
        drawRadial(canvas, cx, cy, ClockMath.hourAngle(hour, minute, second), 0f, radius * 0.5f, handPaint)
        handPaint.strokeWidth = radius * 0.035f
        drawRadial(canvas, cx, cy, ClockMath.minuteAngle(minute, second), 0f, radius * 0.8f, handPaint)

        secondPaint.color = accentColor
        secondPaint.style = Paint.Style.STROKE
        secondPaint.strokeWidth = radius * 0.012f
        drawRadial(canvas, cx, cy, ClockMath.secondAngle(second), -radius * 0.12f, radius * 0.88f, secondPaint)
        secondPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.035f, secondPaint)
    }

    /** Draws a line along [angleDeg] (clockwise from 12) between two distances from the centre. */
    private fun drawRadial(
        canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, from: Float, to: Float, paint: Paint
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        canvas.drawLine(cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to, paint)
    }

    private companion object {
        const val RADIUS_FRACTION = 0.44f
    }
}
