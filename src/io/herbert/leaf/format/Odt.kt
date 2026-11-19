package io.herbert.leaf.format

import io.herbert.leaf.core.Html
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * OpenDocument text (.odt). content.xml is walked once; the automatic styles declared at
 * the top of that file are collected before the body, so alignment, indentation, fills,
 * colours, weight and size survive. Pictures are unpacked from the package, tables become
 * real tables and lists become real lists.
 *
 * Named styles from styles.xml are read first and the file's automatic styles then win,
 * which is how a word processor resolves them too.
 */
object Odt {

    private val SKIP = setOf("annotation", "note", "note-body", "tracked-changes")

    private class Style(
        val align: String? = null,
        val marginLeft: String? = null,
        val marginRight: String? = null,
        val textIndent: String? = null,
        val bg: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val color: String? = null,
        val size: String? = null,
        val parent: String? = null,
    ) {
        /** This style with whatever it leaves unset taken from [p]. */
        fun inherit(p: Style?): Style {
            if (p == null) return this
            return Style(
                align ?: p.align,
                marginLeft ?: p.marginLeft,
                marginRight ?: p.marginRight,
                textIndent ?: p.textIndent,
                bg ?: p.bg,
                bold || p.bold,
                italic || p.italic,
                underline || p.underline,
                strike || p.strike,
                color ?: p.color,
                size ?: p.size,
            )
        }
    }

    /** Collects one style:style element while the parser walks its children. */
    private class StyleAcc {
        var align: String? = null
        var marginLeft: String? = null
        var marginRight: String? = null
        var textIndent: String? = null
        var bg: String? = null
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var color: String? = null
        var size: String? = null
        var parent: String? = null

        fun reset() {
            align = null; marginLeft = null; marginRight = null; textIndent = null; bg = null
            bold = false; italic = false; underline = false; strike = false
            color = null; size = null; parent = null
        }

        fun start(xp: XmlPullParser) {
            when (Xmls.local(xp)) {
                "paragraph-properties" -> {
                    align = Xmls.attr(xp, "text-align")
                    marginLeft = Xmls.attr(xp, "margin-left")
                    marginRight = Xmls.attr(xp, "margin-right")
                    textIndent = Xmls.attr(xp, "text-indent")
                    bg = Xmls.attr(xp, "background-color")?.takeIf { it != "transparent" }
                }
                "table-cell-properties" -> {
                    bg = Xmls.attr(xp, "background-color")?.takeIf { it != "transparent" }
                }
                "text-properties" -> {
                    bold = Xmls.attr(xp, "font-weight")?.equals("bold", true) ?: false
                    italic = Xmls.attr(xp, "font-style")?.equals("italic", true) ?: false
                    underline = Xmls.attr(xp, "text-underline-style")
                        ?.let { it.isNotEmpty() && !it.equals("none", true) } ?: false
                    strike = Xmls.attr(xp, "text-line-through-style")
                        ?.let { it.isNotEmpty() && !it.equals("none", true) } ?: false
                    color = Xmls.attr(xp, "color")
                    size = Xmls.attr(xp, "font-size")
                }
            }
        }

        fun build() = Style(
            align, marginLeft, marginRight, textIndent, bg,
            bold, italic, underline, strike, color, size, parent,
        )
    }

