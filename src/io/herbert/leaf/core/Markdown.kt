package io.herbert.leaf.core

/**
 * A very small Markdown to HTML converter: headings, emphasis, code, lists, quotes,
 * rules, links and images. No dependency, no clever extensions, no surprises.
 */
object Markdown {

    fun toHtml(src: String): String {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(src.length + src.length / 2 + 64)
        val para = StringBuilder()
        var list = 0

        fun closeList() {
            if (list == 1) out.append("</ul>")
            else if (list == 2) out.append("</ol>")
            list = 0
        }

        fun flush() {
            if (para.isNotBlank()) {
                closeList()
                out.append("<p>").append(inline(para.toString())).append("</p>")
            }
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val t = line.trimStart()

            if (t.startsWith("```")) {
                flush(); closeList()
                i++
                out.append("<pre><code>")
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    out.append(Html.esc(lines[i])).append('\n')
                    i++
                }
                i++
                out.append("</code></pre>")
                continue
            }
            if (t.isEmpty()) { flush(); closeList(); i++; continue }

            if (t.startsWith("#")) {
                flush(); closeList()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val body = t.substring(level).trim()
                out.append("<h").append(level).append('>')
                    .append(inline(body))
                    .append("</h").append(level).append('>')
                i++; continue
            }
            if (t.startsWith("> ")) {
                flush(); closeList()
                out.append("<blockquote>").append(inline(t.substring(2))).append("</blockquote>")
                i++; continue
            }
            if (t == "---" || t == "***" || t == "___") {
                flush(); closeList(); out.append("<hr>"); i++; continue
            }

            val bullet = t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")
            val ordered = Regex("^\\d+[.)]\\s").containsMatchIn(t)
            if (bullet || ordered) {
                flush()
                val want = if (bullet) 1 else 2
                if (list != want) {
                    closeList()
                    out.append(if (want == 1) "<ul>" else "<ol>")
                    list = want
                }
                val item = if (bullet) t.substring(2) else t.replaceFirst(Regex("^\\d+[.)]\\s"), "")
                out.append("<li>").append(inline(item)).append("</li>")
                i++; continue
            }

            if (para.isNotEmpty()) para.append(' ')
            para.append(t)
            i++
        }
        flush(); closeList()
        return out.toString()
    }

    private fun inline(s: String): String {
        var t = Html.esc(s)
        t = t.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        t = t.replace(Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)")) {
            "<img alt=\"${it.groupValues[1]}\" src=\"${it.groupValues[2]}\">"
        }
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("__([^_]+)__"), "<strong>$1</strong>")
        t = t.replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*"), "<em>$1</em>")
        t = t.replace(Regex("(?<!_)_([^_\\n]+)_"), "<em>$1</em>")
        return t
    }
}
