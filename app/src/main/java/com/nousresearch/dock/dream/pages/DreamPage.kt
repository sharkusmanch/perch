package com.nousresearch.dock.dream.pages

import android.view.View

/**
 * One full-screen page of the dream.
 *
 * Pages live for the whole dream: [attach]/[detach] bracket the dream and do
 * the expensive setup (binding widgets, loading photos); [resume]/[pause]
 * follow page selection and only start and stop timers, so a page is never
 * rebuilt by a swipe.
 */
interface DreamPage {
    val view: View

    fun attach()
    fun detach()
    fun resume()
    fun pause()

    /** One-shot redraw, called when a swipe starts so no page slides in stale. */
    fun refresh() {}

    fun setNightMode(on: Boolean) {}
}
