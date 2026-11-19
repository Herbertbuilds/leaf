package io.herbert.leaf.core

/**
 * Splits a long string into chunks that are safe to hand to a single TextView.
 * Rendering 10 MB in one TextView costs a fortune in layout time; 6 KB per row is
 * effectively free and keeps recycled rows cheap to measure.
 */
object Chunker {

    const val TARGET = 6000

    fun split(text: String, target: Int = TARGET): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= target) return listOf(text)

        val out = ArrayList<String>(text.length / target + 2)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + target, text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end - 1)
                if (nl > start + target / 3) end = nl + 1
                // never cut a surrogate pair in half
                if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate()) end--
            }
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }
}
