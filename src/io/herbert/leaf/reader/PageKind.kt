package io.herbert.leaf.reader

import io.herbert.leaf.core.Html

/**
 * How a WebView-backed chapter should look. The body class and the page colours travel
 * together, so a caller only has to say what kind of document it is opening.
 */
enum class PageKind {

    /** Prose that follows the app theme: EPUB, Markdown, spreadsheets. */
    TEXT,

    /** A white page, nothing else: used for bare conversions. */
    PAPER,

    /** A word-processor page: white, plus the document layout rules. */
    DOC,

    /** Slide canvases: grey desk, white slides, pinch zoom. */
    DECK;

    val paper: Boolean get() = this != TEXT

    val bodyClass: String
        get() = when (this) {
            DOC -> "doc"
            DECK -> "deck"
            else -> ""
        }

    fun html(body: String, dark: Boolean, fontPx: Int): String =
        Html.page(body, if (paper) false else dark, fontPx, paper, bodyClass)
}
