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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.DockDreamService
import com.nousresearch.dock.dream.DreamPrefs
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager

/**
 * Dock settings activity — a Material 3 screen of grouped cards.
 *
 * - Display: Night Mode, OLED background, battery level.
 * - Clock: live face previews, 24-hour format and one colour per clock face.
 * - Photos: picker using the system picker (no broad storage permission) and interval.
 * - Widgets: slot count + per-slot widget picker via AppWidgetHost.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Wallpaper-based colors on Android 12+; must be applied before the theme is used.
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, DockSettingsFragment())
                .commit()
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
                updateSlotRows(currentSlotCount())
            }

        // The face previews show the colors and time format, so follow those settings.
        private val previewRefreshKeys: Set<String> by lazy {
            setOf(
                getString(R.string.pref_key_clock_24h),
                getString(R.string.pref_key_face_color_digital),
                getString(R.string.pref_key_face_color_analog),
                getString(R.string.pref_key_face_color_float)
            )
        }

        private val prefChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key in previewRefreshKeys) {
                    findPreference<FacePreviewPreference>("face_preview")?.refresh()
                }
            }

        override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
            CardPreferenceAdapter(preferenceScreen)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // Cards separate the rows; no divider lines.
            setDivider(null)

            // Edge to edge: keep the last card clear of the navigation bar and cutouts.
            val bottomGap = (24 * resources.displayMetrics.density).toInt()
            listView.clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                list.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom + bottomGap)
                insets
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_preferences, rootKey)

            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val context = requireContext()

            // --- Preview / select as screen saver ---
            findPreference<Preference>("open_dream_settings")?.setOnPreferenceClickListener {
                openDreamSettings()
                true
            }

            // --- Pick photos preference ---
            findPreference<Preference>("pick_photos")?.setOnPreferenceClickListener {
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
                updateSlotRows(count)
                true
            }
            updateSlotRows(currentSlotCount())

            // Per-slot widget pickers
            for (i in 1..3) {
                findPreference<Preference>("manage_slot_$i")?.setOnPreferenceClickListener {
                    onSlotClicked(i - 1)
                    true
                }
            }

            // Per-slot size
            for (i in 1..3) {
                findPreference<ListPreference>("slot_size_$i")?.apply {
                    title = getString(R.string.widget_slot_size_title, i)
                    // Earlier versions saved an empty value meaning "medium".
                    if (value.isNullOrEmpty()) value = "medium"
                    setOnPreferenceChangeListener { _, _ ->
                        // The new value is persisted only after this returns.
                        view?.post { WidgetHostManager.getInstance(context).notifySlotSizeChanged() }
                        true
                    }
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

            // Auto-start guide — steps plus the ADB commands for when they are not enough
            findPreference<Preference>("auto_start_guide")?.setOnPreferenceClickListener {
                showAutoStartGuide()
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
            // A widget's app may have been uninstalled while we were away.
            updateSlotRows(currentSlotCount())
        }

        override fun onPause() {
            super.onPause()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        }

        /** Material single-choice dialogs for list settings instead of the framework's older style. */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference !is ListPreference) {
                super.onDisplayPreferenceDialog(preference)
                return
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.title)
                .setSingleChoiceItems(
                    preference.entries, preference.findIndexOfValue(preference.value)
                ) { dialog, which ->
                    val value = preference.entryValues[which].toString()
                    if (preference.callChangeListener(value)) preference.value = value
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun openDreamSettings() {
            try {
                startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            } catch (_: Exception) {}
        }

        private fun showAutoStartGuide() {
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
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Auto-start setup")
                .setMessage(message)
                .setPositiveButton("Copy commands") { _, _ ->
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", commands))
                }
                .setNeutralButton("Open Settings") { _, _ -> openDreamSettings() }
                .setNegativeButton("Close", null)
                .show()
        }

        // ------------------------------------------------------------------
        // Widgets
        // ------------------------------------------------------------------

        private fun currentSlotCount(): Int =
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(getString(R.string.pref_key_widget_slot_count), "1")?.toIntOrNull() ?: 1

        /** An empty slot goes straight to the picker; a filled one offers change or remove. */
        private fun onSlotClicked(slotIndex: Int) {
            val ctx = requireContext()
            if (WidgetHostManager.getInstance(ctx).slotProvider(slotIndex) == null) {
                launchWidgetPicker(slotIndex)
                return
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.widget_slot_title, slotIndex + 1))
                .setItems(
                    arrayOf(getString(R.string.widget_change), getString(R.string.widget_remove))
                ) { _, which ->
                    if (which == 0) {
                        launchWidgetPicker(slotIndex)
                    } else {
                        WidgetHostManager.getInstance(ctx).removeWidget(slotIndex)
                        updateSlotRows(currentSlotCount())
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * In-app widget chooser. Replaces the system ACTION_APPWIDGET_PICK dialog
         * (whose row layout/spacing we can't control) with our own list, styled
         * to match the rest of Settings.
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
            showWidgetPickerDialog(ctx, slotIndex, providers)
        }

        private fun showWidgetPickerDialog(
            ctx: Context,
            slotIndex: Int,
            providers: List<AppWidgetProviderInfo>
        ) {
            val pm = ctx.packageManager
            val dp = ctx.resources.displayMetrics.density
            val outerPadding = (12 * dp).toInt()
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

            for (info in providers) {
                val label = try { info.loadLabel(pm) } catch (e: Exception) { info.provider.flattenToShortString() }
                val icon = loadProviderIcon(ctx, info)

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
                    )
                }

                row.addView(android.widget.ImageView(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
                    if (icon != null) setImageDrawable(icon)
                })

                row.addView(android.widget.TextView(ctx).apply {
                    text = label
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
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

            dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.widget_picker_title))
                .setView(scroll)
                .setNegativeButton(getString(android.R.string.cancel), null)
                .create()
            dialog.show()
        }

        private fun loadProviderIcon(ctx: Context, info: AppWidgetProviderInfo): android.graphics.drawable.Drawable? =
            try {
                info.loadIcon(ctx, ctx.resources.displayMetrics.densityDpi)
            } catch (e: Exception) {
                try { ctx.packageManager.getApplicationIcon(info.provider.packageName) } catch (e2: Exception) { null }
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
                updateSlotRows(currentSlotCount())
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

        /** Shows the rows for [count] slots, each with its widget's name and icon. */
        private fun updateSlotRows(count: Int) {
            val ctx = context ?: return
            val manager = WidgetHostManager.getInstance(ctx)
            val installed = try {
                AppWidgetManager.getInstance(ctx).installedProviders
            } catch (e: Exception) {
                emptyList<AppWidgetProviderInfo>()
            }

            for (i in 1..3) {
                findPreference<Preference>("slot_size_$i")?.isVisible = (i <= count)
                val row = findPreference<Preference>("manage_slot_$i") ?: continue
                row.isVisible = (i <= count)
                row.title = getString(R.string.widget_slot_title, i)

                val provider = manager.slotProvider(i - 1)
                val info = provider?.let { p -> installed.firstOrNull { it.provider == p } }
                if (info != null) {
                    row.summary = try { info.loadLabel(ctx.packageManager) } catch (e: Exception) { provider.flattenToShortString() }
                    row.icon = loadProviderIcon(ctx, info)
                        ?: AppCompatResources.getDrawable(ctx, R.drawable.ic_widgets)
                } else {
                    row.summary = getString(R.string.widget_pick_for_slot)
                    row.icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_widgets)
                }
            }
        }

        // ------------------------------------------------------------------
        // Photos
        // ------------------------------------------------------------------

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
                .putString(DreamPrefs.KEY_PHOTO_URIS, uriString)
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
            val uriString = prefs.getString(DreamPrefs.KEY_PHOTO_URIS, "") ?: ""
            val count = if (uriString.isNotEmpty()) uriString.split("|").size else 0
            updatePickPhotosSummary(count)
        }
    }
}
