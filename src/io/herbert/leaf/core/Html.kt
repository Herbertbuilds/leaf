package io.herbert.leaf.core

/** HTML helpers: escaping, body extraction, tag stripping and the reader stylesheet. */
object Html {

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        // Quotes matter in attribute contexts (Markdown links, image sources); in text
        // they render as the same characters.
        .replace("\"", "&quot;")

    fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "\u2014")
        .replace("&ndash;", "\u2013")
        .replace("&hellip;", "\u2026")
        .replace("&amp;", "&")

    /** Returns the inside of <body>, with scripts and styles removed. */
    fun bodyOf(src: String): String {
        var s = src
        s = s.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
        s = s.replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
        s = s.replace(Regex("(?is)<!doctype[^>]*>"), "")
        val m = Regex("(?is)<body\\b[^>]*>(.*?)</body>").find(s)
        if (m != null) return m.groupValues[1]
        return s
    }

    /** Crude but predictable HTML to plain text, used for MOBI and unknown markup. */
    fun stripTags(src: String): String {
        var s = src
        s = s.replace(Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|pre)>"), "\n")
        s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\u2022 ")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        s = s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Escapes text for HTML, keeping runs of spaces visible the way word processors show them. */
    fun spaced(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length + 16)
        for (i in s.indices) {
            when (val c = s[i]) {
                ' ' -> sb.append(
                    if (i == 0 || i == s.length - 1 || s[i - 1] == ' ') "&nbsp;" else " "
                )
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Wraps a rendered body in Leaf's stylesheet.
     *
     * @param paper force the white-page look (Word documents, PDFs).
     * @param cls   body class: "doc" for word-processor pages, "deck" for slides.
     */
    fun page(
        body: String,
        dark: Boolean,
        fontPx: Int,
        paper: Boolean = false,
        cls: String = "",
    ): String {
        // `paper` forces the light, pure-white look used for Word-style documents, the
        // way a PDF page looks, whatever the app theme is. `cls` tags the body so a
        // document or a deck can bring its own layout rules.
        val bg = if (paper) "#FFFFFF" else if (dark) "#0F1214" else "#FBFAF7"
        val fg = if (paper) "#141414" else if (dark) "#E8EAED" else "#1B1B1B"
        val dim = if (paper) "#5F6368" else if (dark) "#9AA0A6" else "#5F6368"
        val accent = if (paper) "#0F7B5F" else if (dark) "#66D9A8" else "#0F7B5F"
        val rule = if (paper) "#E3E3E3" else if (dark) "#272C30" else "#E6E3DC"
        val soft = if (paper) "#F4F4F4" else if (dark) "#181D20" else "#F1EFEA"

        return buildString(1024 + body.length) {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=4\">")
            append("<style>")
            append("html,body{margin:0;padding:0;background:").append(bg).append(";color:").append(fg).append(";}")
            append("body{padding:14px 18px 56px;font-size:").append(fontPx).append("px;line-height:1.55;")
            append("font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;")
            append("-webkit-text-size-adjust:100%;overflow-wrap:break-word;}")
            append("h1,h2,h3,h4{line-height:1.25;margin:1.15em 0 .45em;font-weight:600}")
            append("h1{font-size:1.55em}h2{font-size:1.28em}h3{font-size:1.12em}h4{font-size:1em}")
            append("p{margin:0 0 .85em}img{max-width:100%;height:auto;border-radius:4px}")
            append("a{color:").append(accent).append(";text-decoration:none}")
            append("pre,code{font-family:ui-monospace,'Roboto Mono',monospace;font-size:.9em}")
            append("pre{background:").append(soft).append(";padding:10px 12px;border-radius:8px;white-space:pre-wrap}")
            append("blockquote{margin:0 0 .9em;padding-left:12px;border-left:3px solid ").append(rule)
            append(";color:").append(dim).append("}")
            append("hr{border:0;border-top:1px solid ").append(rule).append(";margin:1.3em 0}")
            append("table{border-collapse:collapse;font-size:.92em;margin:0 0 1em;display:block;overflow-x:auto}")
            append("td,th{border:1px solid ").append(rule).append(";padding:4px 8px;vertical-align:top}")
            append("th{background:").append(soft).append(";text-align:left;font-weight:600}")

            // Spreadsheet grids: column letters, row numbers, frozen headers, real widths.
            // The page itself is the scroller (no overflow wrapper) so `position: sticky`
            // works for both the header row and the row-number column.
            append("table.grid{display:table;border-collapse:separate;border-spacing:0;table-layout:fixed;")
            append("margin:0 -18px;font-size:12.5px;line-height:1.35;overflow:visible;min-width:100%;")
            append("border-top:1px solid ").append(rule).append(";border-left:1px solid ").append(rule).append('}')
            append("table.grid th,table.grid td{border:0;border-right:1px solid ").append(rule)
            append(";border-bottom:1px solid ").append(rule)
            append(";padding:3px 7px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-weight:400;vertical-align:middle}")
            append("table.grid thead th{background:").append(soft).append(";color:").append(dim)
            append(";text-align:center;font-weight:600;position:sticky;top:0;z-index:2}")
            append("table.grid tbody th{background:").append(soft).append(";color:").append(dim)
            append(";text-align:right;position:sticky;left:0;z-index:1;font-variant-numeric:tabular-nums}")
            append("table.grid thead th.corner{left:0;z-index:3}")
            append("table.grid td.n{text-align:right;font-variant-numeric:tabular-nums}")
            append("table.grid td.b{font-weight:600}")
            append(".note{color:").append(dim).append(";font-size:.85em;margin:10px 18px 0}")

            // Word-style documents: pictures at their real size, bordered tables, real
            // list markers, page breaks, tab stops and the page's own background.
            append("body.doc{padding:20px 18px 56px}")
            append("body.doc img.docimg{display:block;max-width:100%;height:auto;margin:.25em 0;border-radius:2px}")
            append("body.doc table.doc{border-collapse:collapse;width:100%;margin:.7em 0;font-size:.95em}")
            append("body.doc table.doc td{border:1px solid #C6C6C6;padding:5px 8px;vertical-align:top}")
            append("body.doc .li{display:flex;align-items:baseline;margin:0 0 .3em}")
            append("body.doc .li .mk{flex:0 0 auto;min-width:1.5em;color:#5F6368}")
            append("body.doc .li .lt{flex:1 1 auto;min-width:0}")
            append("body.doc .pgbrk{border-top:1px dashed #DCDCDC;margin:26px 0 22px}")
            append("body.doc .tab{display:inline-block;width:1.8em}")
            append("body.doc .caps{text-transform:uppercase}")
            append("body.doc .docpage{margin:-20px -18px 0;padding:20px 18px}")
            append("body.doc blockquote{margin:0 0 .9em;padding-left:12px;border-left:3px solid #DADADA;color:#4A4A4A}")

            // Slide decks: each slide is a white canvas with the presentation's aspect
            // ratio, and every shape is absolutely positioned in percentages of it, so
            // pictures and layout survive at any screen width.
            append("body.deck{padding:12px 12px 40px;background:#EDEDED}")
            append(".slide{position:relative;width:100%;height:0;margin:0 0 14px;overflow:hidden;")
            append("background:#FFFFFF;box-shadow:0 1px 3px rgba(0,0,0,.22);border-radius:2px}")
            append(".slide .sl{position:absolute;left:0;top:0;right:0;bottom:0}")
            append(".slide .sh{position:absolute;overflow:hidden}")
            append(".slide .sh img{display:block;width:100%;height:100%;object-fit:contain}")
            append(".slide .tx{position:absolute;left:0;top:0;right:0;bottom:0;")
            append("padding:1.5% 2%;overflow:hidden;line-height:1.25;color:#141414;")
            append("font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}")
            append(".slide .pp{margin:0 0 .3em}")
            append("</style></head><body").append(if (cls.isEmpty()) "" else " class=\"$cls\"")
                .append('>').append(body).append("</body></html>")
        }
    }
}
