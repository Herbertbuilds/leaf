package io.herbert.leaf.core

/**
 * PalmDOC LZ77 decompression, the compression used by every .mobi/.prc book.
 * Ported from the documented algorithm; pure byte math, no Android APIs.
 */
object PalmDoc {

    /**
     * @param src one compressed record
     * @param recordSize the PalmDOC record size (usually 4096), used as an upper bound
     */
    fun decompress(src: ByteArray, recordSize: Int): ByteArray {
        val cap = maxOf(recordSize, 4096) + 64
        val out = ByteArray(cap)
        var o = 0
        var i = 0

        while (i < src.size && o < cap) {
            val c = src[i].toInt() and 0xFF
            i++
            when {
                c == 0x00 -> out[o++] = 0
                c <= 0x08 -> {
                    var k = 0
                    while (k < c && i < src.size && o < cap) {
                        out[o++] = src[i++]; k++
                    }
                }
                c <= 0x7F -> out[o++] = c.toByte()
                c <= 0xBF -> {
                    if (i >= src.size) break
                    val c2 = src[i].toInt() and 0xFF
                    i++
                    val pair = ((c shl 8) or c2) and 0x3FFF
                    val distance = pair shr 3
                    val length = (pair and 0x07) + 3
                    if (distance == 0 || distance > o) break
                    var from = o - distance
                    var k = 0
                    while (k < length && o < cap) {
                        out[o++] = out[from++]
                        k++
                    }
                }
                else -> {
                    if (o < cap) out[o++] = ' '.code.toByte()
                    if (o < cap) out[o++] = (c xor 0x80).toByte()
                }
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
