package io.herbert.leaf.reader

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import io.herbert.leaf.Reader
import io.herbert.leaf.SearchSink
import io.herbert.leaf.Ui
import io.herbert.leaf.core.Finder
import java.util.concurrent.Executors

/**
 * Plain text and code. The document is pre-split into ~6 KB chunks so a single
 * TextView never has to lay out megabytes. Search walks the chunks, highlights the
 * current hit in place and scrolls it near the top of the screen.
 */
class TextReader(
    private val ctx: Context,
    private val chunks: List<String>,
    private var dark: Boolean,
    private var fontPx: Int,
    private val mono: Boolean = false,
) : Reader {

    private val list = ListView(ctx)
    private val adapter = ChunkAdapter()

    private var sinks: SearchSink? = null
    private var query = ""
    private var hits: List<Finder.Hit> = emptyList()
    private var hitIndex = 0
    private var highlight: Triple<Int, Int, Int>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "leaf-search")
    }
    private var searchToken = 0

    init {
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.setBackgroundColor(Ui.bg(dark))
        list.cacheColorHint = Ui.bg(dark)
        list.isVerticalScrollBarEnabled = true
    }

    override val view: View = list
    override val textScalable: Boolean = true
    override val searchable: Boolean = true

    override fun textScale(delta: Int) {
        fontPx = (fontPx + delta).coerceIn(11, 34)
        val first = list.firstVisiblePosition
        val top = if (list.childCount > 0) list.getChildAt(0).top else 0
        adapter.notifyDataSetChanged()
        list.setSelectionFromTop(first, top)
    }

    override fun themeChanged(dark: Boolean) {
        this.dark = dark
        list.setBackgroundColor(Ui.bg(dark))
        list.cacheColorHint = Ui.bg(dark)
        adapter.notifyDataSetChanged()
    }

    // ---- search ----------------------------------------------------------------

    override fun close() {
        // A reader that goes away must not keep a search thread (or the documents it
        // scanned) alive.
        searchExecutor.shutdown()
    }

    override fun search(query: String, sink: SearchSink) {
        val token = ++searchToken
        sinks = sink
        this.query = query
        hits = emptyList()
        hitIndex = 0

        val needle = query.trim()
        if (needle.isEmpty()) {
            highlight = null
            adapter.notifyDataSetChanged()
            sink.report(0, 0)
            return
        }

        // The scan runs off the UI thread so a large text file cannot jank the typing
        // that is asking for the search. The result is applied on the main thread.
        searchExecutor.execute {
            val found = Finder.all(chunks, needle, MAX_HITS)
            mainHandler.post {
                if (token != searchToken) return@post
                hits = found
                if (found.isEmpty()) {
                    highlight = null
                    adapter.notifyDataSetChanged()
                    sink.report(0, 0)
                    return@post
                }
                hitIndex = 0
                showHit()
            }
        }
    }

    override fun findNext(forward: Boolean) {
        if (hits.isEmpty()) return
        hitIndex = if (forward) {
            (hitIndex + 1) % hits.size
        } else {
            (hitIndex - 1 + hits.size) % hits.size
        }
        showHit()
    }

    override fun clearSearch() {
        sinks = null
        query = ""
        hits = emptyList()
        hitIndex = 0
        highlight = null
        adapter.notifyDataSetChanged()
    }

    override fun position(): Int = list.firstVisiblePosition

    override fun restorePosition(pos: Int) {
        val p = pos.coerceIn(0, (adapter.count - 1).coerceAtLeast(0))
        list.setSelection(p)
    }

    private fun showHit() {
        val hit = hits[hitIndex]
        val chunk = hit.chunk
        val start = hit.offset.coerceIn(0, chunks[chunk].length)
        val end = (start + query.trim().length).coerceIn(start, chunks[chunk].length)
        highlight = Triple(chunk, start, end)

        adapter.notifyDataSetChanged()
        list.setSelection(chunk)
        list.post { reveal(chunk, start) }
        sinks?.report(hitIndex + 1, hits.size)
    }

    /** Scrolls so the matched line sits a little below the top edge. */
    private fun reveal(position: Int, offset: Int) {
        val child = list.getChildAt(position - list.firstVisiblePosition) as? TextView ?: return
        val layout = child.layout ?: return
        val length = child.text?.length ?: 0
        val line = layout.getLineForOffset(offset.coerceIn(0, length))
        val lineTop = layout.getLineTop(line)
        val margin = Ui.dp(ctx, 22)
        list.setSelectionFromTop(position, (margin - lineTop).coerceAtMost(margin))
    }

    private inner class ChunkAdapter : BaseAdapter() {
        override fun getCount(): Int = chunks.size
        override fun getItem(position: Int): Any = chunks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView as? TextView ?: TextView(ctx).apply {
                setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 6), Ui.dp(ctx, 18), Ui.dp(ctx, 6))
                setLineSpacing(Ui.dp(ctx, 3).toFloat(), 1f)
                setTextIsSelectable(true)
                includeFontPadding = true
            }
            tv.setTextColor(Ui.text(dark))
            tv.textSize = fontPx.toFloat()
            tv.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT

            val raw = chunks[position]
            val hit = highlight
            tv.text = if (hit != null && hit.first == position) {
                SpannableString(raw).apply {
                    val a = hit.second.coerceIn(0, raw.length)
                    val b = hit.third.coerceIn(a, raw.length)
                    if (b > a) setSpan(BackgroundColorSpan(Ui.hitBg(dark)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                raw
            }
            return tv
        }
    }

    private companion object {
        const val MAX_HITS = 5000
    }
}
