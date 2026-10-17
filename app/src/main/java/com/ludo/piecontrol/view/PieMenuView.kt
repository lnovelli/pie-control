package com.ludo.piecontrol.view

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ludo.piecontrol.R
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import kotlin.math.*

class PieMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Edge { LEFT, RIGHT, BOTTOM }

    interface OnActionSelectedListener {
        fun onActionSelected(action: PieAction)
        fun onDismiss()
    }

    var listener: OnActionSelectedListener? = null
    var config: PieConfig = PieConfig.createDefault()
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var currentEdge: Edge = Edge.LEFT
    var anchorX: Float = 0f
    var anchorY: Float = 0f

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Geometry parameters (in pixels)
    private var r0: Float = 0f // Inner deadzone
    private var r1: Float = 0f // Layer 1 outer
    private var r2: Float = 0f // Layer 2 outer
    private var r3: Float = 0f // Layer 3 outer
    private val sliceGapDegrees = 1.2f

    // Paints
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sliceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sliceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(60, 255, 255, 255)
    }
    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 28f
    }

    // Paths and Rects
    private val arcPath = Path()
    private val innerRect = RectF()
    private val outerRect = RectF()

    // Hover state
    private var hoveredLayer: Int = -1 // 1, 2, 3 or -1
    private var hoveredIndex: Int = -1

    // Icon Cache
    private val iconCache = mutableMapOf<String, Bitmap>()

    init {
        setWillNotDraw(false)
        updatePaints()
    }

    private fun updatePaints() {
        val density = context.resources.displayMetrics.density
        val scale = (config.pieScalePercent / 100f).coerceIn(0.5f, 2.0f)
        val alphaFloat = (config.pieAlphaPercent / 100f).coerceIn(0.1f, 1.0f)

        r0 = 40f * density * scale
        r1 = 100f * density * scale
        r2 = 165f * density * scale
        r3 = 230f * density * scale

        // Background Color with Configured Alpha
        val baseBg = parseColorSafe(config.backgroundColorHex, Color.parseColor("#141425"))
        val bgAlpha = (Color.alpha(baseBg) * alphaFloat).toInt().coerceIn(0, 255)
        slicePaint.color = Color.argb(bgAlpha, Color.red(baseBg), Color.green(baseBg), Color.blue(baseBg))

        // Highlight Color with Configured Alpha
        val baseHighlight = parseColorSafe(config.highlightColorHex, Color.parseColor("#00E676"))
        val highAlpha = (235 * alphaFloat).toInt().coerceIn(0, 255)
        sliceHighlightPaint.color = Color.argb(highAlpha, Color.red(baseHighlight), Color.green(baseHighlight), Color.blue(baseHighlight))

        centerGlowPaint.color = Color.argb(
            (80 * alphaFloat).toInt(),
            Color.red(baseHighlight),
            Color.green(baseHighlight),
            Color.blue(baseHighlight)
        )

        val iconColor = parseColorSafe(config.iconColorHex, Color.WHITE)
        labelPaint.color = iconColor
        labelPaint.textSize = 10f * density * scale
    }

    private fun parseColorSafe(hex: String, defaultColor: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            defaultColor
        }
    }

    fun showAt(x: Float, y: Float, edge: Edge) {
        this.anchorX = x
        this.anchorY = y
        this.currentEdge = edge
        this.hoveredLayer = -1
        this.hoveredIndex = -1
        updatePaints()
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
        hoveredLayer = -1
        hoveredIndex = -1
        listener?.onDismiss()
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                updateHover(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateHover(event.rawX, event.rawY)
                val actionToTrigger = getHoveredAction()
                if (actionToTrigger != null) {
                    performHaptic(long = true)
                    listener?.onActionSelected(actionToTrigger)
                }
                hide()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hide()
                return true
            }
        }
        return false
    }

    private fun updateHover(touchX: Float, touchY: Float) {
        val dx = touchX - anchorX
        val dy = touchY - anchorY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        var newLayer = -1
        var newIndex = -1

        val hasL1 = config.layer1.isNotEmpty()
        val hasL2 = config.layer2.isNotEmpty()
        val hasL3 = config.layer3.isNotEmpty()

        // Calculate maximum active radius
        val maxR = when {
            hasL3 -> r3
            hasL2 -> r2
            hasL1 -> r1
            else -> 0f
        }

        if (dist in r0..(maxR + 80f)) {
            // Determine layer
            newLayer = when {
                dist in r0..r1 && hasL1 -> 1
                dist in r1..r2 && hasL2 -> 2
                dist in r2..r3 && hasL3 -> 3
                dist > r3 && hasL3 -> 3
                dist > r2 && hasL2 && !hasL3 -> 2
                else -> -1
            }

            if (newLayer != -1) {
                val list = when (newLayer) {
                    1 -> config.layer1
                    2 -> config.layer2
                    3 -> config.layer3
                    else -> emptyList()
                }

                if (list.isNotEmpty()) {
                    val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val normAngle = normalizeAngle(angleDeg, currentEdge)
                    if (normAngle in 0f..180f) {
                        val sliceSpan = 180f / list.size
                        newIndex = (normAngle / sliceSpan).toInt().coerceIn(0, list.size - 1)
                    } else {
                        newLayer = -1
                    }
                }
            }
        }

        if (newLayer != hoveredLayer || newIndex != hoveredIndex) {
            hoveredLayer = newLayer
            hoveredIndex = newIndex
            if (hoveredLayer != -1 && hoveredIndex != -1) {
                performHaptic(long = false)
            }
            invalidate()
        }
    }

    private fun getHoveredAction(): PieAction? {
        if (hoveredLayer == -1 || hoveredIndex == -1) return null
        return when (hoveredLayer) {
            1 -> config.layer1.getOrNull(hoveredIndex)
            2 -> config.layer2.getOrNull(hoveredIndex)
            3 -> config.layer3.getOrNull(hoveredIndex)
            else -> null
        }
    }

    private fun normalizeAngle(angle: Float, edge: Edge): Float {
        // Normalize angle to [0, 180] span pointing inwards
        return when (edge) {
            Edge.LEFT -> {
                // Semicircle points right: angles range from -90 to +90
                var a = angle
                if (a < -90f) a += 360f
                a + 90f // -90 -> 0, 0 -> 90, 90 -> 180
            }
            Edge.RIGHT -> {
                // Semicircle points left: angles range from +90 to +270
                var a = angle
                if (a < 0f) a += 360f
                // Semicircle from 90 (bottom) to 270 (top)
                270f - a
            }
            Edge.BOTTOM -> {
                // Semicircle points up: angles range from 180 to 360
                var a = angle
                if (a < 0f) a += 360f
                a - 180f // 180 -> 0, 270 -> 90, 360 -> 180
            }
        }
    }

    private fun getStartAngleForEdge(edge: Edge): Float {
        return when (edge) {
            Edge.LEFT -> -90f
            Edge.RIGHT -> 90f
            Edge.BOTTOM -> 180f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw center origin indicator
        canvas.drawCircle(anchorX, anchorY, r0 * 0.75f, centerGlowPaint)

        // Draw Layer 1 (Inner)
        if (config.layer1.isNotEmpty()) {
            drawRing(canvas, 1, r0, r1, config.layer1)
        }

        // Draw Layer 2 (Middle)
        if (config.layer2.isNotEmpty()) {
            drawRing(canvas, 2, r1, r2, config.layer2)
        }

        // Draw Layer 3 (Outer)
        if (config.layer3.isNotEmpty()) {
            drawRing(canvas, 3, r2, r3, config.layer3)
        }
    }

    private fun drawRing(
        canvas: Canvas,
        layerNumber: Int,
        innerR: Float,
        outerR: Float,
        actions: List<PieAction>
    ) {
        if (actions.isEmpty()) return

        val count = actions.size
        val sliceAngle = 180f / count
        val baseStartAngle = getStartAngleForEdge(currentEdge)

        innerRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
        outerRect.set(anchorX - outerR, anchorY - outerR, anchorX + outerR, anchorY + outerR)

        for (i in 0 until count) {
            val isHovered = (hoveredLayer == layerNumber && hoveredIndex == i)

            // Adjust angle according to edge direction
            val startAngle = if (currentEdge == Edge.RIGHT) {
                baseStartAngle + (count - 1 - i) * sliceAngle + (sliceGapDegrees / 2f)
            } else {
                baseStartAngle + i * sliceAngle + (sliceGapDegrees / 2f)
            }
            val sweepAngle = sliceAngle - sliceGapDegrees

            // Build sector path
            arcPath.reset()
            arcPath.arcTo(outerRect, startAngle, sweepAngle, false)
            arcPath.arcTo(innerRect, startAngle + sweepAngle, -sweepAngle, false)
            arcPath.close()

            // Draw slice background
            val paint = if (isHovered) sliceHighlightPaint else slicePaint
            canvas.drawPath(arcPath, paint)
            canvas.drawPath(arcPath, sliceBorderPaint)

            // Draw Icon & Label at Sector Center
            val midAngle = startAngle + (sweepAngle / 2f)
            val midR = (innerR + outerR) / 2f
            val rad = Math.toRadians(midAngle.toDouble())
            val iconCenterX = (anchorX + midR * cos(rad)).toFloat()
            val iconCenterY = (anchorY + midR * sin(rad)).toFloat()

            val action = actions[i]
            drawActionIcon(canvas, action, iconCenterX, iconCenterY, isHovered)
        }
    }

    private fun drawActionIcon(
        canvas: Canvas,
        action: PieAction,
        cx: Float,
        cy: Float,
        isHovered: Boolean
    ) {
        val iconSize = (r1 - r0) * 0.45f * (if (isHovered) 1.15f else 1.0f)
        val bitmap = getActionBitmap(action, iconSize.toInt())
        if (bitmap != null) {
            val left = cx - (bitmap.width / 2f)
            val top = cy - (bitmap.height / 2f)
            canvas.drawBitmap(bitmap, left, top, null)
        }
    }

    private fun getActionBitmap(action: PieAction, sizePx: Int): Bitmap? {
        val safeSize = sizePx.coerceAtLeast(32)
        val cacheKey = "${action.type.name}_${action.data}_$safeSize"
        iconCache[cacheKey]?.let { return it }

        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (action.type == ActionType.APP && action.data.isNotEmpty()) {
            try {
                val appIcon = context.packageManager.getApplicationIcon(action.data)
                appIcon.setBounds(0, 0, safeSize, safeSize)
                appIcon.draw(canvas)
                iconCache[cacheKey] = bitmap
                return bitmap
            } catch (e: Exception) {
                // Fall back to default icon
            }
        }

        // Vector Drawable with Tint
        val drawableRes = action.type.iconRes
        val drawable = ContextCompat.getDrawable(context, drawableRes)
        if (drawable != null) {
            val iconColor = parseColorSafe(config.iconColorHex, Color.WHITE)
            drawable.setTint(iconColor)
            drawable.setBounds(0, 0, safeSize, safeSize)
            drawable.draw(canvas)
            iconCache[cacheKey] = bitmap
            return bitmap
        }

        return null
    }

    private fun performHaptic(long: Boolean) {
        if (!config.hapticFeedback || vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (long) {
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(12, 100)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (long) 35 else 12)
            }
        } catch (e: Exception) {
            // Haptic failure handled silently
        }
    }
}
