package com.nousresearch.dock.dream.faces

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale

/** Very large heavy numerals filling the screen, with a small date line above. */
class DigitalFaceView(context: Context) : BaseFaceView(context) {

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = roundedTypeface(context)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = roundedTypeface(context)
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    override fun nextDelayMs(nowMs: Long): Long = ClockMath.msUntilNextMinute(nowMs)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cal = now()
        val time = ClockMath.formatTime(
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour
        )

        // Fit the numerals to the screen, measured at a reference size.
        timePaint.textSize = REFERENCE_SIZE
        val refWidth = timePaint.measureText(time)
        timePaint.getTextBounds("0", 0, 1, bounds)
        val refDigitHeight = bounds.height().toFloat()
        val scale = minOf(
            MAX_WIDTH_FRACTION * width / refWidth,
            MAX_HEIGHT_FRACTION * height / refDigitHeight
        )
        timePaint.textSize = REFERENCE_SIZE * scale
        val digitHeight = refDigitHeight * scale

        datePaint.textSize = digitHeight * DATE_SIZE_FRACTION
        val gap = datePaint.textSize * 0.7f

        // Centre the date + numerals block vertically.
        val blockHeight = datePaint.textSize + gap + digitHeight
        val top = (height - blockHeight) / 2f
        val dateBaseline = top + datePaint.textSize
        val timeBaseline = top + blockHeight

        timePaint.color = accentColor
        datePaint.color = accentColor
        datePaint.alpha = DATE_ALPHA

        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")
        canvas.drawText(DateFormat.format(pattern, cal).toString(), width / 2f, dateBaseline, datePaint)
        canvas.drawText(time, width / 2f, timeBaseline, timePaint)
    }

    private companion object {
        const val REFERENCE_SIZE = 100f
        const val MAX_WIDTH_FRACTION = 0.85f
        const val MAX_HEIGHT_FRACTION = 0.58f
        const val DATE_SIZE_FRACTION = 0.13f
        const val DATE_ALPHA = 150
    }
}
