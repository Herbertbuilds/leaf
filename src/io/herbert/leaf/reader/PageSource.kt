package io.herbert.leaf.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.herbert.leaf.core.Magic
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * A thing that can draw page N as a bitmap. PDF, comic archives and single images
 * all implement this, so one pager view serves all three.
 */
interface PageSource {
    val pageCount: Int

    /** height / width, used before the bitmap exists */
    fun aspect(index: Int): Float

    /** Called on a single background thread. */
    fun render(index: Int, widthPx: Int): Bitmap?

    fun close()
}

/** PDF via the platform renderer: no dependency, hardware-friendly, no PDF.js in a WebView. */
class PdfSource(file: File) : PageSource {

    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fd)

    // aspect() runs on the UI thread while render() runs on the render thread, so the
    // page-size memo has to be safe for both.
    private val sizes = ConcurrentHashMap<Int, IntArray>()

    override val pageCount: Int = renderer.pageCount

    private fun size(index: Int): IntArray = sizes.getOrPut(index) {
        // PdfRenderer is not thread safe; aspect() runs on the UI thread while
        // render() runs on the render thread.
        synchronized(renderer) {
            try {
                renderer.openPage(index).use { p -> intArrayOf(p.width, p.height) }
            } catch (_: Throwable) {
                intArrayOf(612, 792)
            }
        }
    }

    override fun aspect(index: Int): Float {
        val s = size(index)
        return if (s[0] <= 0) 1.414f else s[1].toFloat() / s[0]
    }

    override fun render(index: Int, widthPx: Int): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        synchronized(renderer) {
            val s = size(index)
            val w = widthPx.coerceIn(240, 2400)
            // PdfRenderer only renders into ARGB_8888 (anything else throws
            // "Unsupported pixel format"), so the memory saving has to come from the
            // page size: an extreme page (a poster, a strip) is scaled down to fit both
            // the requested width and the height ceiling. The aspect ratio is kept, so
            // the view still fills the width and simply upscales the bitmap.
            val scale = minOf(
                w.toFloat() / s[0].coerceAtLeast(1),
                MAX_PAGE_HEIGHT.toFloat() / s[1].coerceAtLeast(1),
            )
            val bw = (s[0] * scale).toInt().coerceIn(1, w)
            val bh = (s[1] * scale).toInt().coerceIn(1, MAX_PAGE_HEIGHT)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            try {
                renderer.openPage(index).use { page ->
                    page.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } catch (_: Throwable) {
                bmp.recycle()
                return null
            }
            return bmp
        }
    }

    override fun close() {
        synchronized(renderer) {
            try {
                renderer.close()
            } catch (_: Throwable) {
            }
        }
        try {
            fd.close()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** Largest bitmap dimension Leaf will ask the renderer for, in pixels. */
        const val MAX_PAGE_HEIGHT = 4096
    }
}

/** CBZ / image-only zips: every image entry becomes a page, in natural order. */
class ZipImageSource(file: File) : PageSource {

    private val zip = ZipFile(file)
    private val entries = zip.entries().toList()
        .filter { !it.isDirectory && Magic.isImageName(it.name) }
        .sortedBy { Magic.naturalKey(it.name) }

    // Read on the UI thread by aspect() and on the render thread by render().
    private val sizes = ConcurrentHashMap<Int, IntArray>()

    init {
        // Header-only decoding is cheap, and this runs on the background open thread.
        // Measuring here keeps zip reads off the UI thread while a comic is flung.
        if (entries.size <= EAGER_SIZES) for (i in entries.indices) size(i)
    }

    override val pageCount: Int = entries.size

    private fun size(index: Int): IntArray = sizes.getOrPut(index) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entries[index]).use { BitmapFactory.decodeStream(it, null, o) }
            intArrayOf(o.outWidth, o.outHeight)
        } catch (_: Throwable) {
            intArrayOf(0, 0)
        }
    }

    override fun aspect(index: Int): Float {
        val s = size(index)
        return if (s[0] <= 0) 1.4f else s[1].toFloat() / s[0]
    }

    override fun render(index: Int, widthPx: Int): Bitmap? {
        val entry = entries.getOrNull(index) ?: return null
        return try {
            val s = size(index)
            var sample = 1
            while (s[0] > 0 && s[0] / (sample * 2) >= widthPx.coerceAtLeast(320)) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            if (bmp.width > widthPx * 1.4f && bmp.width > 0) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp, widthPx, (bmp.height.toFloat() * widthPx / bmp.width).toInt().coerceAtLeast(1), true
                )
                if (scaled !== bmp) bmp.recycle()
                scaled
            } else {
                bmp
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() {
        try {
            zip.close()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** Above this many pages, headers are read lazily instead of at open time. */
        const val EAGER_SIZES = 2000
    }
}