    fun html(z: ZipFile, mediaDir: File, maxChars: Int = Int.MAX_VALUE): List<String> {
        val entry = z.getEntry("content.xml") ?: return listOf("<p>This document has no text body.</p>")
        val styles = readStyles(z, "styles.xml")
        val numberedLists = readNumberedLists(z, "styles.xml", "content.xml")
        mediaDir.mkdirs()

        val out = StringBuilder()
        val para = StringBuilder()
        val spanBuf = ArrayList<StringBuilder>()
        val spanOpen = ArrayList<String>()
        val spanClose = ArrayList<String>()

        var inAuto = false
        var inText = false
        var skip = 0
        var heading = 0
        var pStyle: Style? = null
        var styleName: String? = null
        val acc = StyleAcc()

        // Picture frame state.
        var inFrame = false
        var frameW: String? = null
        var frameH: String? = null

        // Table cell span, one entry per open cell, plus where its "<td" starts so the
        // attributes can be added once they are known (nested tables make search unsafe).
        val cellSpans = ArrayList<Int>()
        val tdPositions = ArrayList<Int>()
        // Open lists, so a numbered list closes with </ol> and nesting stays balanced.
        val listTags = ArrayList<String>()

        fun target(): StringBuilder = if (spanBuf.isEmpty()) para else spanBuf[spanBuf.size - 1]

        fun closeSpan() {
            val inner = spanBuf.removeAt(spanBuf.size - 1)
            val open = spanOpen.removeAt(spanOpen.size - 1)
            val close = spanClose.removeAt(spanClose.size - 1)
            target().append(open).append(inner).append(close)
        }

        fun flush() {
            // A malformed file can leave a span open; close it rather than lose its text.
            while (spanBuf.isNotEmpty()) closeSpan()
            val body = para.toString().trim()
            para.setLength(0)
            if (body.isEmpty()) return
            val css = paraCss(pStyle)
            if (heading > 0) {
                val h = heading.coerceIn(1, 4)
                out.append("<h").append(h)
                if (css.isNotEmpty()) out.append(" style=\"").append(css).append('"')
                out.append('>').append(body).append("</h").append(h).append('>')
            } else {
                out.append("<p")
                if (css.isNotEmpty()) out.append(" style=\"").append(css).append('"')
                out.append('>').append(body).append("</p>")
            }
        }

        fun emitImage(href: String?) {
            if (href == null) return
            val path = Parts.resolve("", href) ?: return
            val name = Parts.extract(z, path, mediaDir) ?: return
            target().append("<img class=\"docimg\" src=\"").append(name).append("\" alt=\"\"")
            val w = frameW
            val h = frameH
            if (w != null || h != null) {
                target().append(" style=\"")
                if (w != null) target().append("width:").append(w).append(';')
                if (h != null) target().append("height:").append(h).append(';')
                target().append('"')
            }
            target().append('>')
            frameW = null
            frameH = null
        }

        var partial = false

        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val name = when (ev) {
                        XmlPullParser.START_TAG, XmlPullParser.END_TAG -> Xmls.local(xp)
                        else -> ""
                    }
                    if (ev == XmlPullParser.START_TAG) {
                        when {
                            name == "automatic-styles" -> inAuto = true
                            name == "text" -> inText = true
                            name in SKIP -> skip++
                            inAuto -> when (name) {
                                "style" -> {
                                    styleName = Xmls.attr(xp, "name")
                                    acc.reset()
                                    acc.parent = Xmls.attr(xp, "parent-style-name")
                                }
                                else -> if (styleName != null) acc.start(xp)
                            }
                            skip > 0 -> Unit
                            else -> when (name) {
                                "h" -> {
                                    flush()
                                    heading = Xmls.attr(xp, "outline-level")?.toIntOrNull() ?: 1
                                    pStyle = styles[Xmls.attr(xp, "style-name")]
                                }
                                "p" -> {
                                    flush()
                                    heading = 0
                                    pStyle = styles[Xmls.attr(xp, "style-name")]
                                }
                                "span" -> {
                                    val st = styles[Xmls.attr(xp, "style-name")]
                                    spanBuf.add(StringBuilder())
                                    val (open, close) = inlineWraps(st)
                                    spanOpen.add(open)
                                    spanClose.add(close)
                                }
                                "s" -> target().append(" ".repeat((Xmls.attr(xp, "c")?.toIntOrNull() ?: 1).coerceIn(1, 24)))
                                "tab" -> target().append("<span class=\"tab\"></span>")
                                "line-break" -> target().append("<br>")
                                "list" -> {
                                    val tag =
                                        if (numberedLists.contains(Xmls.attr(xp, "style-name") ?: "")) "ol" else "ul"
                                    listTags.add(tag)
                                    out.append('<').append(tag).append('>')
                                }
                                "list-item", "list-header" -> out.append("<li>")
                                "table" -> out.append("<table class=\"doc\">")
                                "table-row" -> out.append("<tr>")
                                "table-cell" -> {
                                    out.append("<td>")
                                    tdPositions.add(out.length - 4)
                                    cellSpans.add(
                                        Xmls.attr(xp, "number-columns-spanned")?.toIntOrNull()?.coerceIn(1, 24) ?: 1
                                    )
                                }
                                "covered-table-cell" -> Unit
                                "frame" -> {
                                    inFrame = true
                                    frameW = null
                                    frameH = null
                                }
                                "width" -> if (inFrame) frameW = Xmls.text(xp)
                                "height" -> if (inFrame) frameH = Xmls.text(xp)
                                "image" -> emitImage(Xmls.attr(xp, "href"))
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when {
                            name == "automatic-styles" -> {
                                inAuto = false
                                // Automatic styles may be based on named ones; resolve them
                                // before the body is read.
                                resolveStyles(styles)
                            }
                            name == "text" -> inText = false
                            name in SKIP -> if (skip > 0) skip--
                            inAuto -> if (name == "style" && styleName != null) {
                                styles[styleName!!] = acc.build()
                                styleName = null
                            }
                            skip > 0 -> Unit
                            else -> when (name) {
                                "h", "p" -> flush()
                                "span" -> if (spanBuf.isNotEmpty()) closeSpan()
                                "list" -> {
                                    val tag = if (listTags.isEmpty()) "ul"
                                    else listTags.removeAt(listTags.size - 1)
                                    out.append("</").append(tag).append('>')
                                }
                                "list-item", "list-header" -> out.append("</li>")
                                "table" -> out.append("</table>")
                                "table-row" -> out.append("</tr>")
                                "table-cell" -> {
                                    val span = if (cellSpans.isEmpty()) 1 else cellSpans.removeAt(cellSpans.size - 1)
                                    val at = if (tdPositions.isEmpty()) -1 else tdPositions.removeAt(tdPositions.size - 1)
                                    if (span > 1 && at >= 0) out.insert(at + 3, " colspan=\"$span\"")
                                    out.append("</td>")
                                }
                                "frame" -> {
                                    inFrame = false
                                    frameW = null
                                    frameH = null
                                }
                            }
                        }
                    } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                        if (inText && !inAuto && skip == 0) target().append(Html.esc(xp.text ?: ""))
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
            partial = true
        }
        if (heading > 0 || para.isNotEmpty()) flush()

        var content = out.toString().ifEmpty { "<p>This document has no text body.</p>" }
        if (partial) content += Xmls.PARTIAL
        if (content.length <= maxChars) return listOf(content)
        // Long documents are cut between top-level blocks so one WebView never holds a
        // whole book at once.
        return Docx.splitTopLevel(content, maxChars)
    }

    // ---- helpers ----------------------------------------------------------------

    private fun readStyles(z: ZipFile, path: String): HashMap<String, Style> {
        val out = HashMap<String, Style>()
        val entry = z.getEntry(path) ?: return out
        val acc = StyleAcc()
        var name: String? = null
        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val local = when (ev) {
                        XmlPullParser.START_TAG, XmlPullParser.END_TAG -> Xmls.local(xp)
                        else -> ""
                    }
                    if (ev == XmlPullParser.START_TAG) {
                        if (local == "style") {
                            name = Xmls.attr(xp, "name")
                            acc.reset()
                            acc.parent = Xmls.attr(xp, "parent-style-name")
                        } else if (name != null) {
                            acc.start(xp)
                        }
                    } else if (ev == XmlPullParser.END_TAG && local == "style") {
                        val n = name
                        if (n != null) out[n] = acc.build()
                        name = null
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        resolveStyles(out)
        return out
    }

    /**
     * Applies style:parent-style-name. Without this, a style based on another loses
     * everything it was supposed to inherit, which is how most word processors write
     * their paragraph and character styles.
     */
    private fun resolveStyles(map: HashMap<String, Style>) {
        val done = HashMap<String, Style>(map.size)
        fun resolve(name: String, seen: HashSet<String>): Style? {
            done[name]?.let { return it }
            val s = map[name] ?: return null
            if (!seen.add(name)) return s
            val merged = s.inherit(s.parent?.let { resolve(it, seen) })
            done[name] = merged
            return merged
        }
        for (name in map.keys.toList()) resolve(name, HashSet())
        map.putAll(done)
    }

    /** List style names that number their items; every other list is a bullet list. */
    private fun readNumberedLists(z: ZipFile, vararg paths: String): HashSet<String> {
        val ordered = HashSet<String>()
        for (path in paths) {
            val entry = z.getEntry(path) ?: continue
            var current: String? = null
            try {
                z.getInputStream(entry).use { ins ->
                    val xp = Xmls.parser(ins)
                    var ev = xp.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val local = when (ev) {
                            XmlPullParser.START_TAG, XmlPullParser.END_TAG -> Xmls.local(xp)
                            else -> ""
                        }
                        if (ev == XmlPullParser.START_TAG) {
                            when (local) {
                                "list-style" -> current = Xmls.attr(xp, "name")
                                "list-level-style-number" -> current?.let { ordered.add(it) }
                            }
                        } else if (ev == XmlPullParser.END_TAG && local == "list-style") {
                            current = null
                        }
                        ev = xp.next()
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return ordered
    }

    private fun cssAlign(v: String?): String? = when (v) {
        null -> null
        "start" -> "left"
        "end" -> "right"
        "justify" -> "justify"
        else -> v
    }

    private fun paraCss(s: Style?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        if (s.align != null) sb.append("text-align:").append(cssAlign(s.align)).append(';')
        if (s.marginLeft != null) sb.append("margin-left:").append(s.marginLeft).append(';')
        if (s.marginRight != null) sb.append("margin-right:").append(s.marginRight).append(';')
        if (s.textIndent != null) sb.append("text-indent:").append(s.textIndent).append(';')
        if (s.bg != null) sb.append("background:").append(s.bg).append(';')
        return sb.toString()
    }

    private fun inlineWraps(s: Style?): Pair<String, String> {
        if (s == null) return "" to ""
        var open = ""
        var close = ""
        val css = StringBuilder()
        if (s.color != null) css.append("color:").append(s.color).append(';')
        if (s.size != null) css.append("font-size:").append(s.size).append(';')
        if (css.isNotEmpty()) {
            open += "<span style=\"$css\">"
            close = "</span>$close"
        }
        if (s.strike) {
            open += "<s>"
            close = "</s>$close"
        }
        if (s.underline) {
            open += "<u>"
            close = "</u>$close"
        }
        if (s.italic) {
            open += "<i>"
            close = "</i>$close"
        }
        if (s.bold) {
            open += "<b>"
            close = "</b>$close"
        }
        return open to close
    }
}
