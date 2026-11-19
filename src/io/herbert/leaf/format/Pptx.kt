package io.herbert.leaf.format

import io.herbert.leaf.core.Html
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * PowerPoint. Each slide becomes a scaled canvas with absolutely positioned boxes, so
 * pictures, background fills, shape fills and text placement survive the trip. Text
 * sizes are expressed in `vw`, which keeps them proportional to the slide, and the
 * reader allows pinch zoom for decks with small type.
 *
 * Not rendered: charts, tables, SmartArt, theme colours, gradients, shadows and picture
 * crops. Those are reported as blank areas rather than as wrong content.
 */
object Pptx {

    private const val EMU_PER_PT = 12700.0

    /** Slides per chapter in the reader, chosen to keep decoded pictures bounded. */
    private const val SLIDES_PER_CHAPTER = 10

    private class Xfrm(val x: Int, val y: Int, val cx: Int, val cy: Int)

    /**
     * A group's frame: [offX]/[offY] and [extX]/[extY] are where it lands on the slide,
     * while [chOffX]..[chExtY] describe the child coordinate space it maps from.
     */
    private class Group(
        val offX: Int,
        val offY: Int,
        val extX: Int,
        val extY: Int,
        val chOffX: Int,
        val chOffY: Int,
        val chExtX: Int,
        val chExtY: Int,
    )

    private class Placeholders {
        val byIdx = HashMap<Int, Xfrm>()
        val byType = HashMap<String, Xfrm>()

        fun find(idx: Int?, type: String?): Xfrm? =
            idx?.let { byIdx[it] } ?: type?.let { byType[it] }
    }

