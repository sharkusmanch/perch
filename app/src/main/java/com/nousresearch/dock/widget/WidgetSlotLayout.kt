package com.nousresearch.dock.widget

import android.app.KeyguardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Holds one hosted widget. The dream shows over the lock screen and takes
 * input, so while the device is locked the widget is display-only:
 * otherwise anyone picking up the phone could press its buttons (which can
 * fire the owning app's actions without unlocking).
 *
 * Every input route into the widget is gated on the lock state — touch
 * (decided when the finger goes down), mouse/stylus, keys — and the widget
 * is hidden from accessibility services while locked. Keyboard focus never
 * enters a widget at all.
 */
class WidgetSlotLayout(context: Context) : FrameLayout(context) {

    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    // Fail closed: if the lock state cannot be read, treat the device as locked.
    private val locked: Boolean get() = keyguard?.isDeviceLocked ?: true

    // Lock state for the touch gesture in progress, read once when it starts:
    // asking the keyguard is a cross-process call, too slow for every move.
    private var gestureLocked = true

    // The device can lock mid-dream with no event to tell us, so the
    // accessibility visibility is re-checked on a timer while attached.
    private val accessibilitySync = object : Runnable {
        override fun run() {
            syncAccessibility()
            postDelayed(this, ACCESSIBILITY_SYNC_MS)
        }
    }

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        syncAccessibility()
    }

    // Returning false leaves the gesture to the pager, so swipes that start
    // on a widget still change page.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureLocked = locked
            syncAccessibility()
        }
        return if (gestureLocked) false else super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        if (locked) false else super.dispatchGenericMotionEvent(ev)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (locked) false else super.dispatchKeyEvent(event)

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
        if (locked) false else super.dispatchKeyShortcutEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        if (locked) false else super.dispatchTrackballEvent(event)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(accessibilitySync)
        accessibilitySync.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(accessibilitySync)
        super.onDetachedFromWindow()
    }

    private fun syncAccessibility() {
        val wanted = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (importantForAccessibility != wanted) importantForAccessibility = wanted
    }

    private companion object {
        const val ACCESSIBILITY_SYNC_MS = 2_000L
    }
}
