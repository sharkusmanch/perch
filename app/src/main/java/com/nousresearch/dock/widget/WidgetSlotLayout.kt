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
 * Every input route into the widget is gated on the lock state — touch,
 * mouse/stylus, keys — and the widget is hidden from accessibility services
 * while locked. Keyboard focus never enters a widget at all.
 */
class WidgetSlotLayout(context: Context) : FrameLayout(context) {

    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    // Fail closed: if the lock state cannot be read, treat the device as locked.
    private val locked: Boolean get() = keyguard?.isDeviceLocked ?: true

    // Lock state for the touch gesture in progress. Asking the keyguard is a
    // cross-process call, too slow for every move, so it is read when the
    // finger goes down, at most every RECHECK_MS while it moves, and always
    // again before the finger lifts — the event that would deliver a click.
    private var gestureLocked = true
    private var lastLockCheckMs = 0L

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
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            gestureLocked = locked
            lastLockCheckMs = ev.eventTime
            syncAccessibility()
        } else if (!gestureLocked) {
            val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
            if (lifting || ev.eventTime - lastLockCheckMs >= RECHECK_MS) {
                lastLockCheckMs = ev.eventTime
                if (locked) {
                    // Locked while the finger was down: end the widget's
                    // gesture without a click and drop the rest of it.
                    gestureLocked = true
                    val cancel = MotionEvent.obtain(ev).apply { this.action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    syncAccessibility()
                }
            }
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
        const val RECHECK_MS = 250L
    }
}
