package io.herbert.leaf.format

import io.herbert.leaf.core.Html
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Word documents (.docx). The body is walked once and turned into HTML that keeps what
 * makes a page look like the page in Word: pictures with their real size, tables with
 * borders, shading and merged cells, paragraph alignment, indentation, spacing and
 * paragraph fills, plus inline bold, italic, underline, strike, colour, highlight, size
 * and super/subscript.
 *
 * Not rendered: floating text boxes become inline text (their position is lost), and
 * SmartArt, charts, equations and footnotes are blank or show their cached text.
 */
object Docx {

    private const val EMU_PER_PX = 9525.0
    private const val TWIPS_PER_PT = 20.0

    /** Word's highlight names that differ from the CSS keyword of the same name. */
    private val HIGHLIGHTS = mapOf(
        "lightGray" to "#D9D9D9",
        "lightGrey" to "#D9D9D9",
        "darkGray" to "#A6A6A6",
        "darkGrey" to "#A6A6A6",
        "darkBlue" to "#00008B",
        "darkCyan" to "#008B8B",
        "darkGreen" to "#006400",
        "darkMagenta" to "#8B008B",
        "darkRed" to "#8B0000",
        "darkYellow" to "#B8860B",
    )

    fun html(z: ZipFile, mediaDir: File, maxChars: Int = Int.MAX_VALUE): List<String> {
        val entry = z.getEntry("word/document.xml") ?: return listOf("<p>This document has no text body.</p>")
        val links = Parts.rels(z, "word/_rels/document.xml.rels")
        val numbering = Numbering(z)
        mediaDir.mkdirs()

        val out = StringBuilder()
        val para = StringBuilder()
        val runText = StringBuilder()

        // ---- paragraph state ----
        var styleName: String? = null
        var outline = -1
        var align: String? = null
        var indentLeft = 0
        var indentRight = 0
        var firstLine = 0
        var spaceBefore = 0
        var spaceAfter = 0
        var lineHeight = 0
        var paraFill: String? = null
        var numId: String? = null
        var ilvl = 0
        var inPPr = false
        var inNumPr = false

        // ---- run state ----
        var inR = false
        var inRPr = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var color: String? = null
        var sizeHalf = 0
        var highlight: String? = null
        var runShade: String? = null
        var vertAlign: String? = null
        var caps = false

        // ---- cell state ----
        var inTcPr = false

        // ---- drawing state ----
        var imgCx = 0

        // ---- table state (cells can nest, so keep one entry per open cell) ----
        val tdPositions = ArrayList<Int>()
        val cellFills = ArrayList<String?>()
        val cellSpans = ArrayList<Int>()

        fun resetPara() {
            styleName = null; outline = -1; align = null
            indentLeft = 0; indentRight = 0; firstLine = 0
            spaceBefore = 0; spaceAfter = 0; lineHeight = 0
            paraFill = null; numId = null; ilvl = 0
        }

        fun resetRun() {
            bold = false; italic = false; underline = false; strike = false
            color = null; sizeHalf = 0; highlight = null; runShade = null
            vertAlign = null; caps = false
        }

        fun flushRun() {
            if (runText.isEmpty()) return
            var html = Html.spaced(runText.toString())
            runText.setLength(0)
            if (vertAlign == "superscript") html = "<sup>$html</sup>"
            else if (vertAlign == "subscript") html = "<sub>$html</sub>"
            if (caps) html = "<span class=\"caps\">$html</span>"
            val css = StringBuilder()
            if (color != null) css.append("color:#").append(color).append(';')
            if (sizeHalf > 0) css.append("font-size:").append(num(sizeHalf / 2.0)).append("pt;")
            if (highlight != null) {
                val v = HIGHLIGHTS[highlight] ?: highlight
                if (!v.equals("none", true)) css.append("background:").append(v).append(';')
            }
            // Run-level shading is a background on the text, not on the table cell.
            if (runShade != null) css.append("background:#").append(runShade).append(';')
            if (css.isNotEmpty()) html = "<span style=\"$css\">$html</span>"
            if (strike) html = "<s>$html</s>"
            if (underline) html = "<u>$html</u>"
            if (italic) html = "<i>$html</i>"
            if (bold) html = "<b>$html</b>"
            para.append(html)
        }

        /** Emits a picture now; the run text before it is flushed so order is preserved. */
        fun emitImage(id: String?) {
            if (id == null) return
            val rel = links[id] ?: return
            val path = Parts.resolve("word", rel.target) ?: return
            val name = Parts.extract(z, path, mediaDir) ?: return
            flushRun()
            para.append("<img class=\"docimg\" src=\"").append(name).append("\" alt=\"\"")
            if (imgCx > 0) {
                para.append(" style=\"width:").append(num(imgCx / EMU_PER_PX)).append("px\"")
            }
            para.append('>')
            imgCx = 0
        }

        fun flushPara() {
            flushRun()
            val body = para.toString().trim()
            para.setLength(0)
            if (body.isEmpty()) {
                resetPara()
                return
            }
            val css = StringBuilder()
            if (align != null) css.append("text-align:").append(align).append(';')
            if (indentLeft > 0) css.append("margin-left:").append(num(indentLeft / TWIPS_PER_PT)).append("pt;")
            if (indentRight > 0) css.append("margin-right:").append(num(indentRight / TWIPS_PER_PT)).append("pt;")
            if (firstLine != 0) css.append("text-indent:").append(num(firstLine / TWIPS_PER_PT)).append("pt;")
            if (spaceBefore > 0) css.append("margin-top:").append(num(spaceBefore / TWIPS_PER_PT)).append("pt;")
            if (spaceAfter > 0) css.append("margin-bottom:").append(num(spaceAfter / TWIPS_PER_PT)).append("pt;")
            if (lineHeight > 0) css.append("line-height:").append(num(lineHeight / 240.0)).append(';')
            if (paraFill != null) css.append("background:#").append(paraFill).append(';')

            val id = numId
            if (id != null) {
                val marker = numbering.marker(id, ilvl)
                // Word indents a list item from the left margin; keep at least one step.
                val step = if (indentLeft > 0) indentLeft else 720 * (ilvl + 1)
                out.append("<div class=\"li\" style=\"margin-left:")
                    .append(num(step / TWIPS_PER_PT)).append("pt")
                if (paraFill != null) out.append(";background:#").append(paraFill)
                out.append("\"><span class=\"mk\">").append(marker)
                    .append("</span><span class=\"lt\">").append(body).append("</span></div>")
            } else {
                val tag = tagFor(styleName, outline)
                out.append('<').append(tag)
                if (css.isNotEmpty()) out.append(" style=\"").append(css).append('"')
                out.append('>').append(body).append("</").append(tag).append('>')
            }
            resetPara()
        }

        var pageBg: String? = null
        var partial = false

        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (Xmls.local(xp)) {
                            "background" -> pageBg = Xmls.attr(xp, "color")?.takeIf { it.length == 6 }

                            // ---- paragraphs ----
                            "p" -> {
                                // A text box inside a paragraph starts a nested paragraph.
                                if (para.isNotEmpty()) flushPara()
                                resetPara()
                                inPPr = false
                            }
                            "pPr" -> inPPr = true
                            "pStyle" -> if (inPPr) styleName = Xmls.attr(xp, "val")
                            "outlineLvl" -> if (inPPr) outline = Xmls.attr(xp, "val")?.toIntOrNull() ?: -1
                            "jc" -> if (inPPr) align = cssAlign(Xmls.attr(xp, "val"))
                            "ind" -> if (inPPr) {
                                indentLeft = Xmls.attr(xp, "left")?.toIntOrNull() ?: 0
                                indentRight = Xmls.attr(xp, "right")?.toIntOrNull() ?: 0
                                firstLine = Xmls.attr(xp, "firstLine")?.toIntOrNull()
                                    ?: (Xmls.attr(xp, "hanging")?.toIntOrNull()?.let { -it } ?: 0)
                            }
                            "spacing" -> if (inPPr) {
                                spaceBefore = Xmls.attr(xp, "before")?.toIntOrNull() ?: 0
                                spaceAfter = Xmls.attr(xp, "after")?.toIntOrNull() ?: 0
                                val rule = Xmls.attr(xp, "lineRule")
                                if (rule == null || rule == "auto") {
                                    lineHeight = Xmls.attr(xp, "line")?.toIntOrNull() ?: 0
                                }
                            }
                            "shd" -> {
                                val fill = Xmls.attr(xp, "fill")
                                if (fill != null && fill.length == 6 && !fill.equals("auto", true)) {
                                    when {
                                        inPPr -> paraFill = fill
                                        inRPr -> runShade = fill
                                        inTcPr && cellFills.isNotEmpty() ->
                                            cellFills[cellFills.size - 1] = fill
                                    }
                                }
                            }
                            "tcPr" -> inTcPr = true
                            "numPr" -> if (inPPr) inNumPr = true
                            "ilvl" -> if (inNumPr) ilvl = Xmls.attr(xp, "val")?.toIntOrNull() ?: 0
                            "numId" -> if (inNumPr) numId = Xmls.attr(xp, "val")

                            // ---- runs ----
                            "r" -> {
                                inR = true
                                resetRun()
                                runText.setLength(0)
                            }
                            "rPr" -> if (inR) inRPr = true
                            "b" -> if (inRPr) bold = Xmls.isOn(xp)
                            "i" -> if (inRPr) italic = Xmls.isOn(xp)
                            "u" -> if (inRPr) {
                                underline = Xmls.attr(xp, "val")?.let { !it.equals("none", true) } ?: true
                            }
                            "strike", "dstrike" -> if (inRPr) strike = Xmls.isOn(xp)
                            "color" -> if (inRPr) {
                                val v = Xmls.attr(xp, "val")
                                color = if (v != null && v.length == 6 && !v.equals("auto", true)) v else null
                            }
                            "sz" -> if (inRPr) sizeHalf = Xmls.attr(xp, "val")?.toIntOrNull() ?: 0
                            "highlight" -> if (inRPr) highlight = Xmls.attr(xp, "val")
                            "vertAlign" -> if (inRPr) vertAlign = Xmls.attr(xp, "val")
                            "caps" -> if (inRPr) caps = Xmls.isOn(xp)
                            "t" -> if (inR) runText.append(Xmls.text(xp))
                            "tab" -> if (inR) {
                                flushRun()
                                para.append("<span class=\"tab\"></span>")
                            }
                            "br" -> if (inR) {
                                if (Xmls.attr(xp, "type") == "page") {
                                    flushPara()
                                    out.append("<div class=\"pgbrk\"></div>")
                                } else {
                                    flushRun()
                                    para.append("<br>")
                                }
                            }
                            "lastRenderedPageBreak" -> {
                                flushPara()
                                out.append("<div class=\"pgbrk\"></div>")
                            }
                            "cr" -> if (inR) {
                                flushRun()
                                para.append("<br>")
                            }

                            // ---- pictures ----
                            "drawing", "pict" -> imgCx = 0
                            "extent", "ext" -> if (imgCx == 0) {
                                imgCx = Xmls.attr(xp, "cx")?.toIntOrNull() ?: 0
                            }
                            "blip" -> emitImage(Xmls.attr(xp, "embed") ?: Xmls.attr(xp, "link"))
                            "imagedata" -> emitImage(Xmls.attr(xp, "id"))

                            // ---- tables ----
                            "tbl" -> {
                                flushPara()
                                out.append("<table class=\"doc\">")
                            }
                            "tr" -> {
                                flushPara()
                                out.append("<tr>")
                            }
                            "tc" -> {
                                flushPara()
                                out.append("<td>")
                                tdPositions.add(out.length - 4)
                                cellFills.add(null)
                                cellSpans.add(1)
                            }
                            "gridSpan" -> if (cellSpans.isNotEmpty()) {
                                cellSpans[cellSpans.size - 1] =
                                    Xmls.attr(xp, "val")?.toIntOrNull()?.coerceIn(1, 32) ?: 1
                            }
                        }

                        XmlPullParser.END_TAG -> when (Xmls.local(xp)) {
                            "p" -> flushPara()
                            "pPr" -> inPPr = false
                            "numPr" -> inNumPr = false
                            "r" -> {
                                inR = false
                                inRPr = false
                                flushRun()
                            }
                            "rPr" -> inRPr = false
                            "tcPr" -> inTcPr = false
                            "drawing", "pict" -> imgCx = 0
                            "tc" -> {
                                if (tdPositions.isNotEmpty()) {
                                    val at = tdPositions.removeAt(tdPositions.size - 1)
                                    val fill = cellFills.removeAt(cellFills.size - 1)
                                    val span = cellSpans.removeAt(cellSpans.size - 1)
                                    val attrs = StringBuilder()
                                    if (span > 1) attrs.append(" colspan=\"").append(span).append('"')
                                    if (fill != null) attrs.append(" style=\"background:#").append(fill).append('"')
                                    if (attrs.isNotEmpty()) out.insert(at + 3, attrs)
                                }
                                out.append("</td>")
                            }
                            "tr" -> out.append("</tr>")
                            "tbl" -> out.append("</table>")
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
            partial = true
        }
        flushPara()

        var body = out.toString()
        if (partial) body += Xmls.PARTIAL
        if (body.isEmpty()) body = "<p>This document has no text body.</p>"
        val bg = pageBg
        val open = if (bg != null) "<div class=\"docpage\" style=\"background:#$bg\">" else ""
        val close = if (bg != null) "</div>" else ""
        val content = open + body + close
        if (content.length <= maxChars) return listOf(content)
        // Long documents are cut between top-level blocks so one WebView never holds a
        // whole book at once.
        if (bg == null) return splitTopLevel(content, maxChars)
        // The page wrapper is a single top-level block, so cut the body and re-wrap each
        // piece; otherwise a page-coloured document could never be split.
        val inner = maxChars - open.length - close.length
        if (inner <= 0) return listOf(content)
        return splitTopLevel(body, inner).map { open + it + close }
    }

