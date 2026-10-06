package com.nousresearch.dock.dream.faces

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.Calendar
import java.util.TimeZone

/** A full-screen clock face shown in the Clock page's vertical carousel. */
interface ClockFace {
    var accentColor: Int
    var is24Hour: Boolean
    var nightMode: Boolean

    /** Start redrawing on the face's own schedule. */
    fun resume()

    /** Stop scheduled redraws; the face keeps showing its last frame. */
    fun pause()
}

/**
 * Base for the faces: reads the time at draw, and while resumed redraws
 * itself whenever [nextDelayMs] says the picture is next due to change.
 */
abstract class BaseFaceView(context: Context) : View(context), ClockFace {

    override var accentColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    override var is24Hour: Boolean = true
        set(value) { field = value; invalidate() }

    override var nightMode: Boolean = false
        set(value) { field = value; invalidate() }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val calendar = Calendar.getInstance()

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, nextDelayMs(System.currentTimeMillis()))
        }
    }

    /** Milliseconds from [nowMs] until this face next needs redrawing. */
    protected abstract fun nextDelayMs(nowMs: Long): Long

    override fun resume() {
        if (running) return
        running = true
        tick.run()
    }

    override fun pause() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pause()
    }

    /** The current wall-clock time in the device's current time zone. */
    protected fun now(): Calendar = calendar.apply {
        timeZone = TimeZone.getDefault()
        timeInMillis = System.currentTimeMillis()
    }

    protected val density: Float get() = resources.displayMetrics.density

    companion object {
        private var rounded: Typeface? = null

        /** Heavy rounded typeface used by the Digital and Float faces. */
        fun roundedTypeface(context: Context): Typeface =
            rounded ?: (try {
                Typeface.Builder(context.assets, "fonts/Nunito.ttf")
                    .setFontVariationSettings("'wght' 900")
                    .build()
            } catch (_: Exception) {
                null
            } ?: Typeface.DEFAULT_BOLD).also { rounded = it }
    }
}
