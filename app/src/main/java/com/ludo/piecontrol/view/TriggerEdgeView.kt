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
    private val onTriggerActivated: (edge: PieMenuView.Edge, startX: Float, startY: Float, event: MotionEvent) -> Unit
) : View(context) {

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
    private val minSwipeDistancePx = 25f * context.resources.displayMetrics.density

    init {
        updatePaint()
    }

    private fun updatePaint() {
        if (!config.showTriggerIndicator) {
            indicatorPaint.color = Color.TRANSPARENT
            return
        }
        val alpha = (config.triggerIndicatorAlpha * 255 / 100).coerceIn(0, 255)
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
        if (config.showTriggerIndicator) {
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
                if (isTriggered) return true

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                // Check inward swipe according to edge
                val swipedInward = when (edge) {
                    PieMenuView.Edge.LEFT -> dx > minSwipeDistancePx && abs(dx) > abs(dy) * 0.7f
                    PieMenuView.Edge.RIGHT -> (-dx) > minSwipeDistancePx && abs(dx) > abs(dy) * 0.7f
                    PieMenuView.Edge.BOTTOM -> (-dy) > minSwipeDistancePx && abs(dy) > abs(dx) * 0.7f
                }

                if (swipedInward) {
                    isTriggered = true
                    onTriggerActivated(edge, downRawX, downRawY, event)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTriggered = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
