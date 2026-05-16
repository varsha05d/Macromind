package com.macromind.foodscanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CropOverlayView.kt
 *
 * A transparent View placed on top of an ImageView.
 * Lets the user draw, move, and resize a crop rectangle with touch.
 *
 * Touch interactions:
 *   - Drag from empty area  → draw a new rectangle
 *   - Drag from inside rect → move the rectangle
 *   - Drag a corner handle  → resize the rectangle
 *
 * Public API:
 *   getCropRectForBitmap(bitmap) → Rect scaled to bitmap coordinates
 *   resetCrop()                  → restore default centered rectangle
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── State ──────────────────────────────────────────────────────────────
    private val cropRect = RectF()          // crop rect in VIEW pixels
    private var touchMode = TouchMode.NONE

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var activeHandle = Handle.NONE

    private enum class TouchMode { NONE, DRAWING, MOVING, RESIZING }
    private enum class Handle { NONE, TL, TR, BL, BR }

    private val HANDLE_RADIUS = 26f    // touch target radius (dp-ish)
    private val MIN_SIZE = 60f         // minimum crop rect dimension

    // ── Paints ─────────────────────────────────────────────────────────────
    private val dimPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
    }

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val handleFillPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handleBorderPaint = Paint().apply {
        color = Color.parseColor("#1E88E5")  // blue
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    // ── Size change → set default crop ────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setDefaultCrop(w, h)
    }

    private fun setDefaultCrop(w: Int, h: Int) {
        val pH = w * 0.08f
        val pV = h * 0.20f
        cropRect.set(pH, pV, w - pH, h - pV)
    }

    // ── Draw ───────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        // Dim the area outside the crop rect
        val dimPath = Path().apply {
            addRect(0f, 0f, vw, vh, Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(dimPath, dimPaint)

        // Rule-of-thirds grid
        val tw = cropRect.width() / 3f
        val th = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + tw * i, cropRect.top,
                cropRect.left + tw * i, cropRect.bottom, gridPaint
            )
            canvas.drawLine(
                cropRect.left, cropRect.top + th * i,
                cropRect.right, cropRect.top + th * i, gridPaint
            )
        }

        // Border
        canvas.drawRect(cropRect, borderPaint)

        // Corner handles
        drawHandle(canvas, cropRect.left,  cropRect.top)
        drawHandle(canvas, cropRect.right, cropRect.top)
        drawHandle(canvas, cropRect.left,  cropRect.bottom)
        drawHandle(canvas, cropRect.right, cropRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, HANDLE_RADIUS, handleFillPaint)
        canvas.drawCircle(x, y, HANDLE_RADIUS, handleBorderPaint)
    }

    // ── Touch ──────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitHandle(x, y)
                touchMode = when {
                    activeHandle != Handle.NONE     -> TouchMode.RESIZING
                    cropRect.contains(x, y)         -> TouchMode.MOVING
                    else                            -> TouchMode.DRAWING
                }
                dragStartX = x; dragStartY = y
                lastX = x;      lastY = y
                if (touchMode == TouchMode.DRAWING) cropRect.set(x, y, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.DRAWING  -> {
                        cropRect.set(
                            min(dragStartX, x), min(dragStartY, y),
                            max(dragStartX, x), max(dragStartY, y)
                        )
                    }
                    TouchMode.MOVING   -> {
                        val dx = x - lastX; val dy = y - lastY
                        moveRect(dx, dy)
                    }
                    TouchMode.RESIZING -> resizeRect(activeHandle, x, y)
                    else -> {}
                }
                lastX = x; lastY = y
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Reset to default if result is too small
                if (cropRect.width() < MIN_SIZE || cropRect.height() < MIN_SIZE) {
                    setDefaultCrop(width, height)
                    invalidate()
                }
                touchMode = TouchMode.NONE
                activeHandle = Handle.NONE
            }
        }
        return true
    }

    private fun hitHandle(x: Float, y: Float): Handle {
        val r = HANDLE_RADIUS * 1.6f   // slightly larger hit target than drawn circle
        return when {
            abs(x - cropRect.left)  < r && abs(y - cropRect.top)    < r -> Handle.TL
            abs(x - cropRect.right) < r && abs(y - cropRect.top)    < r -> Handle.TR
            abs(x - cropRect.left)  < r && abs(y - cropRect.bottom) < r -> Handle.BL
            abs(x - cropRect.right) < r && abs(y - cropRect.bottom) < r -> Handle.BR
            else -> Handle.NONE
        }
    }

    private fun resizeRect(handle: Handle, x: Float, y: Float) {
        when (handle) {
            Handle.TL -> {
                cropRect.left = min(x, cropRect.right  - MIN_SIZE).coerceAtLeast(0f)
                cropRect.top  = min(y, cropRect.bottom - MIN_SIZE).coerceAtLeast(0f)
            }
            Handle.TR -> {
                cropRect.right = max(x, cropRect.left + MIN_SIZE).coerceAtMost(width.toFloat())
                cropRect.top   = min(y, cropRect.bottom - MIN_SIZE).coerceAtLeast(0f)
            }
            Handle.BL -> {
                cropRect.left   = min(x, cropRect.right  - MIN_SIZE).coerceAtLeast(0f)
                cropRect.bottom = max(y, cropRect.top + MIN_SIZE).coerceAtMost(height.toFloat())
            }
            Handle.BR -> {
                cropRect.right  = max(x, cropRect.left + MIN_SIZE).coerceAtMost(width.toFloat())
                cropRect.bottom = max(y, cropRect.top  + MIN_SIZE).coerceAtMost(height.toFloat())
            }
            else -> {}
        }
    }

    private fun moveRect(dx: Float, dy: Float) {
        val newLeft   = (cropRect.left   + dx).coerceAtLeast(0f)
        val newTop    = (cropRect.top    + dy).coerceAtLeast(0f)
        val newRight  = (cropRect.right  + dx).coerceAtMost(width.toFloat())
        val newBottom = (cropRect.bottom + dy).coerceAtMost(height.toFloat())

        // Only move if the whole rect stays within bounds
        if (newRight - newLeft >= MIN_SIZE && newBottom - newTop >= MIN_SIZE) {
            cropRect.set(newLeft, newTop, newRight, newBottom)
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns the crop rect scaled to the actual bitmap dimensions.
     * The ImageView must display the bitmap using scaleType="fitCenter".
     */
    fun getCropRectForBitmap(bitmap: Bitmap): Rect {
        // Calculate where the bitmap actually renders inside the ImageView
        // (fitCenter adds letterbox bars on sides or top/bottom)
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW  = bitmap.width.toFloat()
        val bmpH  = bitmap.height.toFloat()

        val scale = min(viewW / bmpW, viewH / bmpH)
        val renderedW = bmpW * scale
        val renderedH = bmpH * scale
        val offsetX = (viewW - renderedW) / 2f
        val offsetY = (viewH - renderedH) / 2f

        // Map crop rect from view space to bitmap space
        val left   = ((cropRect.left   - offsetX) / scale).toInt().coerceIn(0, bitmap.width)
        val top    = ((cropRect.top    - offsetY) / scale).toInt().coerceIn(0, bitmap.height)
        val right  = ((cropRect.right  - offsetX) / scale).toInt().coerceIn(0, bitmap.width)
        val bottom = ((cropRect.bottom - offsetY) / scale).toInt().coerceIn(0, bitmap.height)

        return Rect(left, top, right, bottom)
    }

    /** Reset the crop box back to the centered default */
    fun resetCrop() {
        setDefaultCrop(width, height)
        invalidate()
    }
}
