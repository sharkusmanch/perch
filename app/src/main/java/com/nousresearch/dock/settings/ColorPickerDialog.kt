package com.nousresearch.dock.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nousresearch.dock.R

/** A grid of preset colors plus a hex field for anything else. */
object ColorPickerDialog {

    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFC3C2B7.toInt(), 0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(),
        0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF66D4CF.toInt(), 0xFF64D2FF.toInt(),
        0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF6482.toInt()
    )
    private const val COLUMNS = 6

    /** Shows the picker; [onPicked] receives the chosen opaque color. */
    fun show(context: Context, title: CharSequence?, initial: Int, onPicked: (Int) -> Unit) {
        val dp = context.resources.displayMetrics.density
        var selected = initial or 0xFF000000.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (16 * dp).toInt(), pad, 0)
        }

        val ringColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        val outlineColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline)
        val swatches = ArrayList<View>()

        fun styleSwatches() {
            swatches.forEachIndexed { i, swatch ->
                val isSelected = PRESETS[i] == selected
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PRESETS[i])
                    setStroke(
                        ((if (isSelected) 3 else 1) * dp).toInt(),
                        if (isSelected) ringColor else outlineColor
                    )
                }
                swatch.isSelected = isSelected
            }
        }

        val hexInput = TextInputEditText(context).apply {
            setText(hex(selected))
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }

        // Rows of evenly spaced swatches.
        val size = (40 * dp).toInt()
        PRESETS.toList().chunked(COLUMNS).forEach { rowColors ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (color in rowColors) {
                val swatch = View(context).apply {
                    contentDescription = "#${hex(color)}"
                    setOnClickListener { hexInput.setText(hex(color)) }
                }
                swatches += swatch
                // Each swatch sits centred in an equal share of the row.
                val cell = FrameLayout(context).apply {
                    addView(swatch, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, size, 1f))
            }
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() })
        }

        val hexLayout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = context.getString(R.string.color_picker_custom)
            prefixText = "#"
            addView(hexInput)
        }
        root.addView(hexLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() })

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val parsed = parse(s?.toString().orEmpty())
                if (parsed != null) selected = parsed
                // An unfinished or invalid hex cannot be saved.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = parsed != null
                styleSwatches()
            }
        })

        styleSwatches()
        dialog.show()
    }

    private fun hex(color: Int): String = String.format("%06X", color and 0xFFFFFF)

    private fun parse(text: String): Int? =
        if (text.length == 6) try { Color.parseColor("#$text") } catch (_: IllegalArgumentException) { null }
        else null
}
