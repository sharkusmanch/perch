package com.nousresearch.dock.settings

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.DreamPrefs
import com.nousresearch.dock.dream.faces.AnalogFaceView
import com.nousresearch.dock.dream.faces.BaseFaceView
import com.nousresearch.dock.dream.faces.DigitalFaceView
import com.nousresearch.dock.dream.faces.FloatFaceView

/** Live miniatures of the three clock faces, drawn with the current colors and time format. */
class FacePreviewPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_face_preview
    }

    /** Redraw with the current settings. */
    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // The row is the layout's root; Preference replaces the root's id, so it cannot be looked up by id.
        val row = holder.itemView as? LinearLayout ?: return
        if (row.childCount == 0) buildTiles(row)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val is24Hour = prefs.getBoolean(context.getString(R.string.pref_key_clock_24h), true)
        for ((keyRes, defaultHex) in COLORS) {
            val face = row.findViewWithTag<BaseFaceView>(keyRes) ?: continue
            face.accentColor = DreamPrefs.color(prefs, context.getString(keyRes), defaultHex)
            face.is24Hour = is24Hour
        }
    }

    private fun buildTiles(row: LinearLayout) {
        val ctx = row.context
        val dp = ctx.resources.displayMetrics.density
        val faces = listOf(
            Triple(DigitalFaceView(ctx), R.string.pref_key_face_color_digital, R.string.face_digital),
            Triple(AnalogFaceView(ctx), R.string.pref_key_face_color_analog, R.string.face_analog),
            Triple(FloatFaceView(ctx), R.string.pref_key_face_color_float, R.string.face_float)
        )
        for ((face, keyRes, labelRes) in faces) {
            face.tag = keyRes
            // Faces stop themselves when detached; start them again whenever shown.
            face.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = face.resume()
                override fun onViewDetachedFromWindow(v: View) = face.pause()
            })

            val tile = FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.face_preview_tile)
                clipToOutline = true
                val pad = (6 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                addView(face, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            val label = TextView(ctx).apply {
                setText(labelRes)
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(tile, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (TILE_HEIGHT_DP * dp).toInt()
                ))
                addView(label, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            row.addView(column, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                val gap = (4 * dp).toInt()
                marginStart = gap
                marginEnd = gap
            })
        }
    }

    private companion object {
        const val TILE_HEIGHT_DP = 64
        val COLORS = listOf(
            R.string.pref_key_face_color_digital to DreamPrefs.DEFAULT_COLOR_DIGITAL,
            R.string.pref_key_face_color_analog to DreamPrefs.DEFAULT_COLOR_ANALOG,
            R.string.pref_key_face_color_float to DreamPrefs.DEFAULT_COLOR_FLOAT
        )
    }
}