    /** Splits a rendered body into chunks of at most [maxChars], cutting only between
     * top-level blocks so no fragment is ever left unbalanced. */
    internal fun splitTopLevel(content: String, maxChars: Int): List<String> {
        val ends = ArrayList<Int>()
        val stack = ArrayList<String>()
        var i = 0
        while (i < content.length) {
            if (content[i] != '<') { i++; continue }
            if (content.startsWith("<!--", i)) {
                val end = content.indexOf("-->", i + 4)
                if (end < 0) break
                i = end + 3
                continue
            }
            val close = content.indexOf('>', i)
            if (close < 0) break
            val tag = content.substring(i, close + 1)
            i = close + 1
            if (tag.endsWith("/>")) continue
            val raw = tag.substring(1, tag.length - 1)
            // A closing tag still carries its slash; without stripping it here the name
            // would be "/p" and never match the "p" that was pushed.
            val isClose = raw.startsWith("/")
            val name = raw.trimStart('/').substringAfterLast(':').substringBefore(' ').trim().lowercase()
            if (name.isEmpty()) continue
            if (!isClose) {
                // Void elements never close; pushing one would stop the stack from ever
                // emptying again and silently turn the rest of the body into one chunk.
                if (isBlock(name) && name !in VOID) stack.add(name)
            } else {
                var idx = stack.lastIndex
                while (idx >= 0 && stack[idx] != name) idx--
                if (idx >= 0) stack.removeAt(idx)
                if (stack.isEmpty()) ends.add(close + 1)
            }
        }
        val chunks = ArrayList<String>()
        var start = 0
        fun addBlock(end: Int) {
            val block = content.substring(start, end)
            start = end
            val last = chunks.lastOrNull()
            // A single block can be longer than the cap when the markup cannot be cut
            // without unbalancing it; keeping its text beats dropping it.
            if (last != null && last.length + block.length <= maxChars) {
                chunks[chunks.size - 1] = last + block
            } else {
                chunks.add(block)
            }
        }
        for (end in ends) addBlock(end)
        if (start < content.length) addBlock(content.length)
        return if (chunks.isEmpty()) listOf(content) else chunks
    }

