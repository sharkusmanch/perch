package com.nousresearch.dock.dream

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.BatteryManager
import android.service.dreams.DreamService
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.night.NightModeController
import com.nousresearch.dock.dream.night.NightTint
import com.nousresearch.dock.dream.pages.ClockPage
import com.nousresearch.dock.dream.pages.DreamPage
import com.nousresearch.dock.dream.pages.PhotosPage
import com.nousresearch.dock.dream.pages.ViewListAdapter
import com.nousresearch.dock.dream.pages.WidgetsPage

/**
 * Dock dream service — the charging screensaver.
 *
 * Three full-screen pages swiped sideways: Widgets, Photos, Clock. The
 * Clock page swipes up and down between faces. When the room is dark the
 * whole screen turns dim and red (Night Mode).
 *
 * The dream is interactive so swipes reach the pages; a double-tap on the
 * Photos or Clock page wakes the device.
 *
 * The system handles auto-launch when charging (user selects Dock
 * in Settings → Display → Screen saver → While charging).
 */
class DockDreamService : DreamService() {

    private lateinit var prefs: SharedPreferences

    private var root: DreamRootLayout? = null
    private var pager: ViewPager2? = null
    private var batteryStatus: TextView? = null
    private var pages: List<DreamPage> = emptyList()

    private var dreaming = false
    private var builtOrientation = Configuration.ORIENTATION_UNDEFINED
    private var batteryReceiver: BroadcastReceiver? = null
    // Kept so a rebuilt view (rotation) shows the level without waiting for the next broadcast.
    private var batteryText: CharSequence = ""

    // Night Mode
    private var nightController: NightModeController? = null
    private var nightOn = false
    private var tintFraction = 0f
    private var tintAnimator: ValueAnimator? = null
    private val tintPaint = Paint()

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        private var current = -1

