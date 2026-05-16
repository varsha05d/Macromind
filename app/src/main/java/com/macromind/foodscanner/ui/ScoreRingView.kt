package com.macromind.foodscanner.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * ScoreRingView — A premium circular arc that shows health confidence.
 * Features:
 *   • Smoothly animated arc fill
 *   • Gradient stroke support
 *   • Thicker stroke with rounded caps
 */
class ScoreRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f // 0.0 to 1.0
    private var animatedProgress = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E5E7EB") // Light gray track
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = ringPaint.strokeWidth / 2f + 10f
        rect.set(padding, padding, width - padding, height - padding)

        // Draw track (270 degree arc)
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        // Draw progress
        canvas.drawArc(rect, 135f, 270f * animatedProgress, false, ringPaint)
    }

    fun setProgress(value: Float, color: Int, animate: Boolean = true) {
        progress = value.coerceIn(0f, 1f)
        ringPaint.color = color

        if (animate) {
            ValueAnimator.ofFloat(animatedProgress, progress).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animatedProgress = progress
            invalidate()
        }
    }
}
