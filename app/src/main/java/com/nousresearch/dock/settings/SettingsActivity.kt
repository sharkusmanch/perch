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
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import android.widget.Toast
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.DockDreamService
import com.nousresearch.dock.dream.DreamPrefs
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager

/**
 * Dock settings activity.
 *
 * - Display: settings theme, OLED background, Night Mode.
 * - Clock: 24-hour format and one colour per clock face.
 * - Photos: picker using the system picker (no broad storage permission) and interval.
 * - Widgets: slot count + per-slot widget picker via AppWidgetHost.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() so the correct day/night resources
        // (see values/themes.xml + values-night/themes.xml) are already
        // active when this activity's window/theme is resolved.
        applyPersistedNightMode(this)
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, DockSettingsFragment())
            .commit()
    }

    companion object {
        /** Maps the persisted "App theme" pref (system/light/dark) to an AppCompatDelegate night mode. */
        fun nightModeFor(themePref: String): Int = when (themePref) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        fun applyPersistedNightMode(ctx: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val themePref = prefs.getString(ctx.getString(R.string.pref_key_app_theme), "system") ?: "system"
            AppCompatDelegate.setDefaultNightMode(nightModeFor(themePref))
        }
    }

    class DockSettingsFragment : PreferenceFragmentCompat() {

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

            // --- App theme (Settings app UI only; the dream stays dark) ---
            findPreference<ListPreference>(getString(R.string.pref_key_app_theme))
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val themePref = newValue as? String ?: "system"
                    AppCompatDelegate.setDefaultNightMode(SettingsActivity.nightModeFor(themePref))
                    activity?.recreate()
                    true
                }

            // --- Pick photos preference ---
            val pickPhotosPref = findPreference<Preference>("pick_photos")
            pickPhotosPref?.setOnPreferenceClickListener {
                launchPhotoPicker()
                true
            }
            updatePickPhotosSummaryFromPrefs(prefs)

            // --- Slideshow interval ---
            val intervalPref = findPreference<ListPreference>(getString(R.string.pref_key_slideshow_interval))
            intervalPref?.setOnPreferenceChangeListener { _, newValue ->
                val intervalMillis = (newValue as String).toLongOrNull() ?: 60000L
                PhotoSlideshowManager.getInstance(context).setInterval(intervalMillis)
                true
            }

            // ===== Widgets =====
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

            // ===== Night Mode =====
            // Night Mode follows the light sensor; without one it can never switch on.
            findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_night_mode))?.let { pref ->
                val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                if (sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) == null) {
                    pref.isEnabled = false
                    pref.summary = getString(R.string.pref_night_mode_no_sensor)
                }
            }

            // ===== Clock face colours =====
            val faceColors = listOf(
                Triple(R.string.pref_key_face_color_digital, DreamPrefs.DEFAULT_COLOR_DIGITAL, R.string.pref_face_color_digital_title),
                Triple(R.string.pref_key_face_color_analog, DreamPrefs.DEFAULT_COLOR_ANALOG, R.string.pref_face_color_analog_title),
                Triple(R.string.pref_key_face_color_float, DreamPrefs.DEFAULT_COLOR_FLOAT, R.string.pref_face_color_float_title)
            )
            for ((keyRes, defaultHex, titleRes) in faceColors) {
                val key = getString(keyRes)
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val p = PreferenceManager.getDefaultSharedPreferences(ctx)
                    showFullColorPicker(ctx, p, key, getString(titleRes), DreamPrefs.color(p, key, defaultHex))
                    true
                }
            }

            // Auto-start guide — open system dream settings + ADB commands
            findPreference<Preference>("auto_start_guide")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val appName = getString(R.string.app_name)
                val component = ComponentName(ctx, DockDreamService::class.java).flattenToString()
                val commands = """
                    |adb shell settings put secure screensaver_enabled 1
                    |adb shell settings put secure screensaver_components $component
                    |adb shell settings put secure screensaver_activate_on_dock 1
                """.trimMargin()
                val message = buildString {
                    appendLine("Enable $appName as your screen saver:")
                    appendLine()
                    appendLine("1. Tap \"Open Settings\" below and select $appName.")
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

        /** Full RGB + hex color picker dialog. Writes the chosen color to [prefKey] as "#RRGGBB". */
        private fun showFullColorPicker(
            ctx: Context, prefs: SharedPreferences,
            prefKey: String, title: String, initial: Int
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
                .setTitle(title)
                .setView(root)
                .setPositiveButton("OK") { _, _ ->
                    val hex = try {
                        val rv = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                        val gv = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                        val bv = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                        String.format("#%02X%02X%02X", rv, gv, bv)
                    } catch (e: Exception) { "#c3c2b7" }
                    prefs.edit().putString(prefKey, hex).apply()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

    }
}
