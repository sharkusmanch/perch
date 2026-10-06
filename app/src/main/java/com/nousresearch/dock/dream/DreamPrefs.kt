package com.nousresearch.dock.dream

import android.content.SharedPreferences
import android.graphics.Color

/** Preference keys and defaults the dream reads that have no row in the settings screen, plus small helpers. */
object DreamPrefs {
    const val KEY_LAST_PAGE = "last_page"
    const val KEY_LAST_FACE = "last_face"
    const val KEY_PHOTO_URIS = "slideshow_photo_uris"

    const val DEFAULT_COLOR_DIGITAL = "#FFFFFF"
    const val DEFAULT_COLOR_ANALOG = "#FF9F0A"
    const val DEFAULT_COLOR_FLOAT = "#FF6482"

    /** Reads a "#RRGGBB" preference, falling back to [defaultHex] when unset or unparsable. */
    fun color(prefs: SharedPreferences, key: String, defaultHex: String): Int =
        try {
            Color.parseColor(prefs.getString(key, defaultHex) ?: defaultHex)
        } catch (_: IllegalArgumentException) {
            Color.parseColor(defaultHex)
        }
}