        override fun onPageSelected(position: Int) {
            if (position == current) return
            pages.getOrNull(current)?.pause()
            pages.getOrNull(position)?.resume()
            current = position
            prefs.edit().putInt(DreamPrefs.KEY_LAST_PAGE, position).apply()
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) pages.forEach { it.refresh() }
        }

        fun reset() {
            current = -1
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(true)
        setFullscreen(true)
        setScreenBright(false)
        applySystemUiFlags()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        buildContent(currentPhysicalOrientation())
    }

    /**
     * Called when the device orientation changes while the dream is
     * running (e.g. user rotates the phone). Tears down the pages and
     * rebuilds them for the new orientation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // The system can deliver this before onAttachedToWindow() has ever
        // run (initial dream-window launch), or after the dream window has
        // gone. There is nothing to rebuild then.
        if (root == null || window == null) return

        // Theme, locale and font-scale changes arrive here too; only a
        // rotation needs the pages rebuilt.
        if (newConfig.orientation == builtOrientation) return

        if (dreaming) stopPages()
        buildContent(newConfig.orientation)
        if (dreaming) startPages()
        applyNightMode(animate = false)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        startPages()
        registerBatteryReceiver()
        if (prefs.getBoolean(getString(R.string.pref_key_night_mode), true)) {
            nightController = NightModeController(this, ::onNightModeChanged).also { it.start() }
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        dreaming = false
        nightController?.stop()
        nightController = null
        nightOn = false
        applyNightMode(animate = false)
        unregisterBatteryReceiver()
        stopPages()
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    /** Inflates the root and builds the three pages. */
    private fun buildContent(orientation: Int) {
        builtOrientation = orientation
        setContentView(R.layout.dream_dock)
        val root = findViewById<DreamRootLayout>(R.id.dream_root)
        val pager = findViewById<ViewPager2>(R.id.dream_pager)
        this.root = root
        this.pager = pager
        batteryStatus = findViewById(R.id.battery_status)

        pages = listOf(
            WidgetsPage(this, orientation == Configuration.ORIENTATION_LANDSCAPE),
            PhotosPage(this, prefs),
            ClockPage(this, prefs)
        )
        pager.adapter = ViewListAdapter(pages.map { it.view })
        // Keep every page alive so a swipe never rebuilds widgets or photos.
        pager.offscreenPageLimit = pages.size - 1

        // Widgets handle their own taps, so the exit gesture is left off that page.
        root.onDoubleTap = { if (pager.currentItem != PAGE_WIDGETS) wakeUp() }

        applyBackgroundColor()
    }

    private fun startPages() {
        val pager = pager ?: return
        pages.forEach { it.attach() }
        val last = prefs.getInt(DreamPrefs.KEY_LAST_PAGE, PAGE_CLOCK).coerceIn(0, pages.size - 1)
        pager.setCurrentItem(last, false)
        pageCallback.reset()
        pager.registerOnPageChangeCallback(pageCallback)
        // Registering does not report the page already showing.
        pageCallback.onPageSelected(last)
        pages.forEach { it.setNightMode(nightOn) }

        val showBattery = prefs.getBoolean(getString(R.string.pref_key_battery_enabled), true)
        batteryStatus?.visibility = if (showBattery) View.VISIBLE else View.GONE
        batteryStatus?.text = batteryText
    }

    private fun stopPages() {
        pager?.unregisterOnPageChangeCallback(pageCallback)
        pages.forEach {
            it.pause()
            it.detach()
        }
    }

    // ------------------------------------------------------------------
    // Night Mode
    // ------------------------------------------------------------------

    private fun onNightModeChanged(on: Boolean) {
        nightOn = on
        applyNightMode(animate = true)
    }

    /**
     * Brings the screen in line with [nightOn]: brightness, black
     * backgrounds, and the red tint over everything on screen.
     */
    private fun applyNightMode(animate: Boolean) {
        applyBrightness()
        applyBackgroundColor()
        pages.forEach { it.setNightMode(nightOn) }

        tintAnimator?.cancel()
        val target = if (nightOn) 1f else 0f
        if (!animate || tintFraction == target) {
            setTint(target)
            return
        }
        tintAnimator = ValueAnimator.ofFloat(tintFraction, target).apply {
            duration = TINT_ANIMATION_MS
            addUpdateListener { setTint(it.animatedValue as Float) }
            start()
        }
    }

    /** Draws the whole view tree through the Night Mode colour matrix, or directly when [fraction] is 0. */
    private fun setTint(fraction: Float) {
        tintFraction = fraction
        val root = root ?: return
        if (fraction <= 0f) {
            root.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            tintPaint.colorFilter = ColorMatrixColorFilter(NightTint.matrix(fraction))
            root.setLayerType(View.LAYER_TYPE_HARDWARE, tintPaint)
        }
    }

    private fun applyBrightness() {
        val lp = window?.attributes ?: return
        lp.screenBrightness =
            if (nightOn) NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.attributes = lp
    }

    // The warm dark grey would glow red under the Night Mode tint.
    private fun applyBackgroundColor() {
        val useOled = prefs.getBoolean(getString(R.string.pref_key_oled_mode), false)
        val color = if (useOled || nightOn) Color.BLACK else getColor(R.color.bg_dark)
        root?.setBackgroundColor(color)
    }

    // ------------------------------------------------------------------
    // Battery
    // ------------------------------------------------------------------

    private fun registerBatteryReceiver() {
        unregisterBatteryReceiver()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                if (pct >= 0) {
                    batteryText = "$pct%"
                    batteryStatus?.text = batteryText
                }
            }
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            batteryReceiver = null
        }
    }

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    /** Hide system bars for an immersive dream experience. */
    private fun applySystemUiFlags() {
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * Detects the physical display rotation directly.  The system
     * DreamActivity wrapper can report a stale/fixed orientation
     * that doesn't match how the device is actually held.
     */
    private fun currentPhysicalOrientation(): Int {
        val display = (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            ?: return resources.configuration.orientation
        return when (display.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
            else -> Configuration.ORIENTATION_PORTRAIT
        }
    }

    private companion object {
        const val PAGE_WIDGETS = 0
        const val PAGE_CLOCK = 2
        const val NIGHT_BRIGHTNESS = 0.01f
        const val TINT_ANIMATION_MS = 1_000L
    }
}
