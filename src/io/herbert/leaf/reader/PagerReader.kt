package io.herbert.leaf.reader

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import io.herbert.leaf.Reader
import io.herbert.leaf.Ui

/**
 * Vertical page pager. A ListView does the flinging and recycling; each row is a
 * [PageView] that asks the shared [PageCache] for its bitmap.
 */
class PagerReader(
    private val ctx: Context,
    private val source: PageSource,
    private var dark: Boolean,
) : Reader {

    private val cache: PageCache
    private val list = ListView(ctx)
    private val bar = LinearLayout(ctx)
    private val counter = TextView(ctx)
    private val seek: SeekBar
    private var dragging = false

    init {
        val budget = (Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(16 * 1024 * 1024, 96 * 1024 * 1024)
        cache = PageCache(source, budget)

        list.adapter = PageAdapter()
        list.divider = null
        list.dividerHeight = 0
        list.setBackgroundColor(Ui.bg(dark))
        list.cacheColorHint = Ui.bg(dark)
        list.isVerticalScrollBarEnabled = true

        counter.textSize = 12f
        counter.setTextColor(Ui.dim(dark))
        counter.minWidth = Ui.dp(ctx, 56)
        counter.gravity = Gravity.CENTER

        seek = SeekBar(ctx)
        seek.max = (source.pageCount - 1).coerceAtLeast(0)
        seek.isEnabled = source.pageCount > 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) list.setSelection(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                list.setSelection(sb.progress)
            }
        })

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.surface(dark))
        bar.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0)
        bar.addView(counter, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bar.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                updateLabel(first)
            }
        })

        if (source.pageCount <= 0) {
            counter.text = "empty"
        } else {
            updateLabel(0)
        }
    }

    private fun updateLabel(page: Int) {
        counter.text = "${(page + 1).coerceAtMost(maxOf(source.pageCount, 1))}/${source.pageCount}"
        if (!dragging) seek.progress = page.coerceIn(0, seek.max)
    }

    override val view: View = list
    override val controls: View = bar
    override val pager: Boolean = true
    override val pageCount: Int = source.pageCount
    override val currentPage: Int get() = list.firstVisiblePosition

    override fun goToPage(index: Int) {
        val p = index.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))
        list.setSelection(p)
        updateLabel(p)
    }

    override fun position(): Int = list.firstVisiblePosition.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))

    override fun restorePosition(pos: Int) {
        val p = pos.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0))
        list.setSelection(p)
        updateLabel(p)
    }

    override fun themeChanged(dark: Boolean) {
        this.dark = dark
        list.setBackgroundColor(Ui.bg(dark))
        list.cacheColorHint = Ui.bg(dark)
        bar.setBackgroundColor(Ui.surface(dark))
        counter.setTextColor(Ui.dim(dark))
        for (i in 0 until list.childCount) (list.getChildAt(i) as? PageView)?.setPlaceholder(Ui.bg(dark))
        list.invalidateViews()
    }

    override fun close() = cache.close()

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount(): Int = source.pageCount
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? PageView ?: PageView(ctx)
            v.setPlaceholder(Ui.bg(dark))
            v.bind(source, cache, position)
            return v
        }
    }
}
