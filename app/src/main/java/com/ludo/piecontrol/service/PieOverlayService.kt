package com.ludo.piecontrol.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ludo.piecontrol.PieControlApp
import com.ludo.piecontrol.R
import com.ludo.piecontrol.data.PreferencesManager
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import com.ludo.piecontrol.ui.MainActivity
import com.ludo.piecontrol.view.PieMenuView
import com.ludo.piecontrol.view.TriggerEdgeView

class PieOverlayService : Service(), PieMenuView.OnActionSelectedListener {

    companion object {
        const val ACTION_RELOAD_CONFIG = "com.ludo.piecontrol.RELOAD_CONFIG"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PreferencesManager
    private var config: PieConfig = PieConfig.createDefault()

    private var leftTriggerView: TriggerEdgeView? = null
    private var rightTriggerView: TriggerEdgeView? = null
    private var bottomTriggerView: TriggerEdgeView? = null

    private var pieMenuView: PieMenuView? = null
    private var isPieAttached = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PreferencesManager.getInstance(this)
        config = prefsManager.loadConfig()

        startAsForeground()
        setupPieMenuView()
        refreshTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD_CONFIG) {
            config = prefsManager.loadConfig()
            pieMenuView?.config = config
            refreshTriggers()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, PieControlApp.CHANNEL_ID)
            .setContentTitle("PieControl Active")
            .setContentText("Edge swipe gestures are ready")
            .setSmallIcon(R.drawable.ic_action_settings)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupPieMenuView() {
        pieMenuView = PieMenuView(this).apply {
            config = this@PieOverlayService.config
            listener = this@PieOverlayService
            visibility = android.view.View.GONE
        }
    }

    fun refreshTriggers() {
        removeTriggers()

        if (!Settings.canDrawOverlays(this)) return
        if (!config.enabled) return

        val density = resources.displayMetrics.density
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val triggerThicknessPx = (config.triggerWidthDp * density).toInt()
        val triggerHeightPx = (config.triggerHeightDp * density).toInt()

        // Calculate vertical position offset
        // 0% = top, 50% = center, 100% = bottom
        val posPercent = config.triggerPositionPercent.coerceIn(0, 100) / 100f
        val yOffset = ((screenHeight - triggerHeightPx) * (posPercent - 0.5f)).toInt()

        // 1. Left Edge Trigger
        if (config.leftEdgeEnabled) {
            val leftView = TriggerEdgeView(this, PieMenuView.Edge.LEFT) { edge, startX, startY, event ->
                handleTriggerSwipe(edge, startX, startY, event)
            }.apply {
                this.config = this@PieOverlayService.config
            }
            leftTriggerView = leftView

            val leftParams = WindowManager.LayoutParams(
                triggerThicknessPx,
                triggerHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                y = yOffset
            }

            try {
                windowManager.addView(leftView, leftParams)
            } catch (e: Exception) {
                // Ignore if error adding view
            }
        }

        // 2. Right Edge Trigger
        if (config.rightEdgeEnabled) {
            val rightView = TriggerEdgeView(this, PieMenuView.Edge.RIGHT) { edge, startX, startY, event ->
                handleTriggerSwipe(edge, startX, startY, event)
            }.apply {
                this.config = this@PieOverlayService.config
            }
            rightTriggerView = rightView

            val rightParams = WindowManager.LayoutParams(
                triggerThicknessPx,
                triggerHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                y = yOffset
            }

            try {
                windowManager.addView(rightView, rightParams)
            } catch (e: Exception) {
                // Ignore if error adding view
            }
        }

        // 3. Bottom Edge Trigger
        if (config.bottomEdgeEnabled) {
            val bottomView = TriggerEdgeView(this, PieMenuView.Edge.BOTTOM) { edge, startX, startY, event ->
                handleTriggerSwipe(edge, startX, startY, event)
            }.apply {
                this.config = this@PieOverlayService.config
            }
            bottomTriggerView = bottomView

            val bottomWidthPx = (config.triggerHeightDp * density).toInt().coerceAtMost(screenWidth)
            val bottomParams = WindowManager.LayoutParams(
                bottomWidthPx,
                triggerThicknessPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }

            try {
                windowManager.addView(bottomView, bottomParams)
            } catch (e: Exception) {
                // Ignore if error adding view
            }
        }
    }

    private fun handleTriggerSwipe(edge: PieMenuView.Edge, startX: Float, startY: Float, event: MotionEvent) {
        val pie = pieMenuView ?: return

        if (!isPieAttached) {
            val pieParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(pie, pieParams)
                isPieAttached = true
            } catch (e: Exception) {
                return
            }
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Clamp anchor point to screen edge
        val anchorX = when (edge) {
            PieMenuView.Edge.LEFT -> 0f
            PieMenuView.Edge.RIGHT -> screenWidth
            PieMenuView.Edge.BOTTOM -> startX.coerceIn(0f, screenWidth)
        }
        val anchorY = when (edge) {
            PieMenuView.Edge.LEFT -> startY.coerceIn(0f, screenHeight)
            PieMenuView.Edge.RIGHT -> startY.coerceIn(0f, screenHeight)
            PieMenuView.Edge.BOTTOM -> screenHeight
        }

        pie.showAt(anchorX, anchorY, edge)
        pie.handleMotionEvent(event)
    }

    override fun onActionSelected(action: PieAction) {
        ActionExecutor.execute(this, action)
        detachPieMenu()
    }

    override fun onDismiss() {
        detachPieMenu()
    }

    private fun detachPieMenu() {
        val pie = pieMenuView ?: return
        if (isPieAttached) {
            try {
                windowManager.removeView(pie)
            } catch (e: Exception) {
                // Handled gracefully
            }
            isPieAttached = false
        }
    }

    private fun removeTriggers() {
        leftTriggerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            leftTriggerView = null
        }
        rightTriggerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            rightTriggerView = null
        }
        bottomTriggerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            bottomTriggerView = null
        }
    }

    override fun onDestroy() {
        isRunning = false
        detachPieMenu()
        removeTriggers()
        super.onDestroy()
    }
}
