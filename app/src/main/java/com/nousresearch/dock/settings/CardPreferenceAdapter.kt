package com.nousresearch.dock.settings

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.nousresearch.dock.R

/**
 * Draws each run of rows between two category headers as one rounded card:
 * large corners on the card's outer edges, small ones between rows.
 */
// PreferenceGroupAdapter is the framework's own adapter; subclassing it is
// the only way to style rows by their position within a group.
@SuppressLint("RestrictedApi")
class CardPreferenceAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {

    private val handler = Handler(Looper.getMainLooper())
    private val rebindAll = Runnable { notifyItemRangeChanged(0, itemCount) }

    init {
        // Showing or hiding a row changes which neighbours are first or last
        // in their card, so every row's shape has to be re-decided.
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleRebind()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleRebind()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = scheduleRebind()
        })
    }

    private fun scheduleRebind() {
        handler.removeCallbacks(rebindAll)
        handler.post(rebindAll)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val view = holder.itemView
        val lp = view.layoutParams as? RecyclerView.LayoutParams ?: return
        val density = view.resources.displayMetrics.density

        if (getItem(position) is PreferenceCategory) {
            lp.setMargins(0, 0, 0, 0)
            view.layoutParams = lp
            return
        }

        val first = position == 0 || getItem(position - 1) is PreferenceCategory
        val last = position == itemCount - 1 || getItem(position + 1) is PreferenceCategory
        view.setBackgroundResource(
            when {
                first && last -> R.drawable.pref_bg_single
                first -> R.drawable.pref_bg_top
                last -> R.drawable.pref_bg_bottom
                else -> R.drawable.pref_bg_middle
            }
        )

        val side = (SIDE_MARGIN_DP * density).toInt()
        lp.setMargins(
            side,
            if (position == 0) (TOP_MARGIN_DP * density).toInt() else 0,
            side,
            if (last) 0 else (ROW_GAP_DP * density).toInt()
        )
        view.layoutParams = lp
    }

    private companion object {
        const val SIDE_MARGIN_DP = 16
        const val TOP_MARGIN_DP = 8
        const val ROW_GAP_DP = 2
    }
}
