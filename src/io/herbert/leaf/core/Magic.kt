package io.herbert.leaf.core

/** What the first bytes of a file say about it. */
enum class Head { PDF, ZIP, PNG, JPEG, GIF, WEBP, BMP, TIFF, MOBI, RTF, HTML, TEXT, BINARY }

/**
 * Magic-byte sniffing. Deliberately allocation-free and independent of Android so it
 * can be unit tested on a desktop JVM.
 */
object Magic {

    fun head(b: ByteArray): Head {
        fun ascii(off: Int, s: String): Boolean {
            if (off + s.length > b.size) return false
            for (i in s.indices) if ((b[off + i].toInt() and 0xFF) != s[i].code) return false
            return true
        }
        fun byte(i: Int): Int = if (i < b.size) b[i].toInt() and 0xFF else -1

        if (ascii(0, "%PDF")) return Head.PDF
        if (ascii(0, "PK") && (byte(2) == 3 || byte(2) == 5 || byte(2) == 7)) return Head.ZIP
        if (byte(0) == 0x89 && ascii(1, "PNG")) return Head.PNG
        if (byte(0) == 0xFF && byte(1) == 0xD8 && byte(2) == 0xFF) return Head.JPEG
        if (ascii(0, "GIF8")) return Head.GIF
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return Head.WEBP
        if (ascii(0, "BM") && b.size > 26) return Head.BMP
        if (ascii(0, "II*\u0000") || ascii(0, "MM\u0000*")) return Head.TIFF
        if (ascii(60, "BOOKMOBI") || ascii(60, "TEXtREAd")) return Head.MOBI
        if (ascii(0, "{\\rtf")) return Head.RTF

        val probe = minOf(b.size, 1024)
        val lower = StringBuilder(probe)
        for (i in 0 until probe) lower.append(((b[i].toInt() and 0xFF).toChar()))
        val text = lower.toString().lowercase()
        if (text.contains("<!doctype html") || text.contains("<html") || text.contains("<head>")) return Head.HTML

        var odd = 0
        for (i in 0 until probe) {
            val v = b[i].toInt() and 0xFF
            if (v == 0) odd += 3
            else if (v < 0x20 && v != 9 && v != 10 && v != 13 && v != 12) odd++
            else if (v == 0x7F) odd++
        }
        return if (odd * 40 <= probe) Head.TEXT else Head.BINARY
    }

    fun isImageName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp") ||
            n.endsWith(".jxl") || n.endsWith(".avif") || n.endsWith(".heic") || n.endsWith(".heif")
    }

    /** Sorts "page2" before "page10" instead of the other way around. */
    fun naturalKey(s: String): String = Regex("\\d+").replace(s) { it.value.padStart(8, '0') }
}
