package com.ludo.piecontrol.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.ludo.piecontrol.model.PieConfig
import kotlin.math.abs

class TriggerEdgeView(
    context: Context,
    val edge: PieMenuView.Edge,
    private val listener: TriggerListener
) : View(context) {

    interface TriggerListener {
        fun onSwipeStarted(edge: PieMenuView.Edge, startX: Float, startY: Float, event: MotionEvent)
        fun onSwipeEvent(event: MotionEvent)
    }

    var isPreviewMode: Boolean = false
        set(value) {
            field = value
            updatePaint()
            invalidate()
        }

    var config: PieConfig = PieConfig.createDefault()
        set(value) {
            field = value
            updatePaint()
            invalidate()
        }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private var downRawX = 0f
    private var downRawY = 0f
    private var isTriggered = false
    private val minSwipeDistancePx = 20f * context.resources.displayMetrics.density

    init {
        updatePaint()
    }

    private fun updatePaint() {
        if (!isPreviewMode) {
            indicatorPaint.color = Color.TRANSPARENT
            return
        }
        val alpha = (config.triggerIndicatorAlpha * 255 / 100).coerceIn(40, 255)
        val highlightColor = try {
            Color.parseColor(config.highlightColorHex)
        } catch (e: Exception) {
            Color.parseColor("#00E676")
        }
        indicatorPaint.color = Color.argb(
            alpha,
            Color.red(highlightColor),
            Color.green(highlightColor),
            Color.blue(highlightColor)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Indicator bars are only visible during preview mode (while configuring in MainActivity)
        if (isPreviewMode) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val cornerRadius = 8f * context.resources.displayMetrics.density
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, indicatorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                isTriggered = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTriggered) {
                    listener.onSwipeEvent(event)
                    return true
                }

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                // Detect deliberate inward swipe towards screen center
                val swipedInward = when (edge) {
                    PieMenuView.Edge.LEFT -> dx > minSwipeDistancePx && abs(dx) > abs(dy) * 0.5f
                    PieMenuView.Edge.RIGHT -> (-dx) > minSwipeDistancePx && abs(dx) > abs(dy) * 0.5f
                    PieMenuView.Edge.BOTTOM -> (-dy) > minSwipeDistancePx && abs(dy) > abs(dx) * 0.5f
                }

                if (swipedInward) {
                    isTriggered = true
                    listener.onSwipeStarted(edge, downRawX, downRawY, event)
                    listener.onSwipeEvent(event)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTriggered) {
                    isTriggered = false
                    listener.onSwipeEvent(event)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
