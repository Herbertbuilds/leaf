package io.herbert.leaf.reader

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import io.herbert.leaf.Reader
import io.herbert.leaf.SearchSink
import io.herbert.leaf.Ui
import io.herbert.leaf.format.Chapter

/**
 * Everything that is naturally HTML: EPUB chapters, Word/ODT conversions, spreadsheets
 * as grids, slide decks. One WebView with JavaScript off, our own stylesheet, and a
 * chapter strip when the document has sections. Search uses WebView's native find, so
 * hits are highlighted and scrolled to exactly, with no JavaScript involved.
 */
class HtmlReader(
    private val ctx: Context,
    private val chapters: List<Chapter>,
    private val baseUrl: String?,
    private var dark: Boolean,
    private var fontPx: Int,
    private val kind: PageKind = PageKind.TEXT,
) : Reader {

    /** Slide canvases get pinch zoom instead of the text-size menu. */
    private val deck: Boolean get() = kind == PageKind.DECK

    private val web = WebView(ctx)
    private var index = 0
    private var pendingScroll = -1
    private var bar: LinearLayout? = null
    private var label: TextView? = null

    private var sink: SearchSink? = null
    private var query = ""

    init {
        applyWebSettings(web)
        web.setBackgroundColor(if (kind.paper) Color.WHITE else Ui.bg(dark))
        web.isVerticalScrollBarEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                if (target.startsWith("#")) return false
                val current = web.url ?: return true
                return target.substringBefore('#') != current.substringBefore('#')
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (pendingScroll >= 0) {
                    web.scrollTo(0, pendingScroll)
                    pendingScroll = -1
                }
                // Re-apply the search after a reload (theme change, font size, chapter).
                if (query.isNotEmpty()) runSearch(query)
            }
        }
        web.setFindListener { active, total, done ->
            if (!done) return@setFindListener
            val current = if (total <= 0) 0 else active + 1
            sink?.report(current, total)
        }

        if (chapters.size > 1) buildBar()
        load()
    }

    private fun buildBar() {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.surface(dark))
            setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0)
        }
        val prev = navButton("\u2039") { jump(-1) }
        val next = navButton("\u203a") { jump(1) }
        val mid = TextView(ctx).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.dim(dark))
        }
        label = mid
        strip.addView(prev, LinearLayout.LayoutParams(Ui.dp(ctx, 48), MATCH_PARENT))
        strip.addView(mid, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        strip.addView(next, LinearLayout.LayoutParams(Ui.dp(ctx, 48), MATCH_PARENT))
        bar = strip
    }

    private fun navButton(text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.text(dark))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun jump(delta: Int) {
        val next = (index + delta).coerceIn(0, chapters.size - 1)
        if (next == index) return
        index = next
        load()
        web.scrollTo(0, 0)
    }

    private fun load() {
        val chapter = chapters.getOrNull(index) ?: return
        val html = kind.html(chapter.body, dark, fontPx)
        web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        label?.text = "${index + 1} / ${chapters.size}  \u00b7  ${chapter.title}"
    }

    override val view: View = web
    override val controls: View? get() = bar
    // Slide text is sized in vw so it scales with the canvas; pinch zoom does the rest.
    override val textScalable: Boolean get() = !deck
    override val searchable: Boolean = true

    override fun contents(): List<String> = chapters.map { it.title }

    override fun goToContents(index: Int) {
        if (index !in chapters.indices) return
        this.index = index
        load()
        web.scrollTo(0, 0)
    }

    override fun textScale(delta: Int) {
        fontPx = (fontPx + delta).coerceIn(11, 34)
        pendingScroll = web.scrollY
        load()
    }

    override fun themeChanged(dark: Boolean) {
        this.dark = dark
        pendingScroll = web.scrollY
        web.setBackgroundColor(if (kind.paper) Color.WHITE else Ui.bg(dark))
        bar?.setBackgroundColor(Ui.surface(dark))
        label?.setTextColor(Ui.dim(dark))
        load()
    }

    // ---- search ----------------------------------------------------------------

    override fun search(query: String, sink: SearchSink) {
        this.sink = sink
        this.query = query.trim()
        if (this.query.isEmpty()) {
            runCatching { web.clearMatches() }
            sink.report(0, 0)
            return
        }
        runSearch(this.query)
    }

    private fun runSearch(q: String) {
        try {
            web.findAllAsync(q)
        } catch (_: Throwable) {
            sink?.report(0, 0)
        }
    }

    override fun findNext(forward: Boolean) {
        if (query.isEmpty()) return
        try {
            web.findNext(forward)
        } catch (_: Throwable) {
        }
    }

    override fun clearSearch() {
        query = ""
        sink = null
        try {
            web.clearMatches()
        } catch (_: Throwable) {
        }
    }

    override fun position(): Int = index

    override fun restorePosition(pos: Int) {
        val next = pos.coerceIn(0, chapters.size - 1)
        if (next != index) {
            index = next
            load()
            web.scrollTo(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWebSettings(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = false
            loadsImagesAutomatically = true
            blockNetworkImage = true
            builtInZoomControls = deck
            setSupportZoom(deck)
            displayZoomControls = false
            textZoom = 100
            defaultTextEncodingName = "utf-8"
        }
    }

    override fun close() {
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Throwable) {
        }
    }
}
