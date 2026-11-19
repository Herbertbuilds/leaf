package io.herbert.leaf.core

/**
 * Case-insensitive literal search over chunked text. Indices point back into the
 * chunk list, which is what the text reader needs to highlight and scroll.
 */
object Finder {

    /** A match: [chunk] index plus the character [offset] inside that chunk. */
    data class Hit(val chunk: Int, val offset: Int)

    fun all(chunks: List<String>, query: String, limit: Int = 5000): List<Hit> {
        val needle = query
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        val n = needle.length
        for (i in chunks.indices) {
            val chunk = chunks[i]
            var at = chunk.indexOf(needle, ignoreCase = true)
            while (at >= 0) {
                out.add(Hit(i, at))
                if (out.size >= limit) return out
                at = chunk.indexOf(needle, at + needle.length, ignoreCase = true)
            }
            // Chunks are cuts through otherwise continuous text, so a match can start in
            // this chunk and end in the next. Scan the seam or it is never found.
            if (i + 1 < chunks.size && n > 1) {
                val tailLen = minOf(n - 1, chunk.length)
                if (tailLen > 0) {
                    val next = chunks[i + 1]
                    val overlap = chunk.substring(chunk.length - tailLen) +
                        next.substring(0, minOf(n - 1, next.length))
                    var seam = overlap.indexOf(needle, ignoreCase = true)
                    while (seam >= 0) {
                        if (seam < tailLen && seam + n > tailLen) {
                            out.add(Hit(i, chunk.length - tailLen + seam))
                            if (out.size >= limit) return out
                        }
                        seam = overlap.indexOf(needle, seam + 1, ignoreCase = true)
                    }
                }
            }
        }
        return out
    }
}
