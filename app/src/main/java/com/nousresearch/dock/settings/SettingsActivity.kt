package com.nousresearch.dock.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
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

        private val pickWidgetLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && pendingWidgetSlot != -1) {
                    val manager = WidgetHostManager.getInstance(requireContext())
                    if (manager.bindAppWidget(pendingWidgetSlot, pendingWidgetId, result.data)) {
                        // Bound directly
                    } else {
                        // Need ACTION_REQUEST_BIND_APPWIDGET
                        pendingWidgetProvider = result.data?.getParcelableExtra<ComponentName>(
                            AppWidgetManager.EXTRA_APPWIDGET_PROVIDER
                        )
                        if (pendingWidgetProvider != null) {
                            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pendingWidgetProvider as android.os.Parcelable)
                            bindWidgetLauncher.launch(intent)
                        } else {
                            manager.cleanupWidgetId(pendingWidgetId)
                            pendingWidgetSlot = -1
                            pendingWidgetId = -1
                        }
                    }
                } else if (pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).cleanupWidgetId(pendingWidgetId)
                    pendingWidgetSlot = -1
                    pendingWidgetId = -1
                }
            }

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

            // Per-style clock color pickers
            val styleColorPrefs = mapOf(
                "default" to getString(R.string.pref_key_clock_color_normal),
                "bubble" to getString(R.string.pref_key_clock_color_bubble),
                "neon" to getString(R.string.pref_key_clock_color_neon),
                "gradient" to getString(R.string.pref_key_clock_color_gradient),
                "mono" to getString(R.string.pref_key_clock_color_mono),
                "outline" to getString(R.string.pref_key_clock_color_outline)
            )

            // The old clock_color pref is kept for the date/battery fallback, but
            // each style gets its own color that overrides it. We write to both
            // so the old key remains the universal fallback.
            for ((styleKey, prefKey) in styleColorPrefs) {
                findPreference<Preference>(prefKey)?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    // Read from the universal fallback, then override for this style
                    val fallback = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                    val styleHex = prefs.getString(prefKey, fallback) ?: fallback
                    val currentColor = try { Color.parseColor(styleHex) } catch (e: Exception) { Color.parseColor("#c3c2b7") }

                    showFullColorPicker(ctx, prefs, prefKey, getString(R.string.pref_key_clock_color), currentColor)
                    true
                }
            }

            // Bubble digit colors — per-digit picker for H1, H2, :, M1, M2
            val bubbleColorsPref = findPreference<Preference>(getString(R.string.pref_key_bubble_digit_colors))
            val gradientColorsPref = findPreference<Preference>(getString(R.string.pref_key_gradient_colors))

            // Visibility depends on selected clock style
            fun updateStyleDependentPrefs() {
                val style = prefs.getString(getString(R.string.pref_key_clock_style), "default") ?: "default"
                for ((sKey, pKey) in styleColorPrefs) {
                    findPreference<Preference>(pKey)?.isVisible = (sKey == style)
                }
                bubbleColorsPref?.isVisible = (style == "bubble")
                gradientColorsPref?.isVisible = (style == "gradient")
            }
            updateStyleDependentPrefs()

            findPreference<ListPreference>(getString(R.string.pref_key_clock_style))
                ?.setOnPreferenceChangeListener { _, _ ->
                    // Post so the new value is committed before we check it
                    updateStyleDependentPrefs()
                    true
                }

            bubbleColorsPref?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val dp = ctx.resources.displayMetrics.density
                val padding = (16 * dp).toInt()

                val labels = arrayOf("H1", "H2", ":", "M1", "M2")
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val raw = prefs.getString(getString(R.string.pref_key_bubble_digit_colors), null)
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
                    val label = android.widget.TextView(ctx).apply {
                        text = labels[i]
                        textSize = 16f
                        setTextColor(Color.parseColor("#c3c2b7"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0.3f
                        )
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    row.addView(label)
                    val swatch = View(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (40 * dp).toInt(), (40 * dp).toInt()
                        ).also { it.setMargins(0, 0, 0, 0) }
                        setBackgroundColor(colors[i])
                        tag = i
                    }
                    swatch.setOnClickListener { v ->
                        val index = v.tag as Int
                        showSimpleColorPicker(ctx, colors[index]) { newColor ->
                            colors[index] = newColor
                            v.setBackgroundColor(newColor)
                        }
                    }
                    row.addView(swatch)
                    root.addView(row)
                }

                AlertDialog.Builder(ctx)
                    .setTitle("Bubble digit colors")
                    .setView(root)
                    .setPositiveButton("OK") { _, _ ->
                        val hexStr = colors.joinToString(",") { String.format("#%06X", it and 0xFFFFFF) }
                        prefs.edit().putString(getString(R.string.pref_key_bubble_digit_colors), hexStr).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
                true
            }

            // Gradient colors — two swatches (start, end)
            gradientColorsPref?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val dp = ctx.resources.displayMetrics.density
                val padding = (16 * dp).toInt()

                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val defaultHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                val raw = prefs.getString(getString(R.string.pref_key_gradient_colors), null)
                val colors = if (raw != null) {
                    try { raw.split(",").map { Color.parseColor(it.trim()) }.toMutableList() } catch (_: Exception) { mutableListOf() }
                } else mutableListOf()
                while (colors.size < 2) colors.add(Color.parseColor(defaultHex))

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, 0)
                }

                val labels = arrayOf("Start", "End")
                for (i in 0..1) {
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
                        setBackgroundColor(colors[i])
                        tag = i
                    }
                    swatch.setOnClickListener { v ->
                        val idx = v.tag as Int
                        showSimpleColorPicker(ctx, colors[idx]) { newColor ->
                            colors[idx] = newColor
                            v.setBackgroundColor(newColor)
                        }
                    }
                    row.addView(swatch)
                    root.addView(row)
                }

                // Preview gradient
                val preview = View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (60 * dp).toInt()
                    ).also { it.setMargins(0, padding, 0, 0) }
                    setBackgroundColor(colors[0])
                }
                root.addView(preview, root.childCount)

                AlertDialog.Builder(ctx)
                    .setTitle("Gradient colors")
                    .setView(root)
                    .setPositiveButton("OK") { _, _ ->
                        val hexStr = colors.joinToString(",") { String.format("#%06X", it and 0xFFFFFF) }
                        prefs.edit().putString(getString(R.string.pref_key_gradient_colors), hexStr).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
                true
            }

            // Font upload picker
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

        private fun launchWidgetPicker(slotIndex: Int) {
            val manager = WidgetHostManager.getInstance(requireContext())
            pendingWidgetId = manager.allocateWidgetIdForSlot(slotIndex)
            if (pendingWidgetId == -1) return

            pendingWidgetSlot = slotIndex
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            pickWidgetLauncher.launch(intent)
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

        /** Full RGB + hex color picker dialog that writes to [prefKey] and [fallbackKey]. */
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
                    prefs.edit().putString(prefKey, hex).putString(fallbackKey, hex).apply()
                }
                .setNegativeButton("Cancel", null)
                .show()
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