    /** True for block-level elements a rendered document is made of. */
    internal fun isBlock(name: String): Boolean = when (name) {
        "p", "div", "ul", "ol", "li", "table", "tr", "blockquote", "pre", "hr" -> true
        else -> name.length in 2..3 && name[0] == 'h'
    }

    private val VOID = setOf(
        "hr", "br", "img", "meta", "link", "input", "wbr",
        "source", "area", "base", "col", "embed", "param", "track",
    )

    // ---- helpers ----------------------------------------------------------------

    /** Word uses "both" for justified text; everything else is already a CSS keyword. */
    private fun cssAlign(v: String?): String? = when (v) {
        null -> null
        "both" -> "justify"
        "start" -> "left"
        "end" -> "right"
        else -> v
    }

    private fun tagFor(style: String?, outline: Int): String {
        if (outline in 0..3) return "h" + (outline + 1)
        val s = style?.lowercase() ?: return "p"
        return when {
            s.startsWith("title") -> "h1"
            s.startsWith("subtitle") -> "h3"
            s.startsWith("heading") -> {
                val n = s.removePrefix("heading").trim().toIntOrNull() ?: 4
                "h" + n.coerceIn(1, 4)
            }
            s.startsWith("quote") -> "blockquote"
            else -> "p"
        }
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.1f", v)

    // ---- numbering --------------------------------------------------------------

    /**
     * Just enough of word/numbering.xml to label a list item: bullet character, or a
     * counter in the format Word asked for (1., a., i., …).
     */
    private class Numbering(z: ZipFile) {

        private val abstractOf = HashMap<String, String>()
        private val fmt = HashMap<String, String>()
        private val pattern = HashMap<String, String>()
        private val counts = HashMap<String, Int>()

        init {
            parse(z)
        }

        private fun parse(z: ZipFile) {
            val entry = z.getEntry("word/numbering.xml") ?: return
            var abs = ""
            var lvl = -1
            var currentNum: String? = null
            try {
                z.getInputStream(entry).use { ins ->
                    val xp = Xmls.parser(ins)
                    var ev = xp.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (Xmls.local(xp)) {
                                "abstractNum" -> {
                                    abs = Xmls.attr(xp, "abstractNumId") ?: ""
                                    lvl = -1
                                }
                                "lvl" -> lvl = Xmls.attr(xp, "ilvl")?.toIntOrNull() ?: 0
                                "numFmt" -> if (abs.isNotEmpty() && lvl >= 0) {
                                    fmt["$abs:$lvl"] = Xmls.attr(xp, "val") ?: ""
                                }
                                "lvlText" -> if (abs.isNotEmpty() && lvl >= 0) {
                                    pattern["$abs:$lvl"] = Xmls.attr(xp, "val") ?: ""
                                }
                                "num" -> currentNum = Xmls.attr(xp, "numId")
                                "abstractNumId" -> {
                                    val id = currentNum
                                    val v = Xmls.attr(xp, "val")
                                    if (id != null && v != null) abstractOf[id] = v
                                }
                            }
                        } else if (ev == XmlPullParser.END_TAG && Xmls.local(xp) == "num") {
                            currentNum = null
                        }
                        ev = xp.next()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun marker(numId: String, ilvl: Int): String {
            val abs = abstractOf[numId]
            val format = abs?.let { fmt["$it:$ilvl"] }
            if (abs == null || format == null || format == "bullet" || format == "none") {
                return bullet(abs?.let { pattern["$it:$ilvl"] })
            }
            // A new top-level item restarts the sub-level counters for that list.
            if (ilvl == 0) {
                val it = counts.keys.iterator()
                while (it.hasNext()) if (it.next().startsWith("$numId:")) it.remove()
            }
            val key = "$numId:$ilvl"
            val n = (counts[key] ?: 0) + 1
            counts[key] = n
            val pat = pattern["$abs:$ilvl"] ?: "%${ilvl + 1}."
            return expand(pat, abs, numId, ilvl, n)
        }

        private fun expand(pat: String, abs: String, numId: String, ilvl: Int, n: Int): String {
            val sb = StringBuilder(pat.length + 4)
            var i = 0
            while (i < pat.length) {
                val c = pat[i]
                if (c == '%' && i + 1 < pat.length && pat[i + 1].isDigit()) {
                    val level = pat[i + 1] - '1'
                    val value = if (level == ilvl) n else (counts["$numId:$level"] ?: 1)
                    val f = fmt["$abs:$level"] ?: "decimal"
                    sb.append(formatNumber(f, value))
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun formatNumber(f: String, n: Int): String = when (f) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            else -> n.toString()
        }

        private fun letters(n: Int): String =
            if (n <= 0) "" else letters((n - 1) / 26) + ('A' + (n - 1) % 26)

        private fun roman(n: Int): String {
            if (n <= 0 || n > 3999) return n.toString()
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            var v = n
            for (i in vals.indices) {
                while (v >= vals[i]) {
                    sb.append(syms[i])
                    v -= vals[i]
                }
            }
            return sb.toString()
        }

        /** Bullet levels are stored as Symbol/Wingdings private-use characters. */
        private fun bullet(pat: String?): String {
            if (pat.isNullOrEmpty()) return "\u2022"
            val c = pat[0]
            return when {
                c == '\uF0A8' -> "\u25AA"
                c == '\uF0D8' -> "\u25B8"
                c == '\uF0FC' -> "\u2713"
                c.code in 0xE000..0xF8FF -> "\u2022"
                c.code < 0x20 -> "\u2022"
                else -> c.toString()
            }
        }
    }
}
