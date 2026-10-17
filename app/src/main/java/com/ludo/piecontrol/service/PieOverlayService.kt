package com.ludo.piecontrol.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ludo.piecontrol.PieControlApp
import com.ludo.piecontrol.R
import com.ludo.piecontrol.data.PreferencesManager
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import com.ludo.piecontrol.ui.FolderActionAdapter
import com.ludo.piecontrol.ui.MainActivity
import com.ludo.piecontrol.view.PieMenuView
import com.ludo.piecontrol.view.TriggerEdgeView

class PieOverlayService : Service(), PieMenuView.OnActionSelectedListener {

    companion object {
        const val ACTION_RELOAD_CONFIG = "com.ludo.piecontrol.RELOAD_CONFIG"
        const val ACTION_SET_PREVIEW_MODE = "com.ludo.piecontrol.SET_PREVIEW_MODE"
        const val EXTRA_PREVIEW_MODE = "extra_preview_mode"
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
    private var isPreviewMode = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                detachPieMenu()
            }
        }
    }

    private val triggerListener = object : TriggerEdgeView.TriggerListener {
        override fun onSwipeStarted(
            edge: PieMenuView.Edge,
            startX: Float,
            startY: Float,
            event: MotionEvent
        ) {
            handleTriggerSwipe(edge, startX, startY, event)
        }

        override fun onSwipeEvent(event: MotionEvent) {
            pieMenuView?.handleMotionEvent(event)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PreferencesManager.getInstance(this)
        config = prefsManager.loadConfig()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenStateReceiver, filter)

        startAsForeground()
        setupPieMenuView()
        refreshTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_CONFIG -> {
                config = prefsManager.loadConfig()
                pieMenuView?.config = config
                refreshTriggers()
            }
            ACTION_SET_PREVIEW_MODE -> {
                val preview = intent.getBooleanExtra(EXTRA_PREVIEW_MODE, false)
                isPreviewMode = preview
                leftTriggerView?.isPreviewMode = preview
                rightTriggerView?.isPreviewMode = preview
                bottomTriggerView?.isPreviewMode = preview
            }
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
            val leftView = TriggerEdgeView(this, PieMenuView.Edge.LEFT, triggerListener).apply {
                this.config = this@PieOverlayService.config
                this.isPreviewMode = this@PieOverlayService.isPreviewMode
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
                // Handled gracefully
            }
        }

        // 2. Right Edge Trigger
        if (config.rightEdgeEnabled) {
            val rightView = TriggerEdgeView(this, PieMenuView.Edge.RIGHT, triggerListener).apply {
                this.config = this@PieOverlayService.config
                this.isPreviewMode = this@PieOverlayService.isPreviewMode
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
                // Handled gracefully
            }
        }

        // 3. Bottom Edge Trigger
        if (config.bottomEdgeEnabled) {
            val bottomView = TriggerEdgeView(this, PieMenuView.Edge.BOTTOM, triggerListener).apply {
                this.config = this@PieOverlayService.config
                this.isPreviewMode = this@PieOverlayService.isPreviewMode
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
                // Handled gracefully
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

    private var folderPopupView: View? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onActionSelected(action: PieAction) {
        detachPieMenu()
        if (action.type == ActionType.FOLDER) {
            mainHandler.post {
                showFolderPopup(action.data)
            }
        } else {
            ActionExecutor.execute(this, action)
        }
    }

    override fun onDismiss() {
        detachPieMenu()
    }

    private fun showFolderPopup(folderId: String) {
        dismissFolderPopup()
        val latestConfig = prefsManager.loadConfig()
        this.config = latestConfig
        val folder = latestConfig.folders.firstOrNull { it.id == folderId || it.name == folderId }
        if (folder == null) {
            android.util.Log.e("PieOverlayService", "Folder not found with id: $folderId")
            return
        }

        try {
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_PieControl)
            val inflater = LayoutInflater.from(themedContext)
            val popupView = inflater.inflate(R.layout.view_folder_popup, null)

            val tvTitle = popupView.findViewById<TextView>(R.id.tvFolderHeaderTitle)
            val ivIcon = popupView.findViewById<ImageView>(R.id.ivFolderHeaderIcon)
            val btnClose = popupView.findViewById<ImageButton>(R.id.btnFolderClose)
            val tvEmpty = popupView.findViewById<TextView>(R.id.tvFolderEmpty)
            val rvActions = popupView.findViewById<RecyclerView>(R.id.rvFolderActions)
            val scrim = popupView.findViewById<View>(R.id.folderPopupScrim)

            tvTitle.text = folder.name
            val iconRes = when (folder.iconName) {
                "ic_folder_apps" -> R.drawable.ic_folder_apps
                "ic_folder_tools" -> R.drawable.ic_folder_tools
                "ic_folder_star" -> R.drawable.ic_folder_star
                else -> R.drawable.ic_action_folder
            }
            ivIcon.setImageResource(iconRes)

            if (folder.actions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvActions.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvActions.visibility = View.VISIBLE
                rvActions.layoutManager = GridLayoutManager(themedContext, 3)
                rvActions.adapter = FolderActionAdapter(themedContext, folder.actions) { action ->
                    dismissFolderPopup()
                    ActionExecutor.execute(this, action)
                }
            }

            btnClose.setOnClickListener { dismissFolderPopup() }
            scrim.setOnClickListener { dismissFolderPopup() }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager.addView(popupView, params)
            folderPopupView = popupView
            android.util.Log.d("PieOverlayService", "Folder popup shown for ${folder.name}")
        } catch (e: Exception) {
            android.util.Log.e("PieOverlayService", "Failed to show folder popup", e)
        }
    }

    private fun dismissFolderPopup() {
        folderPopupView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            folderPopupView = null
        }
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
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
        dismissFolderPopup()
        detachPieMenu()
        removeTriggers()
        super.onDestroy()
    }
}
