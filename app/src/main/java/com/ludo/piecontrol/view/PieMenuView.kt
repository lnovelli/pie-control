package com.ludo.piecontrol.view

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
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
            iconCache.clear()
            updatePaints()
            invalidate()
        }

    var currentEdge: Edge = Edge.LEFT
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    private var rawAnchorX: Float = 0f
    private var rawAnchorY: Float = 0f

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Geometry parameters (in pixels)
    private var r0: Float = 0f // Configurable inner deadzone (gap from finger)
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
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 20, 20, 35)
    }
    private val folderFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(160, 0, 230, 118)
    }
    private val folderDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 0, 230, 118)
    }

    // Paths and Rects
    private val arcPath = Path()
    private val innerRect = RectF()
    private val outerRect = RectF()

    // Hover state & Secondary action tracking
    private var hoveredLayer: Int = -1 // 1, 2, 3 or -1
    private var hoveredIndex: Int = -1
    private var isSecondaryArmed: Boolean = false

    // Icon Cache
    private val iconCache = mutableMapOf<String, Bitmap>()

    // Watchdogs & Long-Press Timer
    private val safetyDismissRunnable = Runnable {
        hide()
    }

    private val secondaryArmRunnable = Runnable {
        val action = getHoveredAction()
        if (action?.secondaryAction != null) {
            isSecondaryArmed = true
            performHaptic(long = true)
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        updatePaints()
    }

    private fun updatePaints() {
        val density = context.resources.displayMetrics.density
        val scale = (config.pieScalePercent / 100f).coerceIn(0.5f, 2.0f)
        val alphaFloat = (config.pieAlphaPercent / 100f).coerceIn(0.1f, 1.0f)

        // Configurable distance of the innermost ring from the finger
        val innerDistDp = config.innerRadiusDp.coerceIn(20, 160).toFloat()
        val layerThicknessDp = 58f

        r0 = innerDistDp * density * scale
        r1 = r0 + (layerThicknessDp * density * scale)
        r2 = r1 + (layerThicknessDp * density * scale)
        r3 = r2 + (layerThicknessDp * density * scale)

        // Background Color with Configured Alpha
        val baseBg = parseColorSafe(config.backgroundColorHex, Color.parseColor("#141425"))
        val bgAlpha = (Color.alpha(baseBg) * alphaFloat).toInt().coerceIn(0, 255)
        slicePaint.color = Color.argb(bgAlpha, Color.red(baseBg), Color.green(baseBg), Color.blue(baseBg))

        // Highlight Color with Configured Alpha
        val baseHighlight = parseColorSafe(config.highlightColorHex, Color.parseColor("#00E676"))
        val highAlpha = (235 * alphaFloat).toInt().coerceIn(0, 255)
        sliceHighlightPaint.color = Color.argb(highAlpha, Color.red(baseHighlight), Color.green(baseHighlight), Color.blue(baseHighlight))

        centerGlowPaint.color = Color.argb(
            (70 * alphaFloat).toInt(),
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
        this.rawAnchorX = x
        this.rawAnchorY = y
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        this.anchorX = x - loc[0].toFloat()
        this.anchorY = y - loc[1].toFloat()
        this.currentEdge = edge
        this.hoveredLayer = -1
        this.hoveredIndex = -1
        this.isSecondaryArmed = false
        removeCallbacks(secondaryArmRunnable)
        removeCallbacks(safetyDismissRunnable)
        postDelayed(safetyDismissRunnable, 4000)

        updatePaints()
        visibility = VISIBLE
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        this.anchorX = rawAnchorX - loc[0].toFloat()
        this.anchorY = rawAnchorY - loc[1].toFloat()
    }

    fun hide() {
        removeCallbacks(safetyDismissRunnable)
        removeCallbacks(secondaryArmRunnable)
        isSecondaryArmed = false
        visibility = GONE
        hoveredLayer = -1
        hoveredIndex = -1
        listener?.onDismiss()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return handleMotionEvent(event)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        removeCallbacks(safetyDismissRunnable)
        postDelayed(safetyDismissRunnable, 3000)

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = event.rawX - loc[0].toFloat()
        val localY = event.rawY - loc[1].toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateHover(localX, localY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(safetyDismissRunnable)
                removeCallbacks(secondaryArmRunnable)
                try {
                    updateHover(localX, localY)
                    val action = getHoveredAction()
                    if (action != null) {
                        val actionToTrigger = if (isSecondaryArmed && action.secondaryAction != null) {
                            action.secondaryAction
                        } else {
                            action
                        }
                        performHaptic(long = true)
                        listener?.onActionSelected(actionToTrigger)
                    }
                } finally {
                    hide()
                }
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

        // Blackout / cancel deadzone: covers 85% of inner radius r0.
        // Inside this zone close to the finger, nothing is selected and lifting cancels/dismisses cleanly.
        val cancelDeadzone = r0 * 0.85f

        if (dist >= cancelDeadzone) {
            newLayer = when {
                hasL3 && dist >= r2 -> 3
                hasL2 && dist >= r1 -> 2
                hasL1 -> 1
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

                    // Generous angular tolerance (-25°..205°) so entering from top or bottom selects cleanly
                    if (normAngle in -25f..205f) {
                        val clampedAngle = normAngle.coerceIn(0f, 179.99f)
                        val sliceSpan = 180f / list.size
                        newIndex = (clampedAngle / sliceSpan).toInt().coerceIn(0, list.size - 1)
                    } else {
                        newLayer = -1
                    }
                }
            }
        }

        if (newLayer != hoveredLayer || newIndex != hoveredIndex) {
            hoveredLayer = newLayer
            hoveredIndex = newIndex
            isSecondaryArmed = false
            removeCallbacks(secondaryArmRunnable)

            if (hoveredLayer != -1 && hoveredIndex != -1) {
                performHaptic(long = false)
                val action = getHoveredAction()
                if (action?.secondaryAction != null) {
                    // Arm secondary action after configured hold delay
                    val holdDelay = config.secondaryHoldDelayMs.toLong().coerceIn(150L, 1000L)
                    postDelayed(secondaryArmRunnable, holdDelay)
                }
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
        return when (edge) {
            Edge.LEFT -> {
                var a = angle
                if (a < -90f) a += 360f
                a + 90f
            }
            Edge.RIGHT -> {
                var a = angle
                if (a < 0f) a += 360f
                270f - a
            }
            Edge.BOTTOM -> {
                var a = angle
                if (a < 0f) a += 360f
                a - 180f
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

        // Draw center origin indicator (subtle glow at touch origin)
        canvas.drawCircle(anchorX, anchorY, minOf(r0 * 0.65f, 50f * context.resources.displayMetrics.density), centerGlowPaint)

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

            // Draw Action Icons (single centered or primary + secondary)
            val action = actions[i]

            // Folder visual distinction: draw subtle accent indicator on sector edge
            if (action.type == ActionType.FOLDER || action.secondaryAction?.type == ActionType.FOLDER) {
                val fAngle = startAngle + sweepAngle - 3.5f
                val fRad = Math.toRadians(fAngle.toDouble())
                val fx = (anchorX + (outerR - 10f) * cos(fRad)).toFloat()
                val fy = (anchorY + (outerR - 10f) * sin(fRad)).toFloat()
                canvas.drawCircle(fx, fy, 4f, folderDotPaint)
            }

            drawActionIcons(canvas, action, innerR, outerR, startAngle, sweepAngle, isHovered)
        }
    }

    private fun drawActionIcons(
        canvas: Canvas,
        action: PieAction,
        innerR: Float,
        outerR: Float,
        startAngle: Float,
        sweepAngle: Float,
        isHovered: Boolean
    ) {
        val midAngle = startAngle + (sweepAngle / 2f)
        val rad = Math.toRadians(midAngle.toDouble())
        val midR = (innerR + outerR) / 2f
        val thickness = outerR - innerR

        val secondary = action.secondaryAction
        val defaultIconColor = parseColorSafe(config.iconColorHex, Color.WHITE)
        val highlightColor = parseColorSafe(config.highlightColorHex, Color.parseColor("#00E676"))
        val iconScale = (config.iconScalePercent.coerceIn(50, 200) / 100f)

        if (secondary == null) {
            // SINGLE ACTION: perfectly centered in the slice button
            val cx = (anchorX + midR * cos(rad)).toFloat()
            val cy = (anchorY + midR * sin(rad)).toFloat()

            val baseSize = thickness * 0.44f * iconScale
            val iconSize = (baseSize * (if (isHovered) 1.15f else 1.0f)).toInt()
            val tintColor = if (isHovered) highlightColor else defaultIconColor
            val bitmap = getActionBitmap(action, iconSize, tintColor)
            if (bitmap != null) {
                if (action.type == ActionType.FOLDER) {
                    val half = iconSize / 2f + 4f
                    canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, 10f, 10f, folderFramePaint)
                }
                canvas.drawBitmap(bitmap, cx - (bitmap.width / 2f), cy - (bitmap.height / 2f), null)
            }
        } else {
            // TWO ACTIONS:
            // First trigger is prominent at top-left/center,
            // and bottom-right, slightly smaller is the secondary action icon!
            val cxCenter = (anchorX + midR * cos(rad)).toFloat()
            val cyCenter = (anchorY + midR * sin(rad)).toFloat()

            val cxPrimary = cxCenter - (thickness * 0.08f)
            val cyPrimary = cyCenter - (thickness * 0.08f)

            val cxSec = cxCenter + (thickness * 0.16f)
            val cySec = cyCenter + (thickness * 0.16f)

            val isSecArmed = isHovered && isSecondaryArmed
            val isPrimFocused = isHovered && !isSecondaryArmed

            val primarySize = (thickness * (if (isPrimFocused) 0.42f else 0.36f) * iconScale).toInt()
            val secSize = (thickness * (if (isSecArmed) 0.32f else 0.24f) * iconScale).toInt()

            val primaryTint = if (isPrimFocused) highlightColor else defaultIconColor
            val secTint = if (isSecArmed) highlightColor else defaultIconColor
            val secAlpha = if (isSecArmed) 255 else 200

            // Draw primary trigger icon
            val primBitmap = getActionBitmap(action, primarySize, primaryTint)
            if (primBitmap != null) {
                if (action.type == ActionType.FOLDER) {
                    val half = primarySize / 2f + 3f
                    canvas.drawRoundRect(cxPrimary - half, cyPrimary - half, cxPrimary + half, cyPrimary + half, 8f, 8f, folderFramePaint)
                }
                canvas.drawBitmap(primBitmap, cxPrimary - (primBitmap.width / 2f), cyPrimary - (primBitmap.height / 2f), null)
            }

            // Draw secondary trigger at bottom-right with subtle badge backing
            val badgeRadius = secSize * 0.65f
            badgeBgPaint.color = Color.argb(190, 18, 18, 30)
            canvas.drawCircle(cxSec, cySec, badgeRadius, badgeBgPaint)
            canvas.drawCircle(cxSec, cySec, badgeRadius, sliceBorderPaint)

            val secBitmap = getActionBitmap(secondary, secSize, secTint, alpha = secAlpha)
            if (secBitmap != null) {
                if (secondary.type == ActionType.FOLDER) {
                    val half = secSize / 2f + 2f
                    canvas.drawRoundRect(cxSec - half, cySec - half, cxSec + half, cySec + half, 6f, 6f, folderFramePaint)
                }
                canvas.drawBitmap(secBitmap, cxSec - (secBitmap.width / 2f), cySec - (secBitmap.height / 2f), null)
            }
        }
    }

    private fun getActionBitmap(action: PieAction, sizePx: Int, tintColor: Int, alpha: Int = 255): Bitmap? {
        val safeSize = sizePx.coerceAtLeast(24)
        val cacheKey = "${action.type.name}_${action.data}_${safeSize}_${tintColor}_$alpha"
        iconCache[cacheKey]?.let { return it }

        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (action.type == ActionType.APP && action.data.isNotEmpty()) {
            try {
                val appIcon = context.packageManager.getApplicationIcon(action.data)
                appIcon.setBounds(0, 0, safeSize, safeSize)
                appIcon.alpha = alpha
                appIcon.draw(canvas)
                iconCache[cacheKey] = bitmap
                return bitmap
            } catch (e: Exception) {
                // Fall back to default icon
            }
        }

        if (action.type == ActionType.SHORTCUT && action.data.isNotEmpty()) {
            try {
                val parsed = Intent.parseUri(action.data, Intent.URI_INTENT_SCHEME)
                val pkg = parsed.`package` ?: parsed.component?.packageName
                    ?: context.packageManager.resolveActivity(parsed, 0)?.activityInfo?.packageName
                if (pkg != null) {
                    val appIcon = context.packageManager.getApplicationIcon(pkg)
                    appIcon.setBounds(0, 0, safeSize, safeSize)
                    appIcon.alpha = alpha
                    appIcon.draw(canvas)
                    iconCache[cacheKey] = bitmap
                    return bitmap
                }
            } catch (e: Exception) {
                // Fall back to default icon
            }
        }

        // Vector Drawable with Tint and Alpha
        val drawableRes = if (action.type == ActionType.FOLDER) {
            val folder = config.folders.firstOrNull { it.id == action.data }
            when (folder?.iconName) {
                "ic_folder_apps" -> com.ludo.piecontrol.R.drawable.ic_folder_apps
                "ic_folder_tools" -> com.ludo.piecontrol.R.drawable.ic_folder_tools
                "ic_folder_star" -> com.ludo.piecontrol.R.drawable.ic_folder_star
                else -> com.ludo.piecontrol.R.drawable.ic_action_folder
            }
        } else {
            action.type.iconRes
        }
        val drawable = ContextCompat.getDrawable(context, drawableRes)
        if (drawable != null) {
            val tinted = Color.argb(
                alpha,
                Color.red(tintColor),
                Color.green(tintColor),
                Color.blue(tintColor)
            )
            drawable.setTint(tinted)
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
            // Handled gracefully
        }
    }
}
