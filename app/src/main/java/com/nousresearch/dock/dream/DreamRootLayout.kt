package com.nousresearch.dock.dream

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of the dream. Watches every touch for a double-tap without taking
 * touches away from the pages underneath.
 */
class DreamRootLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var onDoubleTap: (() -> Unit)? = null

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // Claim touches no child wants, so the rest of the gesture still reaches the detector.
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
