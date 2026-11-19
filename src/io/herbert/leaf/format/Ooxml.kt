package io.herbert.leaf.format

import io.herbert.leaf.core.Html
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * OOXML spreadsheet reader. Worksheets become real grids that look like the sheet does
 * in Excel: column letters, row numbers, frozen headers, bold cells and column widths
 * taken from the file. Word lives in Docx.kt, PowerPoint in Pptx.kt.
 */
object Ooxml {

    // ---- Excel ------------------------------------------------------------------

    private class Cell(val text: String, val numeric: Boolean, val bold: Boolean)

    private class SheetRef(val name: String, val path: String)

    fun xlsx(z: ZipFile): List<Chapter> {
        val shared = sharedStrings(z)
        val bold = boldStyles(z)
        val sheets = sheetRefs(z)
        if (sheets.isEmpty()) return listOf(Chapter("Sheet", "<p>This workbook has no readable sheets.</p>"))
        return sheets.map { Chapter(it.name, sheet(z, it.path, shared, bold)) }
    }

    /** Shared string table: every <si> (including rich-text runs) becomes one string. */
    private fun sharedStrings(z: ZipFile): List<String> {
        val entry = z.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val list = ArrayList<String>()
        val cur = StringBuilder()
        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (Xmls.local(xp)) {
                            "si" -> cur.setLength(0)
                            "t" -> cur.append(Xmls.text(xp))
                        }
                        XmlPullParser.END_TAG -> if (Xmls.local(xp) == "si") list.add(cur.toString())
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    /** Which cellXfs indexes are bold, from xl/styles.xml. Empty when the file has none. */
    private fun boldStyles(z: ZipFile): BooleanArray {
        val entry = z.getEntry("xl/styles.xml") ?: return BooleanArray(0)
        val fonts = ArrayList<Boolean>()
        val xfs = ArrayList<Int>()
        var section = ""
        var curBold = false
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
                            "fonts", "fills", "borders", "cellStyleXfs", "cellXfs",
                            "numFmts", "cellStyles", "dxfs", "tableStyles" -> section = name
                            "font" -> if (section == "fonts") curBold = false
                            "b" -> if (section == "fonts") curBold = true
                            "xf" -> if (section == "cellXfs") {
                                xfs.add(Xmls.attr(xp, "fontId")?.toIntOrNull() ?: 0)
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when {
                            name == "font" && section == "fonts" -> fonts.add(curBold)
                            name == section -> section = ""
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        return BooleanArray(xfs.size) { i -> fonts.getOrElse(xfs[i]) { false } }
    }

    /** Sheet names and their real part paths, via workbook.xml + its relationships. */
    private fun sheetRefs(z: ZipFile): List<SheetRef> {
        val wb = z.getEntry("xl/workbook.xml") ?: return emptyList()

        val rels = HashMap<String, String>()
        val relEntry = z.getEntry("xl/_rels/workbook.xml.rels")
        if (relEntry != null) {
            try {
                z.getInputStream(relEntry).use { ins ->
                    val xp = Xmls.parser(ins)
                    var ev = xp.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && Xmls.local(xp) == "Relationship") {
                            val id = Xmls.attr(xp, "Id")
                            val target = Xmls.attr(xp, "Target")
                            if (id != null && target != null) rels[id] = target
                        }
                        ev = xp.next()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val out = ArrayList<SheetRef>()
        try {
            z.getInputStream(wb).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && Xmls.local(xp) == "sheet") {
                        val name = Xmls.attr(xp, "name") ?: "Sheet ${out.size + 1}"
                        val target = Xmls.attr(xp, "id")?.let { rels[it] }
                        val path = target?.let { resolveXl(it) }
                        if (path != null && z.getEntry(path) != null) out.add(SheetRef(name, path))
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        if (out.isNotEmpty()) return out

        // Broken/missing workbook relationships: fall back to the usual file names.
        var i = 1
        while (i <= 64) {
            val p = "xl/worksheets/sheet$i.xml"
            if (z.getEntry(p) == null) break
            out.add(SheetRef("Sheet $i", p))
            i++
        }
        return out
    }

    /** Resolves a relationship target ("worksheets/sheet1.xml", "/xl/…", "../…"). */
    private fun resolveXl(target: String): String? {
        var t = target.replace('\\', '/')
        val absolute = t.startsWith("/")
        t = t.removePrefix("/")
        val parts = ArrayList<String>()
        if (!absolute && !t.startsWith("xl/")) parts.add("xl")
        for (seg in t.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun sheet(z: ZipFile, path: String, shared: List<String>, bold: BooleanArray): String {
        val entry = z.getEntry(path) ?: return "<p><i>Empty sheet</i></p>"
        val rows = LinkedHashMap<Int, LinkedHashMap<Int, Cell>>()
        val widths = HashMap<Int, Int>()
        var maxCol = -1
        var cellCount = 0
        var rowNo = 0
        var col = 0
        var type: String? = null
        var style = -1
        var value: String? = null
        var partial = false

        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT && rows.size < MAX_ROWS && cellCount < MAX_CELLS) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (Xmls.local(xp)) {
                            "col" -> {
                                val min = Xmls.attr(xp, "min")?.toIntOrNull() ?: 0
                                val max = Xmls.attr(xp, "max")?.toIntOrNull() ?: min
                                val w = Xmls.attr(xp, "width")?.toDoubleOrNull() ?: 0.0
                                if (min in 1..MAX_COLS && w > 0) {
                                    val n = w.roundToInt().coerceIn(4, 48)
                                    for (c in min..minOf(max, min + 8)) widths[c - 1] = n
                                }
                            }
                            "row" -> {
                                rowNo = Xmls.attr(xp, "r")?.toIntOrNull() ?: (rowNo + 1)
                                col = -1
                            }
                            "c" -> {
                                type = Xmls.attr(xp, "t")
                                style = Xmls.attr(xp, "s")?.toIntOrNull() ?: -1
                                col = Xmls.attr(xp, "r")?.let { colOf(it) } ?: (col + 1)
                                value = null
                            }
                            "v" -> value = Xmls.text(xp)
                            "t" -> if (type == "inlineStr") value = Xmls.text(xp)
                        }
                        XmlPullParser.END_TAG -> when (Xmls.local(xp)) {
                            "c" -> {
                                val raw = value
                                if (!raw.isNullOrEmpty() && rowNo > 0 && col in 0 until MAX_COLS) {
                                    val text = when (type) {
                                        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                                        else -> raw
                                    }
                                    if (text.isNotEmpty()) {
                                        val numeric = type == null || type == "n" || type == "b"
                                        val isBold = style >= 0 && style < bold.size && bold[style]
                                        rows.getOrPut(rowNo) { LinkedHashMap() }[col] =
                                            Cell(text, numeric && isNumber(text), isBold)
                                        if (col > maxCol) maxCol = col
                                        cellCount++
                                    }
                                }
                                value = null
                            }
                            "row" -> col = -1
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
            partial = true
        }

        if (rows.isEmpty() || maxCol < 0) {
            return "<p><i>Empty sheet</i></p>" + if (partial) Xmls.PARTIAL else ""
        }

        // Excel-like auto-fit: honour the file's column widths, widen for long content.
        val lengths = IntArray(maxCol + 1)
        for (line in rows.values) {
            for ((c, cell) in line) {
                if (c <= maxCol && cell.text.length > lengths[c]) lengths[c] = cell.text.length
            }
        }

        val rowDigits = rows.keys.max().toString().length
        val sb = StringBuilder(128 + cellCount * 28)
        sb.append("<table class=\"grid\"><colgroup><col style=\"width:")
            .append(rowDigits + 3).append("ch\">")
        for (c in 0..maxCol) {
            val w = maxOf(widths[c] ?: 0, lengths[c] + 2).coerceIn(7, 46)
            sb.append("<col style=\"width:").append(w).append("ch\">")
        }
        sb.append("</colgroup><thead><tr><th class=\"corner\"></th>")
        for (c in 0..maxCol) sb.append("<th>").append(colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")

        // Rows are sparse in the file; render the blank rows between them too so row
        // 1 and row 10 do not appear as neighbours. A stray cell a million rows down
        // must not turn into a million empty rows, though, so a huge gap collapses into
        // one explanatory row.
        var nextRow = 1
        for ((r, line) in rows) {
            val gap = r - nextRow
            if (gap > MAX_FILLER_ROWS) {
                sb.append("<tr><th>\u2026</th><td colspan=\"").append(maxCol + 1)
                    .append("\">").append(gap)
                    .append(" empty rows not shown</td></tr>")
                nextRow = r
            }
            while (nextRow < r) {
                sb.append("<tr><th>").append(nextRow).append("</th><td colspan=\"")
                    .append(maxCol + 1).append("\"></td></tr>")
                nextRow++
            }
            nextRow = r + 1
            val last = minOf(line.keys.max(), maxCol)
            sb.append("<tr><th>").append(r).append("</th>")
            for (c in 0..last) {
                val cell = line[c]
                if (cell == null) {
                    sb.append("<td></td>")
                    continue
                }
                sb.append("<td")
                val cls = when {
                    cell.numeric && cell.bold -> " class=\"n b\""
                    cell.numeric -> " class=\"n\""
                    cell.bold -> " class=\"b\""
                    else -> ""
                }
                sb.append(cls).append('>').append(Html.esc(cell.text)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (rows.size >= MAX_ROWS || cellCount >= MAX_CELLS) {
            sb.append("<p class=\"note\">This sheet is very large; Leaf shows the first ")
                .append(rows.size).append(" rows.</p>")
        }
        if (partial) sb.append(Xmls.PARTIAL)
        return sb.toString()
    }

    private fun isNumber(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        // Excel numbers never carry thousands separators in the stored value, but be lenient.
        val clean = if (t.count { it == ',' } == 1 && ',' in t && '.' !in t) t.replace(",", ".") else t
        return clean.toDoubleOrNull() != null
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    private fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder(3)
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** "AB12" -> 27 (zero based). */
    private fun colOf(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    private const val MAX_ROWS = 3000
    private const val MAX_COLS = 100
    private const val MAX_CELLS = 40000

    /** Longest run of blank rows drawn literally before the gap is summarised. */
    private const val MAX_FILLER_ROWS = 1000
}
