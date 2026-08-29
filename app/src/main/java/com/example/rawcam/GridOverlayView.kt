package com.example.rawcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws a faint 3x3 (rule-of-thirds) composition grid, plus a small focus-state
 * indicator dot in the corner. Both are purely UI aids sitting on top of the
 * TextureView — neither is ever captured, saved, or composited into the
 * JPEG/DNG in any way.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 55 // faint — roughly 20% opacity
        strokeWidth = resources.displayMetrics.density * 1f
    }

    private val focusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusDotRadius = resources.displayMetrics.density * 5f
    private val focusDotMargin = resources.displayMetrics.density * 16f
    private var focusState: FocusState = FocusState.INACTIVE

    fun setFocusState(state: FocusState) {
        if (focusState == state) return
        focusState = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f

        canvas.drawLine(x1, 0f, x1, h, gridPaint)
        canvas.drawLine(x2, 0f, x2, h, gridPaint)
        canvas.drawLine(0f, y1, w, y1, gridPaint)
        canvas.drawLine(0f, y2, w, y2, gridPaint)

        if (focusState != FocusState.INACTIVE) {
            focusDotPaint.color = when (focusState) {
                FocusState.FOCUSED -> Color.GREEN
                FocusState.SEARCHING -> Color.YELLOW
                FocusState.NOT_FOCUSED -> Color.RED
                FocusState.INACTIVE -> Color.GRAY
            }
            canvas.drawCircle(w - focusDotMargin, focusDotMargin, focusDotRadius, focusDotPaint)
        }
    }
}