    fun load(z: ZipFile, mediaDir: File): List<Chapter> {
        val (sw, sh) = slideSize(z)
        val slides = z.entries().toList()
            .filter { !it.isDirectory && Regex("^ppt/slides/slide\\d+\\.xml$").matches(it.name) }
            .sortedBy { Regex("(\\d+)").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (slides.isEmpty()) {
            return listOf(Chapter("Slides", "<p>This presentation has no readable slides.</p>"))
        }
        mediaDir.mkdirs()
        val bodies = slides.map { slide(z, it.name, sw, sh, mediaDir) }
        if (bodies.size <= SLIDES_PER_CHAPTER) {
            return listOf(Chapter("Slides", bodies.joinToString("")))
        }
        // Long decks are paged in groups so the WebView never holds the whole thing (and
        // every picture in it) in memory at once.
        return bodies.chunked(SLIDES_PER_CHAPTER).mapIndexed { i, chunk ->
            val first = i * SLIDES_PER_CHAPTER + 1
            val last = first + chunk.size - 1
            Chapter("Slides $first\u2013$last", chunk.joinToString(""))
        }
    }

    private fun slideSize(z: ZipFile): Pair<Int, Int> {
        val entry = z.getEntry("ppt/presentation.xml") ?: return 12192000 to 6858000
        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && Xmls.local(xp) == "sldSz") {
                        val w = Xmls.attr(xp, "cx")?.toIntOrNull() ?: 0
                        val h = Xmls.attr(xp, "cy")?.toIntOrNull() ?: 0
                        if (w > 0 && h > 0) return w to h
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        return 12192000 to 6858000
    }

    private fun slide(z: ZipFile, path: String, sw: Int, sh: Int, mediaDir: File): String {
        val baseDir = path.substringBeforeLast('/')
        val part = path.substringAfterLast('/')
        val links = Parts.rels(z, "$baseDir/_rels/$part.rels")

        val images = HashMap<String, String>()
        for ((id, rel) in links) {
            if (!rel.type.endsWith("/image")) continue
            val target = Parts.resolve(baseDir, rel.target) ?: continue
            Parts.extract(z, target, mediaDir)?.let { images[id] = it }
        }
        val layout = links.values.firstOrNull { it.type.endsWith("/slideLayout") }
            ?.let { Parts.resolve(baseDir, it.target) }
        val placeholders = placeholders(z, layout)

        val out = StringBuilder()
        val groups = ArrayList<Group>()
        val pushed = ArrayList<Boolean>()
        val text = StringBuilder()
        val para = StringBuilder()

        var bg: String? = null
        var x = 0
        var y = 0
        var cx = 0
        var cy = 0
        var framed = false
        var fill: String? = null
        var img: String? = null
        var phIdx: Int? = null
        var phType: String? = null
        var inSpPr = false
        var inTxBody = false
        var inRPr = false
        var inBg = false
        var inGrpPr = false
        var gOffX = 0
        var gOffY = 0
        var gExtX = 0
        var gExtY = 0
        var gChOffX = 0
        var gChOffY = 0
        var gChExtX = 0
        var gChExtY = 0
        var runSize = 0
        var runBold = false
        var runItalic = false
        var runColor: String? = null
        var align: String? = null
        var partial = false

        fun resetShape() {
            text.setLength(0)
            para.setLength(0)
            x = 0; y = 0; cx = 0; cy = 0
            framed = false
            fill = null
            img = null
            phIdx = null
            phType = null
            runSize = 0; runBold = false; runItalic = false
            runColor = null
            align = null
        }

        fun vw(pt: Double): Double = pt / (sw / EMU_PER_PT) * 100.0

        fun num(v: Double): String = String.format(Locale.US, "%.2f", v)

        // Local functions cannot be referenced before their declaration, so the
        // transforms come first and the thin wrappers after.
        /** Child space -> slide space. Groups are pushed outermost first, so walk back in. */
        fun mapPoint(v: Int, horizontal: Boolean): Double {
            var acc = v.toDouble()
            for (i in groups.indices.reversed()) {
                val g = groups[i]
                val chOff = if (horizontal) g.chOffX else g.chOffY
                val chExt = if (horizontal) g.chExtX else g.chExtY
                val ext = if (horizontal) g.extX else g.extY
                val off = if (horizontal) g.offX else g.offY
                acc = if (chExt != 0) off + (acc - chOff) * (ext.toDouble() / chExt) else acc + off
            }
            return acc
        }

        fun mapSpan(v: Int, horizontal: Boolean): Double {
            var acc = v.toDouble()
            for (i in groups.indices.reversed()) {
                val g = groups[i]
                val chExt = if (horizontal) g.chExtX else g.chExtY
                val ext = if (horizontal) g.extX else g.extY
                if (chExt != 0) acc *= ext.toDouble() / chExt
            }
            return acc
        }

        fun pointX(v: Int): Double = mapPoint(v, true)
        fun pointY(v: Int): Double = mapPoint(v, false)
        fun spanX(v: Int): Double = mapSpan(v, true)
        fun spanY(v: Int): Double = mapSpan(v, false)

        fun emit() {
            if (!framed) {
                val f = placeholders.find(phIdx, phType)
                if (f == null) {
                    resetShape()
                    return
                }
                x = f.x; y = f.y; cx = f.cx; cy = f.cy
            }
            if (cx <= 0 || cy <= 0) {
                resetShape()
                return
            }

            val leftPct = pointX(x) / sw * 100.0
            val topPct = pointY(y) / sh * 100.0
            val wPct = spanX(cx) / sw * 100.0
            val hPct = spanY(cy) / sh * 100.0

            out.append("<div class=\"sh\" style=\"left:").append(num(leftPct))
            out.append("%;top:").append(num(topPct))
            out.append("%;width:").append(num(wPct))
            out.append("%;height:").append(num(hPct)).append('%')
            if (fill != null) out.append(";background:#").append(fill)
            out.append("\">")
            if (img != null) out.append("<img src=\"").append(img).append("\" alt=\"\">")
            if (text.isNotEmpty()) {
                out.append("<div class=\"tx\" style=\"font-size:").append(num(vw(18.0))).append("vw")
                val back = fill ?: bg
                if (back != null && isDark(back)) out.append(";color:#FFFFFF")
                out.append("\">").append(text).append("</div>")
            }
            out.append("</div>")
            resetShape()
        }

        try {
            z.getInputStream(z.getEntry(path) ?: return "<p><i>Missing slide</i></p>").use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    val name = when (ev) {
                        XmlPullParser.START_TAG, XmlPullParser.END_TAG -> Xmls.local(xp)
                        else -> ""
                    }
                    when (ev) {
                        XmlPullParser.START_TAG -> when (name) {
                            "bg" -> inBg = true
                            "grpSp" -> pushed.add(false)
                            "sp", "pic" -> resetShape()
                            "spPr" -> inSpPr = true
                            "grpSpPr" -> {
                                inGrpPr = true
                                gOffX = 0; gOffY = 0; gExtX = 0; gExtY = 0
                                gChOffX = 0; gChOffY = 0; gChExtX = 0; gChExtY = 0
                            }
                            "txBody" -> inTxBody = true
                            "ph" -> {
                                phIdx = Xmls.attr(xp, "idx")?.toIntOrNull()
                                phType = Xmls.attr(xp, "type")
                            }
                            "off" -> {
                                val ox = Xmls.attr(xp, "x")?.toIntOrNull() ?: 0
                                val oy = Xmls.attr(xp, "y")?.toIntOrNull() ?: 0
                                if (inGrpPr) {
                                    gOffX = ox; gOffY = oy
                                } else if (inSpPr) {
                                    x = ox; y = oy
                                    framed = true
                                }
                            }
                            "ext" -> {
                                val ex = Xmls.attr(xp, "cx")?.toIntOrNull() ?: 0
                                val ey = Xmls.attr(xp, "cy")?.toIntOrNull() ?: 0
                                if (inGrpPr) {
                                    gExtX = ex; gExtY = ey
                                } else if (inSpPr) {
                                    cx = ex; cy = ey
                                }
                            }
                            "chOff" -> if (inGrpPr) {
                                gChOffX = Xmls.attr(xp, "x")?.toIntOrNull() ?: 0
                                gChOffY = Xmls.attr(xp, "y")?.toIntOrNull() ?: 0
                            }
                            "chExt" -> if (inGrpPr) {
                                gChExtX = Xmls.attr(xp, "cx")?.toIntOrNull() ?: 0
                                gChExtY = Xmls.attr(xp, "cy")?.toIntOrNull() ?: 0
                            }
                            "srgbClr" -> {
                                val v = Xmls.attr(xp, "val")?.takeIf { it.length == 6 }
                                if (v != null) {
                                    when {
                                        inRPr -> runColor = v
                                        inSpPr -> fill = v
                                        inBg -> bg = v
                                    }
                                }
                            }
                            "blip" -> img = Xmls.attr(xp, "embed")?.let { images[it] }
                            "pPr" -> align = Xmls.attr(xp, "algn")
                            "p" -> if (inTxBody) {
                                para.setLength(0)
                                align = null
                            }
                            "r" -> if (inTxBody) {
                                runSize = 0; runBold = false; runItalic = false; runColor = null
                            }
                            "rPr" -> if (inTxBody) {
                                inRPr = true
                                val b = Xmls.attr(xp, "b")
                                if (b != null) runBold = b == "1" || b.equals("true", true)
                                val i = Xmls.attr(xp, "i")
                                if (i != null) runItalic = i == "1" || i.equals("true", true)
                                val sz = Xmls.attr(xp, "sz")?.toIntOrNull()
                                if (sz != null && sz > 0) runSize = sz
                            }
                            "br" -> if (inTxBody) para.append("<br>")
                            "t" -> if (inTxBody) {
                                val raw = Xmls.text(xp)
                                if (raw.isNotEmpty()) {
                                    val style = StringBuilder()
                                    if (runSize > 0) {
                                        style.append("font-size:").append(num(vw(runSize / 100.0))).append("vw;")
                                    }
                                    if (runColor != null) style.append("color:#").append(runColor).append(';')
                                    if (runBold) style.append("font-weight:600;")
                                    if (runItalic) style.append("font-style:italic;")
                                    val escaped = Html.esc(raw)
                                    if (style.isEmpty()) {
                                        para.append(escaped)
                                    } else {
                                        para.append("<span style=\"").append(style).append("\">")
                                            .append(escaped).append("</span>")
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> when (name) {
                            "bg" -> inBg = false
                            "spPr" -> inSpPr = false
                            "grpSpPr" -> {
                                inGrpPr = false
                                val valid = gChExtX > 0 && gChExtY > 0 && gExtX > 0 && gExtY > 0
                                // One entry per open grpSp (pushed when the group starts), so
                                // a group without a grpSpPr cannot shift the others.
                                if (pushed.isNotEmpty()) pushed[pushed.size - 1] = valid
                                if (valid) groups.add(
                                    Group(gOffX, gOffY, gExtX, gExtY, gChOffX, gChOffY, gChExtX, gChExtY)
                                )
                            }
                            "grpSp" -> {
                                val had = if (pushed.isEmpty()) false else pushed.removeAt(pushed.size - 1)
                                if (had && groups.isNotEmpty()) groups.removeAt(groups.size - 1)
                            }
                            "txBody" -> inTxBody = false
                            "rPr" -> inRPr = false
                            "p" -> if (inTxBody) {
                                val body = para.toString()
                                if (body.isNotEmpty()) {
                                    val dir = align
                                    text.append("<div class=\"pp\"")
                                    if (dir != null) text.append(" style=\"text-align:").append(cssAlign(dir)).append('"')
                                    text.append('>').append(body).append("</div>")
                                }
                                para.setLength(0)
                            }
                            "sp", "pic" -> emit()
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
            partial = true
        }

        val padding = String.format(Locale.US, "%.2f", sh.toDouble() / sw * 100.0)
        val background = bg?.let { ";background:#$it" } ?: ";background:#FFFFFF"
        val note = if (partial) Xmls.PARTIAL else ""
        return note + "<div class=\"slide\" style=\"padding-top:$padding%$background\">" +
            "<div class=\"sl\">" + out + "</div></div>"
    }

    // ---- package plumbing -------------------------------------------------------

    /** Layout inherits from the master; parse the master first so the layout wins. */
    private fun placeholders(z: ZipFile, layout: String?): Placeholders {
        val out = Placeholders()
        if (layout == null) return out
        val layoutDir = layout.substringBeforeLast('/')
        val master = Parts.rels(z, "$layoutDir/_rels/${layout.substringAfterLast('/')}.rels")
            .values.firstOrNull { it.type.endsWith("/slideMaster") }
            ?.let { Parts.resolve(layoutDir, it.target) }
        if (master != null) collectPlaceholders(z, master, out)
        collectPlaceholders(z, layout, out)
        return out
    }

    private fun collectPlaceholders(z: ZipFile, path: String, into: Placeholders) {
        val entry = z.getEntry(path) ?: return
        var idx: Int? = null
        var type: String? = null
        var x = 0
        var y = 0
        var cx = 0
        var cy = 0
        var framed = false
        var inSpPr = false
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
                        when (name) {
                            "sp" -> {
                                idx = null; type = null; framed = false
                                x = 0; y = 0; cx = 0; cy = 0
                            }
                            "spPr" -> inSpPr = true
                            "ph" -> {
                                idx = Xmls.attr(xp, "idx")?.toIntOrNull()
                                type = Xmls.attr(xp, "type")
                            }
                            "off" -> if (inSpPr) {
                                x = Xmls.attr(xp, "x")?.toIntOrNull() ?: 0
                                y = Xmls.attr(xp, "y")?.toIntOrNull() ?: 0
                                framed = true
                            }
                            "ext" -> if (inSpPr) {
                                cx = Xmls.attr(xp, "cx")?.toIntOrNull() ?: 0
                                cy = Xmls.attr(xp, "cy")?.toIntOrNull() ?: 0
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (name) {
                            "spPr" -> inSpPr = false
                            "sp" -> if (framed && cx > 0 && cy > 0) {
                                val f = Xfrm(x, y, cx, cy)
                                if (idx != null && idx!! > 0) into.byIdx[idx!!] = f
                                if (type != null) into.byType[type!!] = f
                            }
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun cssAlign(algn: String): String = when (algn) {
        "ctr" -> "center"
        "r" -> "right"
        "just" -> "justify"
        else -> "left"
    }

    private fun isDark(hex: String): Boolean {
        val v = hex.toIntOrNull(16) ?: return false
        val r = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val b = v and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b < 140
    }
}
