package io.herbert.leaf.reader

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Renders pages off the UI thread, caches a few of them by (page, width), and collapses
 * duplicate requests from views that are being recycled quickly while flinging.
 */
class PageCache(private val source: PageSource, maxBytes: Int) {

    private val lru = object : LruCache<Long, Bitmap>(maxBytes) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "leaf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<Long>()
    private val waiters = HashMap<Long, MutableList<(Bitmap?) -> Unit>>()

    @Volatile
    private var closed = false

    fun peek(index: Int, width: Int): Bitmap? = lru.get(key(index, width))

    fun request(index: Int, width: Int, callback: (Bitmap?) -> Unit) {
        if (closed) return
        val k = key(index, width)
        lru.get(k)?.let {
            callback(it)
            return
        }
        synchronized(this) {
            if (k in inFlight) {
                waiters.getOrPut(k) { mutableListOf() }.add(callback)
                return
            }
            inFlight.add(k)
        }
        worker.execute {
            val bmp = try {
                if (closed) null else source.render(index, width)
            } catch (_: Throwable) {
                // A bad page must never take the reader down; the view just stays blank.
                null
            }
            synchronized(this) {
                inFlight.remove(k)
                if (bmp != null && !closed) lru.put(k, bmp)
            }
            val others = synchronized(this) { waiters.remove(k) }
            main.post {
                if (closed) return@post
                // A failed render must still answer the caller that asked for it, or its
                // page stays a placeholder until the row is rebound.
                callback(bmp)
                others?.forEach { it(bmp) }
            }
        }
    }

    fun close() {
        closed = true
        // Let a render that is already running finish before the source is torn down:
        // closing a PdfRenderer under a live render is a native crash on some devices.
        try {
            worker.execute {
                try {
                    source.close()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            try {
                source.close()
            } catch (_: Throwable) {
            }
        }
        worker.shutdown()
    }

    private fun key(index: Int, width: Int): Long =
        (width.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
}
