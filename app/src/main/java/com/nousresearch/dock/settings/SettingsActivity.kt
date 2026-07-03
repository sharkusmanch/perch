package com.nousresearch.dock.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import android.widget.Toast
import android.graphics.Color
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.dock.R
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager

/**
 * Dock settings activity.
 *
 * Phase 2: Toggle switches (slideshow_enabled, widgets_enabled) wired to SharedPreferences.
 * Phase 3: Photo picker for slideshow using system picker (no broad storage permission).
 * Phase 4: Widget slot management - slot count + per-slot widget picker via AppWidgetHost.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, DockSettingsFragment())
            .commit()
    }

    class DockSettingsFragment : PreferenceFragmentCompat() {

        // Preference keys whose change should repaint the live clock preview.
        private val previewRefreshKeys: Set<String> by lazy {
            setOf(
                getString(R.string.pref_key_clock_style),
                getString(R.string.pref_key_clock_24h),
                getString(R.string.pref_key_clock_color_normal),
                getString(R.string.pref_key_clock_color_bubble),
                getString(R.string.pref_key_clock_color_neon),
                getString(R.string.pref_key_clock_color_gradient),
                getString(R.string.pref_key_clock_color_mono),
                getString(R.string.pref_key_clock_color_outline),
                getString(R.string.pref_key_clock_color),
                getString(R.string.pref_key_clock_font),
                getString(R.string.pref_key_clock_font),
                getString(R.string.pref_key_clock_font_file),
                getString(R.string.pref_key_oled_mode),
                getString(R.string.pref_key_night_dim)
            )
        }

        private val prefChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key in previewRefreshKeys) {
                    findPreference<ClockStylePreviewPreference>("clock_style_preview")?.refresh()
                }
            }

        // Photo picker launcher (OpenMultipleDocuments for persistable permissions)
        private val pickPhotosLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    persistPhotoUris(uris)
                    updatePickPhotosSummary(uris.size)
                }
            }

        // Widget picker launcher
        private var pendingWidgetSlot = -1
        private var pendingWidgetId = -1
        private var pendingWidgetProvider: ComponentName? = null

        private val pickFontLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val context = requireContext()
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val fontFile = java.io.File(context.filesDir, "custom_font.ttf")
                        inputStream?.use { input ->
                            fontFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putString(getString(R.string.pref_key_clock_font), "custom")
                            .putString(getString(R.string.pref_key_clock_font_file), fontFile.absolutePath)
                            .apply()
                        val fontPref = findPreference<Preference>("clock_font_upload")
                        fontPref?.summary = "Custom font loaded"
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

        private val bindWidgetLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).finalizeWidgetBinding(
                        pendingWidgetSlot, pendingWidgetId, pendingWidgetProvider
                    )
                } else if (pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).cleanupWidgetId(pendingWidgetId)
                }
                pendingWidgetSlot = -1
                pendingWidgetId = -1
                pendingWidgetProvider = null
            }

        // Ensures at least 10dp of breathing room between every preference row
        // (name/icon + summary) and the next, most noticeably in the Widgets
        // section where users pick/manage widgets per slot.
        override fun onCreateRecyclerView(
            inflater: android.view.LayoutInflater,
            parent: android.view.ViewGroup,
            savedInstanceState: Bundle?
        ): RecyclerView {
            val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            val minGapPx = (10 * resources.displayMetrics.density).toInt()
            recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    outRect.bottom = minGapPx
                }
            })
            return recyclerView
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_preferences, rootKey)

            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val context = requireContext()

            // --- Pick photos preference ---
            val pickPhotosPref = findPreference<Preference>("pick_photos")
            pickPhotosPref?.setOnPreferenceClickListener {
                launchPhotoPicker()
                true
            }
            updatePickPhotosSummaryFromPrefs(prefs)

            // --- Slideshow enabled toggle ---
            val slideshowEnabledPref = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_slideshow_enabled))
            slideshowEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                pickPhotosPref?.isEnabled = enabled
                PhotoSlideshowManager.getInstance(context).setEnabled(enabled)
                true
            }
            pickPhotosPref?.isEnabled = prefs.getBoolean(getString(R.string.pref_key_slideshow_enabled), true)

            // --- Slideshow interval ---
            val intervalPref = findPreference<ListPreference>(getString(R.string.pref_key_slideshow_interval))
            intervalPref?.setOnPreferenceChangeListener { _, newValue ->
                val intervalMillis = (newValue as String).toLongOrNull() ?: 60000L
                PhotoSlideshowManager.getInstance(context).setInterval(intervalMillis)
                true
            }

            // ===== Widgets =====
            // Widgets enabled toggle
            val widgetsEnabledPref = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_widgets_enabled))
            widgetsEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateWidgetPrefsVisibility(enabled)
                WidgetHostManager.getInstance(context).setEnabled(enabled)
                true
            }
            updateWidgetPrefsVisibility(prefs.getBoolean(getString(R.string.pref_key_widgets_enabled), true))

            // Widget slot count
            val slotCountPref = findPreference<ListPreference>(getString(R.string.pref_key_widget_slot_count))
            slotCountPref?.setOnPreferenceChangeListener { _, newValue ->
                val count = (newValue as String).toIntOrNull() ?: 1
                WidgetHostManager.getInstance(context).setSlotCount(count)
                updateSlotManageVisibility(count)
                true
            }
            val currentSlotCount = prefs.getString(getString(R.string.pref_key_widget_slot_count), "1")?.toIntOrNull() ?: 1
            updateSlotManageVisibility(currentSlotCount)

            // Per-slot widget pickers
            for (i in 1..3) {
                val key = "manage_slot_$i"
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    launchWidgetPicker(i - 1)
                    true
                }
            }

            // Per-slot size
            for (i in 1..3) {
                val key = "slot_size_$i"
                findPreference<ListPreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    WidgetHostManager.getInstance(context).notifySlotSizeChanged()
                    true
                }
            }

            // Widget rail height in portrait
            findPreference<ListPreference>(getString(R.string.pref_key_widget_rail_height))
                ?.setOnPreferenceChangeListener { _, _ ->
                    WidgetHostManager.getInstance(context).notifySlotSizeChanged()
                    true
                }

            // Auto-start guide — open system dream settings + ADB commands
            findPreference<Preference>("auto_start_guide")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val commands = """
                    |adb shell settings put secure screensaver_enabled 1
                    |adb shell settings put secure screensaver_components com.dock.app/.dream.DockDreamService
                    |adb shell settings put secure screensaver_activate_on_dock 1
                """.trimMargin()
                val message = buildString {
                    appendLine("Enable Dock as your screen saver:")
                    appendLine()
                    appendLine("1. Tap \"Open Settings\" below and select Dock.")
                    appendLine("2. Set \"When to start\" → \"While charging\".")
                    appendLine("3. Grant Unrestricted battery.")
                    appendLine("4. Disable Battery Saver when testing.")
                    appendLine()
                    appendLine("If the dream doesn't auto-start, run these ADB commands (one-time):")
                    appendLine()
                    append(commands)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Auto-start setup")
                    .setMessage(message)
                    .setPositiveButton("Copy commands") { _, _ ->
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", commands))
                    }
                    .setNeutralButton("Open Settings") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Close", null)
                    .show()
                true
            }

            // Per-style color pickers — each style has its own visible pref.
            val normalColorPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_normal))
            val bubbleColorsPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_bubble))
            val neonColorPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_neon))
            val gradientColorsPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_gradient))
            val monoColorPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_mono))
            val outlineColorPref = findPreference<Preference>(getString(R.string.pref_key_clock_color_outline))
            val dateColorPref = findPreference<Preference>(getString(R.string.pref_key_date_color))
            val batteryColorPref = findPreference<Preference>(getString(R.string.pref_key_battery_color))

            fun updateStyleDependentPrefs() {
                val style = prefs.getString(getString(R.string.pref_key_clock_style), "default") ?: "default"
                normalColorPref?.isVisible = (style == "default")
                bubbleColorsPref?.isVisible = (style == "bubble")
                neonColorPref?.isVisible = (style == "neon")
                gradientColorsPref?.isVisible = (style == "gradient")
                monoColorPref?.isVisible = (style == "mono")
                outlineColorPref?.isVisible = (style == "outline")
            }
            updateStyleDependentPrefs()

            findPreference<ListPreference>(getString(R.string.pref_key_clock_style))
                ?.setOnPreferenceChangeListener { _, _ ->
                    updateStyleDependentPrefs()
                    true
                }

            // Normal / Neon / Mono / Outline — standard single-color picker with hex input.
            val styleKeys = listOf(
                getString(R.string.pref_key_clock_color_normal) to normalColorPref,
                getString(R.string.pref_key_clock_color_neon) to neonColorPref,
                getString(R.string.pref_key_clock_color_mono) to monoColorPref,
                getString(R.string.pref_key_clock_color_outline) to outlineColorPref,
                getString(R.string.pref_key_date_color) to dateColorPref,
                getString(R.string.pref_key_battery_color) to batteryColorPref
            )
            for ((key, pref) in styleKeys) {
                pref?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val p = PreferenceManager.getDefaultSharedPreferences(ctx)
                    val fallback = p.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                    val curHex = p.getString(key, fallback) ?: fallback
                    val curColor = try { Color.parseColor(curHex) } catch (e: Exception) { Color.parseColor("#c3c2b7") }
                    showFullColorPicker(ctx, p, key, getString(R.string.pref_key_clock_color), curColor)
                    true
                }
            }

            // Bubble — per-digit color picker for H1, H2, :, M1, M2 with hex input per swatch
            bubbleColorsPref?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val dp = ctx.resources.displayMetrics.density
                val padding = (16 * dp).toInt()

                val labels = arrayOf("H1", "H2", ":", "M1", "M2")
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val raw = prefs.getString(getString(R.string.pref_key_clock_color_bubble), null)
                val defaultHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                val colors = if (raw != null) {
                    try { raw.split(",").map { Color.parseColor(it.trim()) }.toMutableList() } catch (_: Exception) { mutableListOf() }
                } else mutableListOf()
                while (colors.size < 5) colors.add(Color.parseColor(defaultHex))

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, 0)
                }

                for (i in 0..4) {
                    val row = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (48 * dp).toInt()
                        ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                    }
                    row.addView(android.widget.TextView(ctx).apply {
                        text = labels[i]; textSize = 16f
                        setTextColor(Color.parseColor("#c3c2b7"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0.3f
                        )
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    })
                    val swatch = View(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (40 * dp).toInt(), (40 * dp).toInt()
                        )
                        setBackgroundColor(colors[i]); tag = i
                    }
                    swatch.setOnClickListener { v ->
                        val idx = v.tag as Int
                        showSimpleColorPicker(ctx, colors[idx]) { newColor ->
                            colors[idx] = newColor
                            v.setBackgroundColor(newColor)
                        }
                    }
                    row.addView(swatch); root.addView(row)
                }

                AlertDialog.Builder(ctx)
                    .setTitle("Bubble digit colors")
                    .setView(root)
                    .setPositiveButton("OK") { _, _ ->
                        val hexStr = colors.joinToString(",") { String.format("#%06X", it and 0xFFFFFF) }
                        prefs.edit().putString(getString(R.string.pref_key_clock_color_bubble), hexStr).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
                true
            }

            // Gradient — multi-color picker (add/remove colors, live preview)
            gradientColorsPref?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val dp = ctx.resources.displayMetrics.density
                val padding = (16 * dp).toInt()

                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val defaultHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                val raw = prefs.getString(getString(R.string.pref_key_clock_color_gradient), null)
                val colors = if (raw != null) {
                    try { raw.split(",").map { Color.parseColor(it.trim()) }.toMutableList() } catch (_: Exception) { mutableListOf() }
                } else mutableListOf()
                if (colors.isEmpty()) { colors.add(Color.parseColor(defaultHex)); colors.add(lighten(Color.parseColor(defaultHex), 0.6f)) }

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, 0)
                }

                // Preview gradient bar
                val preview = View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (60 * dp).toInt()
                    ).also { it.setMargins(0, 0, 0, padding) }
                }
                root.addView(preview)

                // Color list
                val colorsContainer = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }
                root.addView(colorsContainer)

                fun refreshGradientPreview() {
                    if (colors.size >= 2) {
                        val shader = android.graphics.LinearGradient(
                            0f, 0f, (300 * dp).toFloat(), 0f,
                            colors.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP
                        )
                        val bg = android.graphics.drawable.PaintDrawable().apply { paint.shader = shader }
                        preview.background = bg
                    } else if (colors.size == 1) {
                        preview.setBackgroundColor(colors[0])
                    }
                }

                fun rebuildColorList() {
                    colorsContainer.removeAllViews()
                    for (i in colors.indices) {
                        val row = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                (48 * dp).toInt()
                            ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                        }
                        val swatch = View(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                (40 * dp).toInt(), (40 * dp).toInt()
                            )
                            setBackgroundColor(colors[i]); tag = i
                        }
                        swatch.setOnClickListener { v ->
                            val idx = v.tag as Int
                            showSimpleColorPicker(ctx, colors[idx]) { newColor ->
                                colors[idx] = newColor
                                v.setBackgroundColor(newColor)
                                refreshGradientPreview()
                            }
                        }
                        row.addView(swatch)
                        row.addView(android.widget.TextView(ctx).apply {
                            text = "Color ${i + 1}"
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            ).also { it.setMargins((12 * dp).toInt(), 0, 0, 0) }
                            textSize = 14f; gravity = android.view.Gravity.CENTER_VERTICAL
                        })
                        if (colors.size > 2) {
                            val removeBtn = android.widget.Button(ctx).apply {
                                text = "X"
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    (48 * dp).toInt(), android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                                )
                                setOnClickListener { colors.removeAt(i); rebuildColorList(); refreshGradientPreview() }
                            }
                            row.addView(removeBtn)
                        }
                        colorsContainer.addView(row)
                    }
                    // Add color button
                    val addRow = android.widget.LinearLayout(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (48 * dp).toInt()
                        ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                    }
                    val addBtn = android.widget.Button(ctx).apply {
                        text = "+ Add color"
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        setOnClickListener {
                            colors.add(Color.parseColor(defaultHex))
                            rebuildColorList()
                            refreshGradientPreview()
                        }
                    }
                    addRow.addView(addBtn)
                    colorsContainer.addView(addRow)
                }

                rebuildColorList()
                refreshGradientPreview()

                AlertDialog.Builder(ctx)
                    .setTitle("Gradient colors")
                    .setView(root)
                    .setPositiveButton("OK") { _, _ ->
                        val hexStr = colors.joinToString(",") { String.format("#%06X", it and 0xFFFFFF) }
                        prefs.edit().putString(getString(R.string.pref_key_clock_color_gradient), hexStr).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
                true
            }            // Font upload picker
            findPreference<Preference>("clock_font_upload")?.setOnPreferenceClickListener {
                pickFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype"))
                true
            }

            // About — open GitHub repo
            findPreference<Preference>("about_license")?.setOnPreferenceClickListener {
                try {
                    val url = "https://${getString(R.string.about_repo)}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    requireActivity().startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(prefChangeListener)
            findPreference<ClockStylePreviewPreference>("clock_style_preview")?.startPreview()
        }

        override fun onPause() {
            super.onPause()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
            findPreference<ClockStylePreviewPreference>("clock_style_preview")?.stopPreview()
        }

        /**
         * In-app widget chooser. Replaces the system ACTION_APPWIDGET_PICK dialog
         * (whose row layout/spacing we can't control) with our own list, styled
         * to match the rest of Settings and with a guaranteed minimum 10dp gap
         * between each widget row (icon + name).
         */
        private fun launchWidgetPicker(slotIndex: Int) {
            val ctx = requireContext()
            val appWidgetManager = AppWidgetManager.getInstance(ctx)
            val pm = ctx.packageManager
            val providers = try {
                appWidgetManager.installedProviders.sortedBy {
                    it.loadLabel(pm).lowercase()
                }
            } catch (e: Exception) {
                emptyList<AppWidgetProviderInfo>()
            }

            val dp = ctx.resources.displayMetrics.density
            val rowGap = (10 * dp).toInt()
            showWidgetPickerDialog(ctx, slotIndex, providers, rowGap)
        }

        private fun showWidgetPickerDialog(
            ctx: Context,
            slotIndex: Int,
            providers: List<AppWidgetProviderInfo>,
            rowGap: Int
        ) {
            val pm = ctx.packageManager
            val dp = ctx.resources.displayMetrics.density
            val outerPadding = (16 * dp).toInt()
            val rowPadding = (12 * dp).toInt()
            val iconSize = (40 * dp).toInt()

            val list = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            }
            val scroll = android.widget.ScrollView(ctx).apply { addView(list) }

            if (providers.isEmpty()) {
                list.addView(android.widget.TextView(ctx).apply {
                    text = getString(R.string.widget_picker_empty)
                    setTextColor(Color.parseColor("#c3c2b7"))
                    textSize = 14f
                })
            }

            lateinit var dialog: AlertDialog

            for ((index, info) in providers.withIndex()) {
                val label = try { info.loadLabel(pm) } catch (e: Exception) { info.provider.flattenToShortString() }
                val icon: android.graphics.drawable.Drawable? = try {
                    info.loadIcon(ctx, ctx.resources.displayMetrics.densityDpi)
                } catch (e: Exception) {
                    try { pm.getApplicationIcon(info.provider.packageName) } catch (e2: Exception) { null }
                }

                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(outValue.resourceId)
                    setPadding(rowPadding, rowPadding, rowPadding, rowPadding)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { lp ->
                        // Guarantee at least 10dp of space before the next row.
                        if (index < providers.size - 1) lp.setMargins(0, 0, 0, rowGap)
                    }
                }

                row.addView(android.widget.ImageView(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
                    if (icon != null) setImageDrawable(icon)
                })

                row.addView(android.widget.TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor("#c3c2b7"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.setMargins((16 * dp).toInt(), 0, 0, 0) }
                })

                row.setOnClickListener {
                    dialog.dismiss()
                    bindWidgetFromCustomPicker(slotIndex, info)
                }

                list.addView(row)
            }

            dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.widget_picker_title))
                .setView(scroll)
                .setNegativeButton(getString(android.R.string.cancel), null)
                .create()
            dialog.show()
        }

        /** Binds the chosen widget provider to [slotIndex], requesting the ACTION_APPWIDGET_BIND permission if needed. */
        private fun bindWidgetFromCustomPicker(slotIndex: Int, info: AppWidgetProviderInfo) {
            val ctx = requireContext()
            val manager = WidgetHostManager.getInstance(ctx)
            val appWidgetId = manager.allocateWidgetIdForSlot(slotIndex)
            if (appWidgetId == -1) return

            val appWidgetManager = AppWidgetManager.getInstance(ctx)
            val bound = try {
                appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)
            } catch (e: Exception) {
                false
            }

            if (bound) {
                manager.finalizeWidgetBinding(slotIndex, appWidgetId, info.provider)
            } else {
                pendingWidgetSlot = slotIndex
                pendingWidgetId = appWidgetId
                pendingWidgetProvider = info.provider
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider as android.os.Parcelable)
                bindWidgetLauncher.launch(intent)
            }
        }

        private fun updateWidgetPrefsVisibility(enabled: Boolean) {
            val keys = listOf(
                getString(R.string.pref_key_widget_slot_count),
                getString(R.string.pref_key_widget_rail_height),
                getString(R.string.pref_key_widget_show_portrait),
                getString(R.string.pref_key_widget_show_landscape),
                "manage_slot_1", "slot_size_1",
                "manage_slot_2", "slot_size_2",
                "manage_slot_3", "slot_size_3"
            )
            for (key in keys) {
                val pref = findPreference<Preference>(key)
                if (pref != null) pref.isEnabled = enabled
            }
        }

        private fun updateSlotManageVisibility(count: Int) {
            for (i in 1..3) {
                val pref = findPreference<Preference>("manage_slot_$i")
                pref?.isVisible = (i <= count)
                val sizePref = findPreference<Preference>("slot_size_$i")
                sizePref?.isVisible = (i <= count)
            }
            findPreference<androidx.preference.PreferenceCategory>("spacer_after_slot1")?.isVisible = (count >= 2)
            findPreference<androidx.preference.PreferenceCategory>("spacer_after_slot2")?.isVisible = (count >= 3)
        }

        private fun launchPhotoPicker() {
            pickPhotosLauncher.launch(arrayOf("image/*"))
        }

        private fun persistPhotoUris(uris: List<Uri>) {
            val context = requireContext()
            val contentResolver: ContentResolver = context.contentResolver

            // Take persistable URI permissions
            for (uri in uris) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            // Store as pipe-separated string in SharedPreferences
            val uriString = uris.map { it.toString() }.joinToString("|")
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString("slideshow_photo_uris", uriString)
                .apply()

            // Notify slideshow manager
            PhotoSlideshowManager.getInstance(context).setPhotoUris(uris)
        }

        private fun updatePickPhotosSummary(count: Int) {
            val pickPhotosPref = findPreference<Preference>("pick_photos")
            pickPhotosPref?.summary = if (count > 0) {
                getString(R.string.slideshow_photos_selected, count)
            } else {
                getString(R.string.slideshow_no_photos)
            }
        }

        private fun updatePickPhotosSummaryFromPrefs(prefs: SharedPreferences) {
            val uriString = prefs.getString("slideshow_photo_uris", "") ?: ""
            val count = if (uriString.isNotEmpty()) uriString.split("|").size else 0
            updatePickPhotosSummary(count)
        }

        /**
         * Full RGB + hex color picker dialog. Writes the chosen color only to
         * [prefKey] (the style-specific pref). [fallbackKey] is used solely as a
         * read-time default when [prefKey] has never been set, and is never
         * overwritten here.
         */
        private fun showFullColorPicker(
            ctx: Context, prefs: SharedPreferences,
            prefKey: String, fallbackKey: String, initial: Int
        ) {
            val dp = ctx.resources.displayMetrics.density
            val padding = (16 * dp).toInt()
            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padding, padding, padding, 0)
            }

            val preview = View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (80 * dp).toInt()
                ).also { it.setMargins(0, 0, 0, padding) }
                setBackgroundColor(initial)
            }
            root.addView(preview)

            val hexRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 0) }
            }
            hexRow.addView(android.widget.TextView(ctx).apply {
                text = "#"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (24 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val hexInput = android.widget.EditText(ctx).apply {
                setText(String.format("%06X", initial and 0xFFFFFF))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {}
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val hex = s?.toString()?.trim()?.removePrefix("#") ?: return
                        if (hex.length != 6) return
                        val color = try { Color.parseColor("#$hex") } catch (e: Exception) { return }
                        root.findViewWithTag<android.widget.SeekBar>("R")?.progress = Color.red(color)
                        root.findViewWithTag<android.widget.SeekBar>("G")?.progress = Color.green(color)
                        root.findViewWithTag<android.widget.SeekBar>("B")?.progress = Color.blue(color)
                        preview.setBackgroundColor(color)
                    }
                })
            }
            hexRow.addView(hexInput)
            root.addView(hexRow)

            fun addSlider(label: String, initial: Int, tag: String): android.widget.SeekBar {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                }
                row.addView(android.widget.TextView(ctx).apply {
                    text = label
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (40 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    textSize = 14f
                })
                val seekBar = android.widget.SeekBar(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    max = 255; progress = initial
                }
                row.addView(seekBar)
                val valueView = android.widget.TextView(ctx).apply {
                    text = initial.toString()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (40 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.END
                    textSize = 14f
                }
                row.addView(valueView)
                root.addView(row)
                seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        valueView.text = progress.toString()
                        val r = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                        val g = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                        val b = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                        preview.setBackgroundColor(Color.rgb(r, g, b))
                        if (fromUser) hexInput.setText(String.format("%02X%02X%02X", r, g, b))
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
                seekBar.tag = tag
                return seekBar
            }

            addSlider("R", Color.red(initial), "R")
            addSlider("G", Color.green(initial), "G")
            addSlider("B", Color.blue(initial), "B")

            AlertDialog.Builder(ctx)
                .setTitle("Clock color")
                .setView(root)
                .setPositiveButton("OK") { _, _ ->
                    val hex = try {
                        val rv = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                        val gv = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                        val bv = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                        String.format("#%02X%02X%02X", rv, gv, bv)
                    } catch (e: Exception) { "#c3c2b7" }
                    // Only write the style-specific key. Writing to [fallbackKey] (the
                    // shared "clock_color" pref) here would leak this style's color into
                    // every other style's default, making switching styles look like it
                    // "remembers" the wrong color.
                    prefs.edit().putString(prefKey, hex).apply()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        /** Lightens [color] toward white by [amount] (0..1). Used to derive a default second gradient stop. */
        private fun lighten(color: Int, amount: Float): Int {
            val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
            val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
            val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        /** Compact color picker dialog with RGB sliders + hex input, using a callback. */
        private fun showSimpleColorPicker(
            ctx: Context, initial: Int, onPicked: (Int) -> Unit
        ) {
            val dp = ctx.resources.displayMetrics.density
            val padding = (16 * dp).toInt()
            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padding, padding, padding, 0)
            }

            val preview = View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (60 * dp).toInt()
                ).also { it.setMargins(0, 0, 0, padding) }
                setBackgroundColor(initial)
            }
            root.addView(preview)

            // Hex input row
            val hexRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, padding) }
            }
            hexRow.addView(android.widget.TextView(ctx).apply {
                text = "#"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (24 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val hexInput = android.widget.EditText(ctx).apply {
                setText(String.format("%06X", initial and 0xFFFFFF))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {}
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val hex = s?.toString()?.trim()?.removePrefix("#") ?: return
                        if (hex.length != 6) return
                        val color = try { Color.parseColor("#$hex") } catch (e: Exception) { return }
                        root.findViewWithTag<android.widget.SeekBar>("R")?.progress = Color.red(color)
                        root.findViewWithTag<android.widget.SeekBar>("G")?.progress = Color.green(color)
                        root.findViewWithTag<android.widget.SeekBar>("B")?.progress = Color.blue(color)
                        preview.setBackgroundColor(color)
                    }
                })
            }
            hexRow.addView(hexInput)
            root.addView(hexRow)

            fun addSlider(label: String, initial: Int, tag: String): android.widget.SeekBar {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                }
                row.addView(android.widget.TextView(ctx).apply {
                    text = label; layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ); textSize = 14f
                })
                val seek = android.widget.SeekBar(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    max = 255; progress = initial
                }
                row.addView(seek)
                val valueView = android.widget.TextView(ctx).apply {
                    text = initial.toString(); layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ); gravity = android.view.Gravity.END; textSize = 14f
                }
                row.addView(valueView)
                root.addView(row)
                seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        valueView.text = p.toString()
                        val rv = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                        val gv = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                        val bv = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                        preview.setBackgroundColor(Color.rgb(rv, gv, bv))
                        if (fromUser) hexInput.setText(String.format("%02X%02X%02X", rv, gv, bv))
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
                seek.tag = tag
                return seek
            }

            addSlider("R", Color.red(initial), "R")
            addSlider("G", Color.green(initial), "G")
            addSlider("B", Color.blue(initial), "B")

            AlertDialog.Builder(ctx)
                .setTitle("Pick color")
                .setView(root)
                .setPositiveButton("OK") { _, _ ->
                    val rv = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                    val gv = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                    val bv = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                    onPicked(Color.rgb(rv, gv, bv))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
