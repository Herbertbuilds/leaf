package io.herbert.leaf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.sqrt

/**
 * One page of a paginated document. Draws the rendered bitmap fitted to the view width
 * and adds pinch zoom plus double-tap zoom with panning while zoomed. Vertical scrolling
 * between pages is left to the ListView, which has better fling physics than a custom
 * scroller would.
 */
class PageView(context: Context) : View(context) {

    private var source: PageSource? = null
    private var cache: PageCache? = null
    private var index = 0
    private var aspect = SQRT2
    private var bitmap: Bitmap? = null
    private var renderedAt = 0

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var placeholder = 0xFF1A1F23.toInt()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            if (next != zoom) {
                zoom = next
                if (zoom <= 1.001f) {
                    panX = 0f
                    panY = 0f
                }
                clampPan()
                invalidate()
            }
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1.2f) 1f else 2.4f
            if (zoom == 1f) {
                panX = 0f
                panY = 0f
            }
            clampPan()
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom <= 1.001f) return false
            panX -= distanceX
            panY -= distanceY
            clampPan()
            invalidate()
            return true
        }
    })

    init {
        isClickable = true
        setWillNotDraw(false)
    }

    fun setPlaceholder(color: Int) {
        placeholder = color
        invalidate()
    }

    fun bind(source: PageSource, cache: PageCache, index: Int) {
        this.source = source
        this.cache = cache
        this.index = index
        zoom = 1f
        panX = 0f
        panY = 0f
        renderedAt = 0
        bitmap = null
        aspect = source.aspect(index).let { if (it.isFinite() && it > 0.1f && it < 12f) it else SQRT2 }
        requestLayout()
        invalidate()
        if (width > 0) ensureBitmap()
    }

    private fun ensureBitmap() {
        val c = cache ?: return
        val w = width
        if (w <= 0) return
        if (bitmap != null && renderedAt == w) return

        val cached = c.peek(index, w)
        if (cached != null) {
            bitmap = cached
            renderedAt = w
            invalidate()
            return
        }
        val want = index
        c.request(want, w) { bmp ->
            // A bitmap rendered for an old width would be drawn stretched; the size
            // change already asked for the right one.
            if (bmp != null && want == this.index && w == width) {
                bitmap = bmp
                renderedAt = w
                clampPan()
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureBitmap()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h = (w * aspect).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawColor(placeholder)
            return
        }
        val fit = width.toFloat() / bmp.width
        val scale = fit * zoom
        val dw = bmp.width * scale
        val dh = bmp.height * scale

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate((width - dw) / 2f + panX, panY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        val scale = width.toFloat() / bmp.width * zoom
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val maxX = ((dw - width) / 2f).coerceAtLeast(0f)
        val maxY = (dh - height).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || zoom > 1.001f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private companion object {
        val SQRT2 = sqrt(2f)
    }
}
