package com.nousresearch.dock.dream.pages

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.TextClock
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.DreamPrefs
import com.nousresearch.dock.slideshow.PhotoSlideshowManager

/** The photo slideshow with a small time and date in the corner. */
class PhotosPage(private val context: Context, private val prefs: SharedPreferences) : DreamPage {

    override val view: View = LayoutInflater.from(context).inflate(R.layout.page_photos, null)

    private val clock: View = view.findViewById(R.id.photo_clock)
    private val time: TextClock = view.findViewById(R.id.photo_time)
    private val hint: View = view.findViewById(R.id.photos_hint)
    private val manager = PhotoSlideshowManager.getInstance(context)

    override fun attach() {
        manager.init(
            view.findViewById(R.id.slideshow_front),
            view.findViewById(R.id.slideshow_back),
            view.findViewById(R.id.scrim_overlay)
        )

        val stored = prefs.getString(DreamPrefs.KEY_PHOTO_URIS, "") ?: ""
        manager.setPhotoUris(if (stored.isEmpty()) emptyList() else stored.split("|").map(Uri::parse))
        val interval = prefs.getString(context.getString(R.string.pref_key_slideshow_interval), null)
        manager.setInterval(interval?.toLongOrNull() ?: DEFAULT_INTERVAL_MS)

        // The app has its own 24-hour setting, so pin both TextClock formats to it.
        val is24Hour = prefs.getBoolean(context.getString(R.string.pref_key_clock_24h), true)
        val pattern = if (is24Hour) "H:mm" else "h:mm"
        time.format12Hour = pattern
        time.format24Hour = pattern

        val hasPhotos = manager.hasPhotos()
        clock.visibility = if (hasPhotos) View.VISIBLE else View.GONE
        hint.visibility = if (hasPhotos) View.GONE else View.VISIBLE
        manager.showFirst()
    }

    override fun detach() {
        manager.release()
    }

    override fun resume() {
        manager.resumeCycling()
    }

    override fun pause() {
        manager.pauseCycling()
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}
