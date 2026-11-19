package io.herbert.leaf

import android.view.View

/** Reports search progress: [current] is 1-based, [total] is 0 when nothing matched. */
fun interface SearchSink {
    fun report(current: Int, total: Int)
}

/**
 * Everything the reader chrome needs to know about a document view. Keeping this
 * interface small is what lets every format share one toolbar and one back stack.
 */
interface Reader {

    val view: View

    /** Optional strip below the content (page slider, chapter arrows). */
    val controls: View? get() = null

    fun close() {}

    fun themeChanged(dark: Boolean) {}

    val textScalable: Boolean get() = false

    /** delta is +1 or -1, in the reader's own sizing unit. */
    fun textScale(delta: Int) {}

    fun contents(): List<String>? = null

    fun goToContents(index: Int) {}

    val pager: Boolean get() = false

    val pageCount: Int get() = 0

    val currentPage: Int get() = 0

    fun goToPage(index: Int) {}

    /** True when the document has text Leaf can search (everything except PDF/images). */
    val searchable: Boolean get() = false

    /** Starts a new search. An empty query clears the current one. */
    fun search(query: String, sink: SearchSink) {}

    /** Moves to the next or previous match, wrapping around. */
    fun findNext(forward: Boolean) {}

    fun clearSearch() {}

    // ---- reading position --------------------------------------------------------

    /** Stable key for the document a position belongs to, e.g. a content or file hash. */
    var positionKey: String?
        get() = null
        set(value) {}

    /** Current reading position in reader-local units (page, chapter or chunk index). */
    fun position(): Int = 0

    /** Restores a position previously reported by [position]. */
    fun restorePosition(pos: Int) {}
}
