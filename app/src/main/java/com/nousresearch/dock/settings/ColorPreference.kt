package com.nousresearch.dock.settings

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.nousresearch.dock.R

/** A row that stores a color as "#RRGGBB", shows it as a dot, and opens the color picker when tapped. */
class ColorPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private var defaultHex = "#FFFFFF"

    init {
        widgetLayoutResource = R.layout.preference_widget_color_dot
    }

    private val color: Int
        get() = try {
            Color.parseColor(getPersistedString(defaultHex))
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? = a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        (defaultValue as? String)?.let { defaultHex = it }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val dot = holder.findViewById(R.id.color_dot) ?: return
        (dot.background.mutate() as? GradientDrawable)?.setColor(color)
    }

    override fun onClick() {
        ColorPickerDialog.show(context, title, color) { picked ->
            persistString(String.format("#%06X", picked and 0xFFFFFF))
            notifyChanged()
        }
    }
}
