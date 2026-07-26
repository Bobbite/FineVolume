package com.example.finevolume.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class VerticalVolumeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var maxSteps: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var currentStep: Int = 30
        set(value) {
            field = value.coerceIn(0, maxSteps)
            invalidate()
        }

    var onProgressChangeListener: ((step: Int, fromUser: Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF") // Semi-transparent track
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00") // Vibrant orange fill
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val rx = widthF / 2f
        val ry = rx // Full rounded pill ends

        // Draw track
        trackRect.set(0f, 0f, widthF, heightF)
        canvas.drawRoundRect(trackRect, rx, ry, trackPaint)

        // Draw active fill level from bottom up
        val fraction = (currentStep.toFloat() / maxSteps).coerceIn(0f, 1f)
        val fillTop = heightF * (1f - fraction)
        fillRect.set(0f, fillTop, widthF, heightF)
        canvas.drawRoundRect(fillRect, rx, ry, fillPaint)

        // Draw border
        canvas.drawRoundRect(trackRect, rx, ry, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val y = event.y.coerceIn(0f, height.toFloat())
                val fraction = 1f - (y / height.toFloat())
                val newStep = (fraction * maxSteps).roundToInt().coerceIn(0, maxSteps)
                
                if (newStep != currentStep) {
                    currentStep = newStep
                    onProgressChangeListener?.invoke(newStep, true)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
