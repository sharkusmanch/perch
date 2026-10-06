package com.nousresearch.dock.dream.pages

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.DreamPrefs
import com.nousresearch.dock.dream.faces.AnalogFaceView
import com.nousresearch.dock.dream.faces.BaseFaceView
import com.nousresearch.dock.dream.faces.DigitalFaceView
import com.nousresearch.dock.dream.faces.FloatFaceView

/** The clock faces, changed by swiping up and down. */
class ClockPage(private val context: Context, private val prefs: SharedPreferences) : DreamPage {

    private val digital = DigitalFaceView(context)
    private val analog = AnalogFaceView(context)
    private val float = FloatFaceView(context)
    private val faces: List<BaseFaceView> = listOf(digital, analog, float)

    private val pager = ViewPager2(context).apply {
        orientation = ViewPager2.ORIENTATION_VERTICAL
        adapter = ViewListAdapter(faces)
        offscreenPageLimit = faces.size - 1
    }

    override val view: View get() = pager

    private var resumed = false

    private val faceCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            prefs.edit().putInt(DreamPrefs.KEY_LAST_FACE, position).apply()
            if (resumed) runOnly(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) refresh()
        }
    }

    override fun attach() {
        digital.accentColor = DreamPrefs.color(
            prefs, context.getString(R.string.pref_key_face_color_digital), DreamPrefs.DEFAULT_COLOR_DIGITAL
        )
        analog.accentColor = DreamPrefs.color(
            prefs, context.getString(R.string.pref_key_face_color_analog), DreamPrefs.DEFAULT_COLOR_ANALOG
        )
        float.accentColor = DreamPrefs.color(
            prefs, context.getString(R.string.pref_key_face_color_float), DreamPrefs.DEFAULT_COLOR_FLOAT
        )
        val is24Hour = prefs.getBoolean(context.getString(R.string.pref_key_clock_24h), true)
        faces.forEach { it.is24Hour = is24Hour }

        val last = prefs.getInt(DreamPrefs.KEY_LAST_FACE, 0).coerceIn(0, faces.size - 1)
        pager.setCurrentItem(last, false)
        pager.registerOnPageChangeCallback(faceCallback)
    }

    override fun detach() {
        pause()
        pager.unregisterOnPageChangeCallback(faceCallback)
    }

    override fun resume() {
        resumed = true
        runOnly(pager.currentItem)
    }

    override fun pause() {
        resumed = false
        faces.forEach { it.pause() }
    }

    override fun refresh() {
        faces.forEach { it.invalidate() }
    }

    override fun setNightMode(on: Boolean) {
        faces.forEach { it.nightMode = on }
    }

    /** Only the visible face keeps redrawing. */
    private fun runOnly(position: Int) {
        faces.forEachIndexed { i, face -> if (i == position) face.resume() else face.pause() }
    }
}
