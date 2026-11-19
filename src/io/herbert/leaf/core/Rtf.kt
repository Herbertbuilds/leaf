package io.herbert.leaf.core

/** RTF to text: enough of the format to read old documents, nothing more. */
object Rtf {

    private val IGNORED = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "objdata",
        "themedata", "datastore", "latentstyles", "xmlnstbl", "listtable",
        "listoverridetable", "rsidtbl", "generator", "filetbl", "revtbl",
        "pgptbl", "header", "footer", "fldinst",
    )

    fun toText(src: String): String {
        val out = StringBuilder(src.length / 2)
        var i = 0
        var depth = 0
        var skipFrom = 0
        // \ucN says how many fallback characters follow a \u escape; they must be
        // dropped or every accented letter would be written twice ("café?").
        var ucSkip = 1
        var pendingSkip = 0

        while (i < src.length) {
            if (pendingSkip > 0) {
                // A fallback can be a plain char or an escaped one (\'e9, \~).
                i += when {
                    i + 3 < src.length && src[i] == '\\' && src[i + 1] == '\'' -> 4
                    i + 1 < src.length && src[i] == '\\' -> 2
                    else -> 1
                }
                pendingSkip--
                continue
            }
            val c = src[i]
            when {
                c == '{' -> {
                    depth++
                    i++
                    if (skipFrom == 0 && i < src.length && (src[i] == '\\' || src[i] == '*')) {
                        var j = i
                        if (src[j] == '*') { skipFrom = depth; j++ ; i = j; continue }
                        if (j + 1 < src.length && src[j + 1].isLetter()) {
                            var k = j + 1
                            while (k < src.length && src[k].isLetter()) k++
                            val word = src.substring(j + 1, k)
                            if (word in IGNORED) skipFrom = depth
                        }
                    }
                }
                c == '}' -> {
                    if (skipFrom == depth) skipFrom = 0
                    depth--
                    i++
                }
                c == '\\' -> {
                    val next = if (i + 1 < src.length) src[i + 1] else ' '
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (skipFrom == 0) out.append(next)
                            i += 2
                        }
                        next == '\'' -> {
                            if (skipFrom == 0 && i + 3 < src.length) {
                                val hex = src.substring(i + 2, i + 4).toIntOrNull(16)
                                if (hex != null) out.append(hex.toChar())
                            }
                            i += 4
                        }
                        next == '*' -> i += 2
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var neg = false
                            if (j < src.length && src[j] == '-') { neg = true; j++ }
                            var num = 0
                            var hasNum = false
                            while (j < src.length && src[j].isDigit()) {
                                num = num * 10 + (src[j] - '0'); hasNum = true; j++
                            }
                            if (j < src.length && src[j] == ' ') j++
                            i = j
                            if (skipFrom == 0) when (word) {
                                "par", "line", "sect", "row" -> out.append('\n')
                                "tab", "cell" -> out.append('\t')
                                "emdash" -> out.append('\u2014')
                                "endash" -> out.append('\u2013')
                                "bullet" -> out.append("\u2022 ")
                                "lquote", "rquote" -> out.append('\'')
                                "ldblquote", "rdblquote" -> out.append('"')
                                "uc" -> if (hasNum) ucSkip = num.coerceIn(0, 16)
                                "u" -> if (hasNum) {
                                    val code = if (neg) 65536 + num else num
                                    // Char is 16-bit: anything above U+FFFF needs a
                                    // surrogate pair, or toChar() silently truncates it.
                                    if (code in 0..0xFFFF) {
                                        out.append(code.toChar())
                                    } else if (code in 0x10000..0x10FFFF) {
                                        val v = code - 0x10000
                                        out.append((0xD800 + (v shr 10)).toChar())
                                        out.append((0xDC00 + (v and 0x3FF)).toChar())
                                    }
                                    pendingSkip = ucSkip
                                }
                                else -> Unit
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    if (skipFrom == 0) out.append(c)
                    i++
                }
            }
        }
        return out.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
