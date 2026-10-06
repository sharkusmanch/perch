package com.nousresearch.dock.widget

import android.app.KeyguardManager
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Holds one hosted widget. The dream shows over the lock screen and takes
 * touches, so while the device is locked the widget is display-only:
 * otherwise anyone picking up the phone could press its buttons (which can
 * fire the owning app's actions without unlocking).
 */
class WidgetSlotLayout(context: Context) : FrameLayout(context) {

    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    // Fail closed: if the lock state cannot be read, treat the device as locked.
    private val locked: Boolean get() = keyguard?.isDeviceLocked ?: true

    // Intercepted touches are not consumed here, so swipes still reach the pager.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = locked
}
