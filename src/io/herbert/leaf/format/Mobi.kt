package io.herbert.leaf.format

import io.herbert.leaf.core.PalmDoc
import io.herbert.leaf.core.TextCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * PalmDB/MOBI reading: header, record table, PalmDOC decompression. HUFF/CDIC and
 * DRM books are reported honestly instead of producing garbage.
 */
object Mobi {

    fun text(file: File): String {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(78)
                if (raf.length() < 78) return ""
                raf.readFully(header)

                val type = String(header, 60, 8, Charsets.ISO_8859_1)
                if (!type.startsWith("BOOK") && !type.startsWith("TEXt") && !type.startsWith("READ")) return ""

                val count = u16(header, 76)
                if (count <= 1) return ""

                val offsets = IntArray(count)
                raf.seek(78)
                val buf = ByteArray(4)
                for (i in 0 until count) {
                    raf.readFully(buf)
                    offsets[i] = be32(buf, 0)
                }

                val rec0 = record(raf, offsets, 0) ?: return ""
                if (rec0.size < 16) return ""

                val compression = u16(rec0, 0)
                val textLength = be32(rec0, 4)
                val textRecords = u16(rec0, 8)
                val recordSize = u16(rec0, 10).let { if (it <= 0) 4096 else it }
                val encryption = u16(rec0, 12)

                if (encryption != 0) return "This Kindle book is DRM protected, so Leaf cannot read it."
                if (compression == 17480) {
                    return "This Kindle book uses HUFF/CDIC compression, which Leaf does not decode.\n" +
                        "Converting it to EPUB (Calibre does this in one click) makes it readable here."
                }

                val out = ByteArrayOutputStream(maxOf(textLength, 1).coerceAtMost(64 * 1024 * 1024))
                for (i in 1..textRecords) {
                    val rec = record(raf, offsets, i) ?: break
                    if (compression == 2) out.write(PalmDoc.decompress(rec, recordSize))
                    else out.write(rec)
                    if (out.size() >= textLength && textLength > 0) break
                }

                val bytes = out.toByteArray()
                val take = if (textLength in 1..bytes.size) textLength else bytes.size
                return TextCodec.decode(if (take == bytes.size) bytes else bytes.copyOf(take))
            }
        } catch (_: Throwable) {
            return ""
        }
    }

    private fun record(raf: RandomAccessFile, offsets: IntArray, index: Int): ByteArray? {
        if (index < 0 || index >= offsets.size) return null
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else raf.length().toInt()
        if (start <= 0 || end <= start || end > raf.length()) return null
        val len = end - start
        if (len > 8 * 1024 * 1024) return null
        val b = ByteArray(len)
        raf.seek(start.toLong())
        raf.readFully(b)
        return b
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
