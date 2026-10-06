package com.nousresearch.dock.slideshow

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * PhotoSlideshowManager — manages the crossfade slideshow on the dream's Photos page.
 *
 * Responsibilities:
 * - Load photos from persisted URIs using Glide
 * - Crossfade between two ImageViews (front/back) for smooth transitions
 * - Configurable interval (30s / 1m / 5m)
 *
 * Usage:
 *   val manager = PhotoSlideshowManager.getInstance(context)
 *   manager.init(frontImageView, backImageView, scrimView)
 *   manager.setPhotoUris(uris)
 *   manager.showFirst()      // dream start: show a photo
 *   manager.resumeCycling()  // page visible: advance on the interval
 *   manager.pauseCycling()   // page hidden: hold the current photo
 *   manager.release()        // dream stop
 */
class PhotoSlideshowManager private constructor(
    private val context: Context
) {

    companion object {
        @Volatile private var INSTANCE: PhotoSlideshowManager? = null
        fun getInstance(context: Context): PhotoSlideshowManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoSlideshowManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    // View references
    private var frontImageView: ImageView? = null
    private var backImageView: ImageView? = null
    private var scrimView: View? = null

    // State
    private var photoUris: List<Uri> = emptyList()
    private var currentIndex = 0
    private var isFrontShowing = true
    private var isCycling = false
    private var intervalMillis = 60000L // default 1 minute
    private var consecutiveFailures = 0

    private val handler = Handler(Looper.getMainLooper())

    // Runnable for advancing slideshow
    private val advanceRunnable = Runnable { advanceSlideshow() }

    /**
     * Initialize with view references. Must be called before showFirst().
     */
    fun init(
        front: ImageView,
        back: ImageView,
        scrim: View
    ) {
        frontImageView = front
        backImageView = back
        scrimView = scrim
        isFrontShowing = true
    }

    /** Set the list of photo URIs to cycle through. */
    fun setPhotoUris(uris: List<Uri>) {
        photoUris = uris
        currentIndex = 0
        consecutiveFailures = 0
    }

    fun hasPhotos(): Boolean = photoUris.isNotEmpty()

    /** Show the current photo without starting the timer. */
    fun showFirst() {
        if (photoUris.isEmpty()) {
            hideViews()
            return
        }
        showViews()
        loadCurrentPhoto()
    }

    /** Advance to the next photo every interval. */
    fun resumeCycling() {
        if (isCycling || photoUris.size < 2) return
        isCycling = true
        scheduleNext()
    }

    /** Hold the current photo. */
    fun pauseCycling() {
        isCycling = false
        handler.removeCallbacks(advanceRunnable)
    }

    /** Stop and drop every reference to the dream's views. */
    fun release() {
        pauseCycling()
        frontImageView = null
        backImageView = null
        scrimView = null
    }

    /** Update the slideshow interval. Takes effect on next cycle. */
    fun setInterval(millis: Long) {
        intervalMillis = millis
        if (isCycling) {
            handler.removeCallbacks(advanceRunnable)
            scheduleNext()
        }
    }

    // ------------------------------------------------------------------
    // Private implementation
    // ------------------------------------------------------------------

    private fun showViews() {
        frontImageView?.visibility = View.VISIBLE
        backImageView?.visibility = View.VISIBLE
        scrimView?.visibility = View.VISIBLE
    }

    private fun hideViews() {
        frontImageView?.visibility = View.GONE
        backImageView?.visibility = View.GONE
        scrimView?.visibility = View.GONE
    }

    private fun loadCurrentPhoto() {
        val uri = photoUris[currentIndex]
        val targetView = if (isFrontShowing) backImageView else frontImageView

        // Decode at screen size: a full-resolution camera photo can be too
        // large a bitmap to draw at all.
        val metrics = context.resources.displayMetrics
        val width = targetView?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = targetView?.height?.takeIf { it > 0 } ?: metrics.heightPixels

        Glide.with(context)
            .load(uri)
            .centerCrop()
            .into(object : CustomTarget<Drawable>(width, height) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    consecutiveFailures = 0
                    // The dream may have stopped, or rebuilt its views, while this loaded.
                    val stillTarget = if (isFrontShowing) backImageView else frontImageView
                    if (targetView == null || targetView !== stillTarget) return
                    targetView.apply {
                        setImageDrawable(resource)
                        alpha = 0f
                        visibility = View.VISIBLE
                    }
                    crossfadeViews()
                }

                // A deleted photo, or one whose permission was revoked: try
                // the next, giving up once every photo has failed in a row.
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    val stillTarget = if (isFrontShowing) backImageView else frontImageView
                    if (targetView == null || targetView !== stillTarget) return
                    consecutiveFailures++
                    if (consecutiveFailures < photoUris.size) {
                        currentIndex = (currentIndex + 1) % photoUris.size
                        loadCurrentPhoto()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun crossfadeViews() {
        val fromView = if (isFrontShowing) frontImageView else backImageView
        val toView = if (isFrontShowing) backImageView else frontImageView

        toView?.animate()?.alpha(1f)?.setDuration(500)?.start()
        fromView?.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            fromView?.setImageDrawable(null)
        }?.start()

        isFrontShowing = !isFrontShowing
    }

    private fun advanceSlideshow() {
        if (!isCycling || photoUris.isEmpty()) return
        currentIndex = (currentIndex + 1) % photoUris.size
        loadCurrentPhoto()
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.postDelayed(advanceRunnable, intervalMillis)
    }
}
