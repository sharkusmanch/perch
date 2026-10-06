package com.nousresearch.dock.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager
import com.nousresearch.dock.R

/**
 * WidgetHostManager — manages AppWidgetHost with 1–3 slots.
 *
 * Responsibilities:
 * - Create and manage AppWidgetHost + AppWidgetHostView instances
 * - Launch widget picker (ACTION_APPWIDGET_PICK)
 * - Add/remove/reorder slots per user config (1-3 slots)
 * - Style each slot: rounded corners, @color/bg_surface background, no shadows
 * - Lay the slots out across the dream's Widgets page
 * - Release widget host and views on dream stop to avoid leaks
 */
class WidgetHostManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "WidgetHostManager"
        private const val HOST_ID = 0xD0CC // "Dock"
        private const val MAX_SLOTS = 3
        private const val PREFS_NAME = "dock_widget_prefs"
        private const val PREFS_KEY_SLOT_COUNT = "widget_slot_count"
        private const val PREFS_KEY_SLOT_PREFIX = "widget_slot_"

        @Volatile private var INSTANCE: WidgetHostManager? = null

        fun getInstance(context: Context): WidgetHostManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WidgetHostManager(context.applicationContext).also { INSTANCE = it }
            }

        @VisibleForTesting
        fun resetInstance() {
            INSTANCE = null
        }
    }

    // AppWidgetHost
    private val appWidgetHost = LoggingAppWidgetHost(context, HOST_ID)
    private val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)

    // Views
    private var widgetRail: ViewGroup? = null
    private val slotViews = arrayOfNulls<FrameLayout>(MAX_SLOTS)
    private val hostViews = arrayOfNulls<AppWidgetHostView>(MAX_SLOTS)

    // State
    private var slotCount = 1
    private var isStarted = false
    private var currentIsLandscape = false
    private var nightMode = false

    // Themed context for RemoteViews inflation (set by init)
    private var viewContext: Context = context

    // Settings can use the manager before any dream has called init().
    init {
        loadPersistedState()
    }

    /** Initialize with the Widgets page container and the current orientation. */
    fun init(widgetRail: ViewGroup, isLandscape: Boolean) {
        this.widgetRail = widgetRail
        this.viewContext = widgetRail.context.applicationContext
        this.currentIsLandscape = isLandscape
        // Settings may have left host views from binding in this process.
        hostViews.fill(null)
        loadPersistedState()
        createSlotViews()
    }

    /** Start binding all widgets. Must be called after init(). */
    fun start() {
        if (isStarted) return
        isStarted = true
        appWidgetHost.startListening()
        bindAllWidgets()
    }

    /** Stop and release all widget resources. */
    fun stop() {
        if (!isStarted) return
        isStarted = false
        unbindAllWidgets()
        appWidgetHost.stopListening()
    }

    /** Stop and drop every reference to the dream's views. */
    fun release() {
        stop()
        widgetRail?.removeAllViews()
        widgetRail = null
        slotViews.fill(null)
        hostViews.fill(null)
        viewContext = context
        nightMode = false
    }

    /** Whether any slot is showing a widget. Only meaningful after start(). */
    fun hasBoundWidget(): Boolean = (0 until slotCount).any { hostViews[it] != null }

    /** Night Mode needs pure black behind widgets; the usual grey would glow red. */
    fun setNightMode(on: Boolean) {
        nightMode = on
        for (i in 0 until slotCount) {
            slotViews[i]?.let { applySlotBackground(it) }
        }
    }

    /** Allocate a new widget ID for a slot (for picker launch). */
    fun allocateWidgetIdForSlot(slotIndex: Int): Int {
        if (slotIndex !in 0 until slotCount) return -1
        return appWidgetHost.allocateAppWidgetId()
    }

    /** Clean up a widget ID if picker was cancelled. */
    fun cleanupWidgetId(appWidgetId: Int) {
        appWidgetHost.deleteAppWidgetId(appWidgetId)
    }

    /** Set number of slots (1-3). Recreates slot views. */
    fun setSlotCount(count: Int) {
        slotCount = count.coerceIn(1, MAX_SLOTS)
        createSlotViews()
        persistSlotCount()
        if (isStarted) {
            unbindAllWidgets()
            bindAllWidgets()
        }
    }

    /** Call after per-slot size preferences change to rebuild views. */
    fun notifySlotSizeChanged() {
        createSlotViews()
        if (isStarted) {
            unbindAllWidgets()
            bindAllWidgets()
        }
    }

    /** Launch widget picker for a specific slot. */
    fun pickWidgetForSlot(slotIndex: Int, onPicked: (Boolean) -> Unit) {
        if (slotIndex !in 0 until slotCount) {
            onPicked(false)
            return
        }
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Need to start activity from a context that can start activities
        // This will be called from SettingsActivity which is an Activity
        try {
            context.startActivity(intent)
            // The result will come back via onActivityResult in SettingsActivity
            // We'll handle binding there
            onPicked(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch widget picker", e)
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            onPicked(false)
        }
    }

    fun bindAppWidget(slotIndex: Int, appWidgetId: Int, resultData: Intent?): Boolean {
        if (slotIndex !in 0 until slotCount) return false

        appWidgetManager.getAppWidgetInfo(appWidgetId)?.let { info ->
            return finalizeWidgetBinding(slotIndex, appWidgetId, info.provider)
        }

        val provider = resultData?.getParcelableExtra<ComponentName>(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER)
        if (provider == null) {
            Log.w(TAG, "bindAppWidget: no provider info and no provider extra for id=$appWidgetId")
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return false
        }
        if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)) {
            return finalizeWidgetBinding(slotIndex, appWidgetId, provider)
        }
        return false
    }

    /** Complete widget binding after user grants permission via ACTION_APPWIDGET_BIND. */
    fun finalizeWidgetBinding(slotIndex: Int, appWidgetId: Int, provider: ComponentName?): Boolean {
        if (slotIndex !in 0 until slotCount || provider == null) {
            Log.w(TAG, "finalizeWidgetBinding: invalid slot=$slotIndex or provider=$provider")
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return false
        }
        try {
            val info = getWidgetProviderInfo(appWidgetId) ?: run {
                Log.w(TAG, "finalizeWidgetBinding: no info for id=$appWidgetId")
                appWidgetHost.deleteAppWidgetId(appWidgetId)
                return false
            }
            val hostView = appWidgetHost.createView(viewContext, appWidgetId, info)
            hostView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setHostView(slotIndex, hostView)
            persistWidget(slotIndex, provider, appWidgetId)
            Log.d(TAG, "finalizeWidgetBinding: success slot=$slotIndex id=$appWidgetId provider=$provider")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "finalizeWidgetBinding: exception slot=$slotIndex id=$appWidgetId", e)
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return false
        }
    }

    /** The widget provider assigned to a slot, or null if the slot is empty. */
    fun slotProvider(slotIndex: Int): ComponentName? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flattened = prefs.getString("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider", null)
        return flattened?.let(ComponentName::unflattenFromString)
    }

    /** Remove widget from a slot. */
    fun removeWidget(slotIndex: Int) {
        if (slotIndex !in 0 until slotCount) return
        // No host view exists unless a dream is running, so fall back to the saved id.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getInt("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id", -1)
        val appWidgetId = hostViews[slotIndex]?.appWidgetId ?: savedId.takeIf { it != -1 }
        if (appWidgetId != null) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
        setHostView(slotIndex, null)
        clearPersistedWidget(slotIndex)
    }

    // ------------------------------------------------------------------
    // Private implementation
    // ------------------------------------------------------------------

    private fun createSlotViews() {
        widgetRail?.removeAllViews()
        val isLandscape = currentIsLandscape
        val density = context.resources.displayMetrics.density
        val spacingPx = (12 * density).toInt()

        // Side by side in landscape, stacked in portrait.
        (widgetRail as? LinearLayout)?.let {
            it.orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rawWeights = FloatArray(slotCount) { i ->
            when (prefs.getString("slot_size_${i + 1}", null)) {
                "small" -> 0.7f
                "large" -> 1.3f
                else -> 1.0f
            }
        }
        val weightSum = rawWeights.sum()
        val normWeights = rawWeights.map { it * slotCount / weightSum }

        for (i in 0 until slotCount) {
            val lp = if (isLandscape) {
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    normWeights[i]
                )
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    normWeights[i]
                )
            }
            if (i < slotCount - 1) {
                if (isLandscape) lp.marginEnd = spacingPx
                else lp.bottomMargin = spacingPx
            }
            val slotContainer = WidgetSlotLayout(context).apply {
                layoutParams = lp
                clipToOutline = true
            }
            applySlotBackground(slotContainer)
            widgetRail?.addView(slotContainer)
            slotViews[i] = slotContainer
        }
    }

    private fun applySlotBackground(slot: FrameLayout) {
        slot.setBackgroundResource(
            if (nightMode) R.drawable.widget_slot_background_night
            else R.drawable.widget_slot_background
        )
    }

    private fun bindAllWidgets() {
        for (i in 0 until slotCount) {
            bindPersistedWidget(i)
        }
    }

    private fun unbindAllWidgets() {
        for (i in 0 until slotCount) {
            setHostView(i, null)
        }
    }

    private fun bindPersistedWidget(slotIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flattened = prefs.getString("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider", null)
        val appWidgetId = prefs.getInt("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id", -1)
        if (flattened != null && appWidgetId != -1) {
            val provider = ComponentName.unflattenFromString(flattened)
            if (provider != null) {
                Log.d(TAG, "bindPersistedWidget slot=$slotIndex id=$appWidgetId provider=$provider")
                try {
                    val info = getWidgetProviderInfo(appWidgetId) ?: run {
                        Log.w(TAG, "bindPersistedWidget: no info for slot=$slotIndex, clearing")
                        clearPersistedWidget(slotIndex)
                        return
                    }
                    val hostView = appWidgetHost.createView(viewContext, appWidgetId, info)
                    hostView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    Log.d(TAG, "bindPersistedWidget: success slot=$slotIndex id=$appWidgetId")
                    setHostView(slotIndex, hostView)
                } catch (e: Exception) {
                    Log.e(TAG, "bindPersistedWidget: exception slot=$slotIndex id=$appWidgetId", e)
                }
            } else {
                Log.w(TAG, "bindPersistedWidget: null provider for slot=$slotIndex, clearing")
                clearPersistedWidget(slotIndex)
            }
        }
    }

    private fun setHostView(slotIndex: Int, hostView: AppWidgetHostView?) {
        val container = slotViews[slotIndex] ?: run {
            Log.w(TAG, "setHostView: no container for slot=$slotIndex")
            return
        }
        container.removeAllViews()
        hostView?.let { container.addView(it) }
        hostViews[slotIndex] = hostView
        Log.d(TAG, "setHostView slot=$slotIndex view=${hostView != null}")

        // Set app widget sizing after layout — providers use OPTION_APPWIDGET_MIN/MAX_WIDTH/HEIGHT
        // to decide which RemoteViews layout to render. Without real dimensions many render 0x0.
        if (hostView != null) {
            container.post {
                val wPx = container.width
                val hPx = container.height
                if (wPx > 0 && hPx > 0) {
                    val density = container.resources.displayMetrics.density
                    val wDp = (wPx / density).toInt()
                    val hDp = (hPx / density).toInt()
                    val options = Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hostView.updateAppWidgetSize(options, wDp, hDp, wDp, hDp)
                    }
                    appWidgetManager.updateAppWidgetOptions(hostView.appWidgetId, options)
                    Log.d(TAG, "setHostView: sized slot=$slotIndex ${wDp}x${hDp}dp")
                }
            }
        }
    }

    private fun getWidgetProviderInfo(appWidgetId: Int): AppWidgetProviderInfo? {
        return appWidgetManager.getAppWidgetInfo(appWidgetId)
    }

    private fun loadPersistedState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        slotCount = prefs.getInt(PREFS_KEY_SLOT_COUNT, 1).coerceIn(1, MAX_SLOTS)
    }

    private fun persistSlotCount() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREFS_KEY_SLOT_COUNT, slotCount).apply()
    }

    private fun persistWidget(slotIndex: Int, provider: ComponentName, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider", provider.flattenToString())
            .putInt("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id", appWidgetId)
            .apply()
    }

    private fun clearPersistedWidget(slotIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider")
            .remove("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id")
            .apply()
    }

    private class LoggingHostView(context: Context) : AppWidgetHostView(context) {
        override fun updateAppWidget(remoteViews: android.widget.RemoteViews?) {
            Log.d(TAG, "updateAppWidget DELIVERED for id=$appWidgetId: $remoteViews")
            super.updateAppWidget(remoteViews)
        }
    }

    private class LoggingAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        override fun onCreateView(
            context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView {
            Log.d(TAG, "onCreateView for id=$appWidgetId provider=${appWidget?.provider}")
            return LoggingHostView(context)
        }
    }
}