package com.nousresearch.dock.dream.pages

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.nousresearch.dock.R
import com.nousresearch.dock.widget.WidgetHostManager

/** The hosted Android widgets, laid out across the whole screen. */
class WidgetsPage(context: Context, private val isLandscape: Boolean) : DreamPage {

    override val view: View = LayoutInflater.from(context).inflate(R.layout.page_widgets, null)

    private val container: ViewGroup = view.findViewById(R.id.widget_container)
    private val hint: View = view.findViewById(R.id.widgets_hint)
    private val manager = WidgetHostManager.getInstance(context)

    override fun attach() {
        manager.init(container, isLandscape)
        manager.start()
        // Decided after binding: a slot whose app was uninstalled binds nothing.
        val hasWidgets = manager.hasBoundWidget()
        container.visibility = if (hasWidgets) View.VISIBLE else View.GONE
        hint.visibility = if (hasWidgets) View.GONE else View.VISIBLE
    }

    override fun detach() {
        manager.release()
    }

    // Widgets update themselves through the host; there is nothing to pause.
    override fun resume() {}
    override fun pause() {}

    override fun setNightMode(on: Boolean) {
        manager.setNightMode(on)
    }
}
