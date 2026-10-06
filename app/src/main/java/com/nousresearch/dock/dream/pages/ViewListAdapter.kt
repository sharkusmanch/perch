package com.nousresearch.dock.dream.pages

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Serves a fixed list of pre-built views to a ViewPager2. Each position has
 * its own view type and a non-recyclable holder, so a view is never handed
 * to another position or re-created.
 */
class ViewListAdapter(private val views: List<View>) :
    RecyclerView.Adapter<ViewListAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = views.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = views[viewType]
        (view.parent as? ViewGroup)?.removeView(view)
        // ViewPager2 rejects pages that do not fill it.
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return Holder(view).apply { setIsRecyclable(false) }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {}
}
