package com.ludo.piecontrol.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ludo.piecontrol.R
import com.ludo.piecontrol.data.PreferencesManager
import com.ludo.piecontrol.databinding.ActivityMainBinding
import com.ludo.piecontrol.databinding.ItemRingActionBinding
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import com.ludo.piecontrol.model.PieFolder
import com.ludo.piecontrol.service.PieAccessibilityService
import com.ludo.piecontrol.service.PieOverlayService
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var config: PieConfig

    // SAF Import Launcher
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonString = reader.readText()
                    if (prefsManager.importConfigJson(jsonString)) {
                        config = prefsManager.loadConfig()
                        notifyServiceConfigChanged()
                        bindConfigToUi()
                        Toast.makeText(this, "Configuration imported successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Invalid configuration format", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF Export Launcher
    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val jsonString = prefsManager.exportConfigJson()
                    outputStream.write(jsonString.toByteArray())
                    Toast.makeText(this, "Configuration exported successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // In-app Action Shortcut Launcher
    private var onShortcutResultCallback: ((PieAction) -> Unit)? = null
    private val shortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val targetIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }
            val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
                ?: targetIntent?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
                ?: "Shortcut"
            val uri = targetIntent?.toUri(Intent.URI_INTENT_SCHEME)
                ?: data.dataString
                ?: data.toUri(Intent.URI_INTENT_SCHEME)

            if (!uri.isNullOrEmpty()) {
                val action = PieAction(
                    type = ActionType.SHORTCUT,
                    label = name,
                    data = uri
                )
                onShortcutResultCallback?.invoke(action)
            } else {
                Toast.makeText(this, "Could not extract shortcut data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager.getInstance(this)
        config = prefsManager.loadConfig()

        setupPermissionsUi()
        bindConfigToUi()
        setupListeners()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("trigger_update_download", false) == true) {
            val url = intent.getStringExtra("update_download_url") ?: com.ludo.piecontrol.update.AppUpdateManager.DEFAULT_APK_URL
            val ver = intent.getStringExtra("update_version_name") ?: "New Version"
            startUpdateDownload(url, ver)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsStatus()
        ensureServiceState()
        setServicePreviewMode(true)
    }

    override fun onPause() {
        super.onPause()
        setServicePreviewMode(false)
    }

    override fun onDestroy() {
        setServicePreviewMode(false)
        super.onDestroy()
    }

    private fun setupPermissionsUi() {
        binding.btnOverlayPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updatePermissionsStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.btnOverlayPermission.text = getString(R.string.granted)
            binding.btnOverlayPermission.isEnabled = false
        } else {
            binding.btnOverlayPermission.text = getString(R.string.grant_permission)
            binding.btnOverlayPermission.isEnabled = true
        }

        val hasAccessibility = isAccessibilityServiceEnabled()
        if (hasAccessibility) {
            binding.btnAccessibilityPermission.text = getString(R.string.enabled)
            binding.btnAccessibilityPermission.isEnabled = false
        } else {
            binding.btnAccessibilityPermission.text = getString(R.string.enable_service)
            binding.btnAccessibilityPermission.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.id.contains(packageName)) {
                return true
            }
        }
        return false
    }

    private fun bindConfigToUi() {
        // Master switch
        binding.switchMaster.isChecked = config.enabled
        binding.tvMasterStatus.text = if (config.enabled) getString(R.string.status_enabled) else getString(R.string.status_disabled)

        // Edge switches
        binding.switchLeftEdge.isChecked = config.leftEdgeEnabled
        binding.switchRightEdge.isChecked = config.rightEdgeEnabled
        binding.switchBottomEdge.isChecked = config.bottomEdgeEnabled

        // Trigger sliders
        binding.sliderTriggerHeight.value = config.triggerHeightDp.toFloat().coerceIn(120f, 480f)
        binding.tvTriggerHeightLabel.text = "Trigger Height: ${config.triggerHeightDp} dp"

        binding.sliderTriggerWidth.value = config.triggerWidthDp.toFloat().coerceIn(10f, 60f)
        binding.tvTriggerWidthLabel.text = "Trigger Thickness: ${config.triggerWidthDp} dp"

        binding.sliderTriggerPos.value = config.triggerPositionPercent.toFloat().coerceIn(10f, 90f)
        binding.tvTriggerPosLabel.text = "Vertical Position: ${config.triggerPositionPercent}%"

        binding.switchShowIndicator.isChecked = config.showTriggerIndicator

        // Appearance
        binding.sliderPieScale.value = config.pieScalePercent.toFloat().coerceIn(60f, 150f)
        binding.tvPieScaleLabel.text = "Semicircle Scale: ${config.pieScalePercent}%"

        binding.sliderIconScale.value = config.iconScalePercent.toFloat().coerceIn(60f, 180f)
        binding.tvIconScaleLabel.text = "Icon Scale: ${config.iconScalePercent}%"

        binding.sliderPieAlpha.value = config.pieAlphaPercent.toFloat().coerceIn(30f, 100f)
        binding.tvPieAlphaLabel.text = "Opacity: ${config.pieAlphaPercent}%"

        // Inner Ring Distance from Finger
        binding.sliderInnerRadius.value = config.innerRadiusDp.toFloat().coerceIn(20f, 140f)
        binding.tvInnerRadiusLabel.text = "Distance from Finger: ${config.innerRadiusDp} dp"

        // Timing Sliders
        binding.sliderShortHoverDelay.value = config.shortHoverDelayMs.toFloat().coerceIn(0f, 300f)
        binding.tvShortHoverLabel.text = if (config.shortHoverDelayMs == 0) "Short Hover Activation: Instant (0 ms)" else "Short Hover Activation: ${config.shortHoverDelayMs} ms"

        binding.sliderSecondaryHoldDelay.value = config.secondaryHoldDelayMs.toFloat().coerceIn(150f, 750f)
        binding.tvSecondaryHoldLabel.text = "Secondary Action Hold Trigger: ${config.secondaryHoldDelayMs} ms"

        binding.switchHaptic.isChecked = config.hapticFeedback

        // App Version
        val versionName = com.ludo.piecontrol.update.AppUpdateManager.getCurrentVersionName(this)
        val versionCode = com.ludo.piecontrol.update.AppUpdateManager.getCurrentVersionCode(this)
        binding.tvAppVersion.text = "PieControl v$versionName (Build $versionCode)"

        // Rings
        renderRingActions()
    }

    private fun setupListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            config.enabled = isChecked
            saveConfig()
            binding.tvMasterStatus.text = if (isChecked) getString(R.string.status_enabled) else getString(R.string.status_disabled)
            ensureServiceState()
        }

        binding.switchLeftEdge.setOnCheckedChangeListener { _, isChecked ->
            config.leftEdgeEnabled = isChecked
            saveConfig()
        }

        binding.switchRightEdge.setOnCheckedChangeListener { _, isChecked ->
            config.rightEdgeEnabled = isChecked
            saveConfig()
        }

        binding.switchBottomEdge.setOnCheckedChangeListener { _, isChecked ->
            config.bottomEdgeEnabled = isChecked
            saveConfig()
        }

        binding.sliderTriggerHeight.addOnChangeListener { _, value, _ ->
            config.triggerHeightDp = value.toInt()
            binding.tvTriggerHeightLabel.text = "Trigger Height: ${config.triggerHeightDp} dp"
            saveConfig()
        }

        binding.sliderTriggerWidth.addOnChangeListener { _, value, _ ->
            config.triggerWidthDp = value.toInt()
            binding.tvTriggerWidthLabel.text = "Trigger Thickness: ${config.triggerWidthDp} dp"
            saveConfig()
        }

        binding.sliderTriggerPos.addOnChangeListener { _, value, _ ->
            config.triggerPositionPercent = value.toInt()
            binding.tvTriggerPosLabel.text = "Vertical Position: ${config.triggerPositionPercent}%"
            saveConfig()
        }

        binding.switchShowIndicator.setOnCheckedChangeListener { _, isChecked ->
            config.showTriggerIndicator = isChecked
            saveConfig()
        }

        binding.sliderPieScale.addOnChangeListener { _, value, _ ->
            config.pieScalePercent = value.toInt()
            binding.tvPieScaleLabel.text = "Semicircle Scale: ${config.pieScalePercent}%"
            saveConfig()
        }

        binding.sliderIconScale.addOnChangeListener { _, value, _ ->
            config.iconScalePercent = value.toInt()
            binding.tvIconScaleLabel.text = "Icon Scale: ${config.iconScalePercent}%"
            saveConfig()
        }

        binding.sliderPieAlpha.addOnChangeListener { _, value, _ ->
            config.pieAlphaPercent = value.toInt()
            binding.tvPieAlphaLabel.text = "Opacity: ${config.pieAlphaPercent}%"
            saveConfig()
        }

        binding.sliderInnerRadius.addOnChangeListener { _, value, _ ->
            config.innerRadiusDp = value.toInt()
            binding.tvInnerRadiusLabel.text = "Distance from Finger: ${config.innerRadiusDp} dp"
            saveConfig()
        }

        binding.sliderShortHoverDelay.addOnChangeListener { _, value, _ ->
            config.shortHoverDelayMs = value.toInt()
            binding.tvShortHoverLabel.text = if (config.shortHoverDelayMs == 0) "Short Hover Activation: Instant (0 ms)" else "Short Hover Activation: ${config.shortHoverDelayMs} ms"
            saveConfig()
        }

        binding.sliderSecondaryHoldDelay.addOnChangeListener { _, value, _ ->
            config.secondaryHoldDelayMs = value.toInt()
            binding.tvSecondaryHoldLabel.text = "Secondary Action Hold Trigger: ${config.secondaryHoldDelayMs} ms"
            saveConfig()
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            config.hapticFeedback = isChecked
            saveConfig()
        }

        // Color Presets
        binding.btnColorGreen.setOnClickListener {
            config.highlightColorHex = "#FF00E676"
            saveConfig()
            Toast.makeText(this, "Highlight color set to Emerald Green", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorBlue.setOnClickListener {
            config.highlightColorHex = "#FF2979FF"
            saveConfig()
            Toast.makeText(this, "Highlight color set to Electric Blue", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorGold.setOnClickListener {
            config.highlightColorHex = "#FFFFD700"
            saveConfig()
            Toast.makeText(this, "Highlight color set to Gold", Toast.LENGTH_SHORT).show()
        }
        binding.btnColorRed.setOnClickListener {
            config.highlightColorHex = "#FFFF5252"
            saveConfig()
            Toast.makeText(this, "Highlight color set to Crimson Red", Toast.LENGTH_SHORT).show()
        }

        // Ring Add Buttons
        binding.btnAddLayer1.setOnClickListener { showAddActionDialog(1) }
        binding.btnAddLayer2.setOnClickListener { showAddActionDialog(2) }
        binding.btnAddLayer3.setOnClickListener { showAddActionDialog(3) }

        // Action Folders Add Button
        binding.btnAddFolder.setOnClickListener { showFolderEditDialog(null) }

        // Backup & Restore
        binding.btnExportConfig.setOnClickListener {
            exportFileLauncher.launch("piecontrol_config.json")
        }

        binding.btnImportConfig.setOnClickListener {
            showImportOptionsDialog()
        }

        binding.btnResetDefaults.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset to Defaults")
                .setMessage("Are you sure you want to reset all PieControl settings and ring actions to defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    config = prefsManager.resetToDefaults()
                    notifyServiceConfigChanged()
                    bindConfigToUi()
                    Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // App Updates
        binding.btnCheckUpdate.setOnClickListener {
            performCheckUpdate()
        }
    }

    private fun performCheckUpdate() {
        val currentVerName = com.ludo.piecontrol.update.AppUpdateManager.getCurrentVersionName(this)
        binding.tvUpdateStatus.text = "Checking server for updates..."
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.progressBarUpdate.isIndeterminate = true
        binding.progressBarUpdate.visibility = View.VISIBLE
        binding.btnCheckUpdate.isEnabled = false

        com.ludo.piecontrol.update.AppUpdateManager.checkForUpdates(this) { hasUpdate, _, remoteVersion, downloadUrl, notes, error ->
            binding.progressBarUpdate.visibility = View.GONE
            binding.btnCheckUpdate.isEnabled = true

            if (error != null) {
                binding.tvUpdateStatus.text = "Update check failed: $error"
                Toast.makeText(this, "Could not check for updates: $error", Toast.LENGTH_SHORT).show()
                return@checkForUpdates
            }

            if (hasUpdate) {
                binding.tvUpdateStatus.text = "Update available: v$remoteVersion"
                MaterialAlertDialogBuilder(this)
                    .setTitle("Update Available")
                    .setMessage("Version $remoteVersion is available (Current: v$currentVerName).\n\nRelease notes:\n$notes\n\nDo you want to download and install it?")
                    .setPositiveButton("Update Now") { _, _ ->
                        startUpdateDownload(downloadUrl, remoteVersion)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } else {
                binding.tvUpdateStatus.text = "App is up to date (v$currentVerName)"
                Toast.makeText(this, "PieControl is up to date (v$currentVerName)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startUpdateDownload(downloadUrl: String, version: String) {
        binding.tvUpdateStatus.text = "Starting download..."
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.progressBarUpdate.isIndeterminate = false
        binding.progressBarUpdate.progress = 0
        binding.progressBarUpdate.visibility = View.VISIBLE
        binding.btnCheckUpdate.isEnabled = false

        com.ludo.piecontrol.update.AppUpdateManager.downloadAndPromptInstall(
            context = this,
            downloadUrl = downloadUrl,
            title = "PieControl v$version Update",
            onProgress = { progress ->
                binding.progressBarUpdate.progress = progress
                binding.tvUpdateStatus.text = "Downloading: $progress%"
            },
            onError = { error ->
                binding.progressBarUpdate.visibility = View.GONE
                binding.tvUpdateStatus.text = "Download failed: $error"
                binding.btnCheckUpdate.isEnabled = true
                Toast.makeText(this, "Update download failed: $error", Toast.LENGTH_LONG).show()
            },
            onSuccess = {
                binding.progressBarUpdate.visibility = View.GONE
                binding.tvUpdateStatus.text = "Download complete. Launching installer..."
                binding.btnCheckUpdate.isEnabled = true
            }
        )
    }

    private fun renderRingActions() {
        updateRingLayerUi(1, binding.tvLayer1Header, binding.btnAddLayer1, binding.rvLayer1, binding.tvEmptyLayer1, config.layer1)
        updateRingLayerUi(2, binding.tvLayer2Header, binding.btnAddLayer2, binding.rvLayer2, binding.tvEmptyLayer2, config.layer2)
        updateRingLayerUi(3, binding.tvLayer3Header, binding.btnAddLayer3, binding.rvLayer3, binding.tvEmptyLayer3, config.layer3)
        renderFolders()
    }

    private fun renderFolders() {
        val folders = config.folders
        if (folders.isEmpty()) {
            binding.tvEmptyFolders.visibility = View.VISIBLE
            binding.rvFolders.visibility = View.GONE
        } else {
            binding.tvEmptyFolders.visibility = View.GONE
            binding.rvFolders.visibility = View.VISIBLE
        }
        binding.rvFolders.layoutManager = LinearLayoutManager(this)
        binding.rvFolders.adapter = FolderAdapter(
            folders = folders,
            onEditClick = { folder, pos -> showFolderEditDialog(folder, pos) },
            onDeleteClick = { folder, pos ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Folder")
                    .setMessage("Are you sure you want to delete folder \"${folder.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        folders.removeAt(pos)
                        saveConfig()
                        renderFolders()
                        renderRingActions()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
    }

    private fun updateRingLayerUi(
        layerNum: Int,
        headerTv: TextView,
        addBtn: Button,
        recyclerView: RecyclerView,
        emptyTv: TextView,
        actions: MutableList<PieAction>
    ) {
        val maxLimit = when (layerNum) {
            1 -> PieConfig.MAX_LAYER1
            2 -> PieConfig.MAX_LAYER2
            else -> PieConfig.MAX_LAYER3
        }
        val ringName = when (layerNum) {
            1 -> "Inner Ring"
            2 -> "Middle Ring"
            else -> "Outer Ring"
        }
        headerTv.text = "$ringName (${actions.size} / $maxLimit actions)"
        addBtn.isEnabled = actions.size < maxLimit

        if (actions.isEmpty()) {
            emptyTv.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyTv.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        setupOrUpdateAdapter(layerNum, recyclerView, actions)
    }

    private fun updateHeadersOnly() {
        binding.tvLayer1Header.text = "Inner Ring (${config.layer1.size} / ${PieConfig.MAX_LAYER1} actions)"
        binding.btnAddLayer1.isEnabled = config.layer1.size < PieConfig.MAX_LAYER1

        binding.tvLayer2Header.text = "Middle Ring (${config.layer2.size} / ${PieConfig.MAX_LAYER2} actions)"
        binding.btnAddLayer2.isEnabled = config.layer2.size < PieConfig.MAX_LAYER2

        binding.tvLayer3Header.text = "Outer Ring (${config.layer3.size} / ${PieConfig.MAX_LAYER3} actions)"
        binding.btnAddLayer3.isEnabled = config.layer3.size < PieConfig.MAX_LAYER3
    }

    private fun setupOrUpdateAdapter(
        layerNum: Int,
        recyclerView: RecyclerView,
        actions: MutableList<PieAction>
    ) {
        val existingAdapter = recyclerView.adapter as? RingActionAdapter
        if (existingAdapter != null) {
            existingAdapter.actions = actions
            existingAdapter.notifyDataSetChanged()
            return
        }

        recyclerView.layoutManager = LinearLayoutManager(this)

        var itemTouchHelper: ItemTouchHelper? = null
        val adapter = RingActionAdapter(
            context = this,
            actions = actions,
            onActionClick = { action, _ ->
                showAddActionDialog(layerNum, action)
            },
            onActionDelete = { action, pos ->
                val ringName = when (layerNum) {
                    1 -> "Inner Ring"
                    2 -> "Middle Ring"
                    else -> "Outer Ring"
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Action")
                    .setMessage("Remove \"${action.label}\" from $ringName?")
                    .setPositiveButton("Remove") { _, _ ->
                        val targetList = when (layerNum) {
                            1 -> config.layer1
                            2 -> config.layer2
                            else -> config.layer3
                        }
                        val removed = targetList.removeAll { it.id == action.id }
                        if (!removed && pos in targetList.indices) {
                            targetList.removeAt(pos)
                        }
                        saveConfig()
                        renderRingActions()
                        Toast.makeText(this, "Removed \"${action.label}\"", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onOrderChanged = {
                saveConfig()
                updateHeadersOnly()
            },
            onStartDragListener = { holder ->
                itemTouchHelper?.startDrag(holder)
            }
        )

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    adapter.onItemMove(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                        alpha = 0.85f
                        elevation = 12f
                    }
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.apply {
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    alpha = 1.0f
                    elevation = 0f
                }
                adapter.onDragComplete()
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
        recyclerView.adapter = adapter
    }

    private fun showAddActionDialog(layerNum: Int, existingAction: PieAction? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_action, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)

        // Primary views
        val rbSystem = dialogView.findViewById<RadioButton>(R.id.rbSystem)
        val rbApp = dialogView.findViewById<RadioButton>(R.id.rbApp)
        val rbShortcut = dialogView.findViewById<RadioButton>(R.id.rbShortcut)
        val rbUrl = dialogView.findViewById<RadioButton>(R.id.rbUrl)
        val rbFolder = dialogView.findViewById<RadioButton>(R.id.rbFolder)

        val layoutSystem = dialogView.findViewById<LinearLayout>(R.id.layoutSystemActions)
        val layoutApp = dialogView.findViewById<LinearLayout>(R.id.layoutAppPicker)
        val layoutShortcut = dialogView.findViewById<LinearLayout>(R.id.layoutShortcutPicker)
        val cardSelectedShortcut = dialogView.findViewById<LinearLayout>(R.id.cardSelectedShortcut)
        val tvSelectedShortcutName = dialogView.findViewById<TextView>(R.id.tvSelectedShortcutName)
        val btnChangeShortcut = dialogView.findViewById<Button>(R.id.btnChangeShortcut)
        val layoutShortcutProviderList = dialogView.findViewById<LinearLayout>(R.id.layoutShortcutProviderList)
        val etSearchShortcut = dialogView.findViewById<EditText>(R.id.etSearchShortcut)
        val rvShortcuts = dialogView.findViewById<RecyclerView>(R.id.rvShortcutsList)
        val layoutUrl = dialogView.findViewById<LinearLayout>(R.id.layoutUrl)
        val layoutFolder = dialogView.findViewById<LinearLayout>(R.id.layoutFolderPicker)

        val spinnerSystem = dialogView.findViewById<Spinner>(R.id.spinnerSystemActions)
        val spinnerPrimaryFolder = dialogView.findViewById<Spinner>(R.id.spinnerPrimaryFolder)
        val etSearchApp = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rvAppsList)
        val etWebUrl = dialogView.findViewById<EditText>(R.id.etWebUrl)
        val etWebLabel = dialogView.findViewById<EditText>(R.id.etWebLabel)

        // Secondary views
        val switchSecondary = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchEnableSecondary)
        val layoutSecContainer = dialogView.findViewById<LinearLayout>(R.id.layoutSecondaryContainer)
        val rbSecSystem = dialogView.findViewById<RadioButton>(R.id.rbSecSystem)
        val rbSecApp = dialogView.findViewById<RadioButton>(R.id.rbSecApp)
        val rbSecShortcut = dialogView.findViewById<RadioButton>(R.id.rbSecShortcut)
        val rbSecUrl = dialogView.findViewById<RadioButton>(R.id.rbSecUrl)
        val rbSecFolder = dialogView.findViewById<RadioButton>(R.id.rbSecFolder)

        val layoutSecSystem = dialogView.findViewById<LinearLayout>(R.id.layoutSecSystemActions)
        val layoutSecApp = dialogView.findViewById<LinearLayout>(R.id.layoutSecAppPicker)
        val layoutSecShortcut = dialogView.findViewById<LinearLayout>(R.id.layoutSecShortcutPicker)
        val cardSecSelectedShortcut = dialogView.findViewById<LinearLayout>(R.id.cardSecSelectedShortcut)
        val tvSecSelectedShortcutName = dialogView.findViewById<TextView>(R.id.tvSecSelectedShortcutName)
        val btnSecChangeShortcut = dialogView.findViewById<Button>(R.id.btnSecChangeShortcut)
        val layoutSecShortcutProviderList = dialogView.findViewById<LinearLayout>(R.id.layoutSecShortcutProviderList)
        val etSecSearchShortcut = dialogView.findViewById<EditText>(R.id.etSecSearchShortcut)
        val rvSecShortcuts = dialogView.findViewById<RecyclerView>(R.id.rvSecShortcutsList)
        val layoutSecUrl = dialogView.findViewById<LinearLayout>(R.id.layoutSecUrl)
        val layoutSecFolder = dialogView.findViewById<LinearLayout>(R.id.layoutSecFolderPicker)

        val spinnerSecSystem = dialogView.findViewById<Spinner>(R.id.spinnerSecSystemActions)
        val spinnerSecFolder = dialogView.findViewById<Spinner>(R.id.spinnerSecFolder)
        val etSecSearchApp = dialogView.findViewById<EditText>(R.id.etSecSearchApp)
        val rvSecApps = dialogView.findViewById<RecyclerView>(R.id.rvSecAppsList)
        val etSecWebUrl = dialogView.findViewById<EditText>(R.id.etSecWebUrl)
        val etSecWebLabel = dialogView.findViewById<EditText>(R.id.etSecWebLabel)

        val ringName = when (layerNum) {
            1 -> "Inner Ring"
            2 -> "Middle Ring"
            else -> "Outer Ring"
        }
        tvTitle.text = if (existingAction == null) "Add Action to $ringName" else "Reassign Action in $ringName"

        // Setup System Action Spinners
        val systemActionTypes = listOf(
            ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS, ActionType.ASSISTANT, ActionType.POWER, ActionType.SCREENSHOT,
            ActionType.LOCK_SCREEN, ActionType.VOLUME_UP, ActionType.VOLUME_DOWN, ActionType.FLASHLIGHT,
            ActionType.CHECK_UPDATES
        )
        val systemLabels = systemActionTypes.map { it.defaultLabel }
        spinnerSystem.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, systemLabels)
        spinnerSecSystem.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, systemLabels)

        // Setup Folder Spinners
        val folderNames = if (config.folders.isEmpty()) listOf("No folders created yet") else config.folders.map { it.name }
        spinnerPrimaryFolder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, folderNames)
        spinnerSecFolder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, folderNames)

        // Primary Radio visibility toggles
        fun updatePrimaryVisibility() {
            layoutSystem.visibility = if (rbSystem.isChecked) View.VISIBLE else View.GONE
            layoutApp.visibility = if (rbApp.isChecked) View.VISIBLE else View.GONE
            layoutShortcut.visibility = if (rbShortcut.isChecked) View.VISIBLE else View.GONE
            layoutUrl.visibility = if (rbUrl.isChecked) View.VISIBLE else View.GONE
            layoutFolder.visibility = if (rbFolder.isChecked) View.VISIBLE else View.GONE
        }
        rbSystem.setOnClickListener { updatePrimaryVisibility() }
        rbApp.setOnClickListener { updatePrimaryVisibility() }
        rbShortcut.setOnClickListener { updatePrimaryVisibility() }
        rbUrl.setOnClickListener { updatePrimaryVisibility() }
        rbFolder.setOnClickListener { updatePrimaryVisibility() }

        // Secondary Switch & Radio toggles
        switchSecondary.setOnCheckedChangeListener { _, isChecked ->
            layoutSecContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        fun updateSecVisibility() {
            layoutSecSystem.visibility = if (rbSecSystem.isChecked) View.VISIBLE else View.GONE
            layoutSecApp.visibility = if (rbSecApp.isChecked) View.VISIBLE else View.GONE
            layoutSecShortcut.visibility = if (rbSecShortcut.isChecked) View.VISIBLE else View.GONE
            layoutSecUrl.visibility = if (rbSecUrl.isChecked) View.VISIBLE else View.GONE
            layoutSecFolder.visibility = if (rbSecFolder.isChecked) View.VISIBLE else View.GONE
        }
        rbSecSystem.setOnClickListener { updateSecVisibility() }
        rbSecApp.setOnClickListener { updateSecVisibility() }
        rbSecShortcut.setOnClickListener { updateSecVisibility() }
        rbSecUrl.setOnClickListener { updateSecVisibility() }
        rbSecFolder.setOnClickListener { updateSecVisibility() }

        // Load installed apps
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { appInfo ->
                AppEntry(
                    label = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo)
                )
            }
            .sortedBy { it.label.lowercase() }

        // Primary App Picker
        var selectedPrimaryApp: AppEntry? = if (existingAction?.type == ActionType.APP) {
            installedApps.firstOrNull { it.packageName == existingAction.data }
        } else null
        val primaryAppAdapter = AppPickerAdapter(
            items = installedApps,
            initialSelectedPackage = selectedPrimaryApp?.packageName
        ) { app -> selectedPrimaryApp = app }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = primaryAppAdapter

        etSearchApp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) installedApps else installedApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                primaryAppAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Secondary App Picker
        val existingSec = existingAction?.secondaryAction
        var selectedSecApp: AppEntry? = if (existingSec?.type == ActionType.APP) {
            installedApps.firstOrNull { it.packageName == existingSec.data }
        } else null
        val secAppAdapter = AppPickerAdapter(
            items = installedApps,
            initialSelectedPackage = selectedSecApp?.packageName
        ) { app -> selectedSecApp = app }
        rvSecApps.layoutManager = LinearLayoutManager(this)
        rvSecApps.adapter = secAppAdapter

        etSecSearchApp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) installedApps else installedApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                secAppAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Shortcut Providers Setup
        val shortcutProviders = loadShortcutProviders()

        // Primary Shortcut Setup
        var selectedPrimaryShortcut: PieAction? = if (existingAction?.type == ActionType.SHORTCUT) existingAction else null
        if (selectedPrimaryShortcut != null) {
            cardSelectedShortcut.visibility = View.VISIBLE
            layoutShortcutProviderList.visibility = View.GONE
            tvSelectedShortcutName.text = selectedPrimaryShortcut!!.label
        }
        btnChangeShortcut.setOnClickListener {
            cardSelectedShortcut.visibility = View.GONE
            layoutShortcutProviderList.visibility = View.VISIBLE
        }
        val primaryShortcutAdapter = ShortcutPickerAdapter(shortcutProviders) { provider ->
            val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                component = ComponentName(provider.packageName, provider.activityName)
            }
            onShortcutResultCallback = { action ->
                selectedPrimaryShortcut = action
                cardSelectedShortcut.visibility = View.VISIBLE
                layoutShortcutProviderList.visibility = View.GONE
                tvSelectedShortcutName.text = action.label
            }
            try {
                shortcutLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open shortcut picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        rvShortcuts.layoutManager = LinearLayoutManager(this)
        rvShortcuts.adapter = primaryShortcutAdapter

        etSearchShortcut.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) shortcutProviders else shortcutProviders.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                primaryShortcutAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Secondary Shortcut Setup
        var selectedSecShortcut: PieAction? = if (existingSec?.type == ActionType.SHORTCUT) existingSec else null
        if (selectedSecShortcut != null) {
            cardSecSelectedShortcut.visibility = View.VISIBLE
            layoutSecShortcutProviderList.visibility = View.GONE
            tvSecSelectedShortcutName.text = selectedSecShortcut!!.label
        }
        btnSecChangeShortcut.setOnClickListener {
            cardSecSelectedShortcut.visibility = View.GONE
            layoutSecShortcutProviderList.visibility = View.VISIBLE
        }
        val secShortcutAdapter = ShortcutPickerAdapter(shortcutProviders) { provider ->
            val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                component = ComponentName(provider.packageName, provider.activityName)
            }
            onShortcutResultCallback = { action ->
                selectedSecShortcut = action
                cardSecSelectedShortcut.visibility = View.VISIBLE
                layoutSecShortcutProviderList.visibility = View.GONE
                tvSecSelectedShortcutName.text = action.label
            }
            try {
                shortcutLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open shortcut picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        rvSecShortcuts.layoutManager = LinearLayoutManager(this)
        rvSecShortcuts.adapter = secShortcutAdapter

        etSecSearchShortcut.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) shortcutProviders else shortcutProviders.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                secShortcutAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Populate existing primary action
        if (existingAction != null) {
            when (existingAction.type) {
                ActionType.APP -> {
                    rbApp.isChecked = true
                    updatePrimaryVisibility()
                }
                ActionType.SHORTCUT -> {
                    rbShortcut.isChecked = true
                    updatePrimaryVisibility()
                }
                ActionType.URL -> {
                    rbUrl.isChecked = true
                    updatePrimaryVisibility()
                    etWebUrl.setText(existingAction.data)
                    etWebLabel.setText(existingAction.label)
                }
                ActionType.FOLDER -> {
                    rbFolder.isChecked = true
                    updatePrimaryVisibility()
                    val folderIdx = config.folders.indexOfFirst { it.id == existingAction.data }
                    if (folderIdx != -1) spinnerPrimaryFolder.setSelection(folderIdx)
                }
                else -> {
                    rbSystem.isChecked = true
                    updatePrimaryVisibility()
                    val sysIdx = systemActionTypes.indexOf(existingAction.type)
                    if (sysIdx != -1) spinnerSystem.setSelection(sysIdx)
                }
            }

            // Populate existing secondary action
            if (existingSec != null) {
                switchSecondary.isChecked = true
                layoutSecContainer.visibility = View.VISIBLE
                when (existingSec.type) {
                    ActionType.APP -> {
                        rbSecApp.isChecked = true
                        updateSecVisibility()
                    }
                    ActionType.SHORTCUT -> {
                        rbSecShortcut.isChecked = true
                        updateSecVisibility()
                    }
                    ActionType.URL -> {
                        rbSecUrl.isChecked = true
                        updateSecVisibility()
                        etSecWebUrl.setText(existingSec.data)
                        etSecWebLabel.setText(existingSec.label)
                    }
                    ActionType.FOLDER -> {
                        rbSecFolder.isChecked = true
                        updateSecVisibility()
                        val folderIdx = config.folders.indexOfFirst { it.id == existingSec.data }
                        if (folderIdx != -1) spinnerSecFolder.setSelection(folderIdx)
                    }
                    else -> {
                        rbSecSystem.isChecked = true
                        updateSecVisibility()
                        val sysIdx = systemActionTypes.indexOf(existingSec.type)
                        if (sysIdx != -1) spinnerSecSystem.setSelection(sysIdx)
                    }
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.show()

        // Set generous spacious dialog dimensions to prevent content clipping
        val displayMetrics = resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.96).toInt()
        val dialogHeight = (displayMetrics.heightPixels * 0.90).toInt()
        dialog.window?.setLayout(dialogWidth, dialogHeight)

        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnDialogSave)
        btnSave.text = if (existingAction == null) "Add Action" else "Save Changes"

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val targetList = when (layerNum) {
                1 -> config.layer1
                2 -> config.layer2
                else -> config.layer3
            }
            val maxLimit = when (layerNum) {
                1 -> PieConfig.MAX_LAYER1
                2 -> PieConfig.MAX_LAYER2
                else -> PieConfig.MAX_LAYER3
            }

            if (existingAction == null && targetList.size >= maxLimit) {
                Toast.makeText(this, "Maximum limit reached for this ring", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setOnClickListener
            }

            // Construct Primary Action
            val primaryAction: PieAction? = when {
                rbSystem.isChecked -> {
                    val idx = spinnerSystem.selectedItemPosition
                    val selectedType = systemActionTypes[idx]
                    PieAction(type = selectedType, label = selectedType.defaultLabel)
                }
                rbApp.isChecked -> {
                    if (selectedPrimaryApp == null) {
                        Toast.makeText(this, "Please select an application from the list", Toast.LENGTH_SHORT).show()
                        null
                    } else {
                        PieAction(type = ActionType.APP, label = selectedPrimaryApp!!.label, data = selectedPrimaryApp!!.packageName)
                    }
                }
                rbShortcut.isChecked -> {
                    if (selectedPrimaryShortcut == null) {
                        Toast.makeText(this, "Please select an in-app shortcut from the list", Toast.LENGTH_SHORT).show()
                        null
                    } else {
                        selectedPrimaryShortcut
                    }
                }
                rbUrl.isChecked -> {
                    val url = etWebUrl.text?.toString()?.trim() ?: ""
                    val label = etWebLabel.text?.toString()?.trim() ?: ""
                    if (url.isEmpty()) {
                        Toast.makeText(this, "Please enter a valid website URL", Toast.LENGTH_SHORT).show()
                        null
                    } else {
                        val displayLabel = if (label.isNotEmpty()) label else "Website"
                        PieAction(type = ActionType.URL, label = displayLabel, data = url)
                    }
                }
                rbFolder.isChecked -> {
                    if (config.folders.isEmpty()) {
                        Toast.makeText(this, "No folders available. Create a folder first.", Toast.LENGTH_SHORT).show()
                        null
                    } else {
                        val folder = config.folders[spinnerPrimaryFolder.selectedItemPosition]
                        PieAction(type = ActionType.FOLDER, label = folder.name, data = folder.id)
                    }
                }
                else -> null
            }

            // Construct Secondary Action
            val secondaryAction: PieAction? = if (switchSecondary.isChecked) {
                when {
                    rbSecSystem.isChecked -> {
                        val idx = spinnerSecSystem.selectedItemPosition
                        val selectedType = systemActionTypes[idx]
                        PieAction(type = selectedType, label = selectedType.defaultLabel)
                    }
                    rbSecApp.isChecked -> {
                        if (selectedSecApp == null) {
                            Toast.makeText(this, "Please select a secondary application", Toast.LENGTH_SHORT).show()
                            null
                        } else {
                            PieAction(type = ActionType.APP, label = selectedSecApp!!.label, data = selectedSecApp!!.packageName)
                        }
                    }
                    rbSecShortcut.isChecked -> {
                        if (selectedSecShortcut == null) {
                            Toast.makeText(this, "Please select a secondary in-app shortcut", Toast.LENGTH_SHORT).show()
                            null
                        } else {
                            selectedSecShortcut
                        }
                    }
                    rbSecUrl.isChecked -> {
                        val url = etSecWebUrl.text?.toString()?.trim() ?: ""
                        val label = etSecWebLabel.text?.toString()?.trim() ?: ""
                        if (url.isEmpty()) {
                            Toast.makeText(this, "Please enter a secondary website URL", Toast.LENGTH_SHORT).show()
                            null
                        } else {
                            val displayLabel = if (label.isNotEmpty()) label else "Website"
                            PieAction(type = ActionType.URL, label = displayLabel, data = url)
                        }
                    }
                    rbSecFolder.isChecked -> {
                        if (config.folders.isEmpty()) {
                            Toast.makeText(this, "No folders available for secondary.", Toast.LENGTH_SHORT).show()
                            null
                        } else {
                            val folder = config.folders[spinnerSecFolder.selectedItemPosition]
                            PieAction(type = ActionType.FOLDER, label = folder.name, data = folder.id)
                        }
                    }
                    else -> null
                }
            } else null

            if (primaryAction != null) {
                val finalAction = primaryAction.copy(
                    id = existingAction?.id ?: primaryAction.id,
                    secondaryAction = secondaryAction
                )
                if (existingAction != null) {
                    val idx = targetList.indexOfFirst { it.id == existingAction.id }
                    if (idx != -1) {
                        targetList[idx] = finalAction
                    } else {
                        targetList.add(finalAction)
                    }
                } else {
                    targetList.add(finalAction)
                }
                saveConfig()
                renderRingActions()
                dialog.dismiss()
            }
        }
    }

    private fun showFolderEditDialog(existingFolder: PieFolder?, position: Int = -1) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_folder, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvFolderDialogTitle)
        val etName = dialogView.findViewById<EditText>(R.id.etFolderName)
        val rgIcon = dialogView.findViewById<RadioGroup>(R.id.rgFolderIcon)
        val rbIconDefault = dialogView.findViewById<RadioButton>(R.id.rbIconDefault)
        val rbIconApps = dialogView.findViewById<RadioButton>(R.id.rbIconApps)
        val rbIconTools = dialogView.findViewById<RadioButton>(R.id.rbIconTools)
        val rbIconStar = dialogView.findViewById<RadioButton>(R.id.rbIconStar)
        val btnAddAction = dialogView.findViewById<Button>(R.id.btnAddFolderAction)
        val rvFolderItems = dialogView.findViewById<RecyclerView>(R.id.rvFolderItemList)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptyFolderActions)

        tvTitle.text = if (existingFolder == null) "Create Action Folder" else "Edit Folder"
        val folderActions = existingFolder?.actions?.toMutableList() ?: mutableListOf<PieAction>()

        if (existingFolder != null) {
            etName.setText(existingFolder.name)
            when (existingFolder.iconName) {
                "ic_folder_apps" -> rbIconApps.isChecked = true
                "ic_folder_tools" -> rbIconTools.isChecked = true
                "ic_folder_star" -> rbIconStar.isChecked = true
                else -> rbIconDefault.isChecked = true
            }
        }

        fun updateFolderItemsUi() {
            if (folderActions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvFolderItems.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvFolderItems.visibility = View.VISIBLE
            }
        }

        val folderAdapter = RingActionAdapter(
            context = this,
            actions = folderActions,
            onActionClick = { _, _ -> },
            onActionDelete = { _, pos ->
                folderActions.removeAt(pos)
                rvFolderItems.adapter?.notifyItemRemoved(pos)
                updateFolderItemsUi()
            },
            onOrderChanged = {}
        )
        rvFolderItems.layoutManager = LinearLayoutManager(this)
        rvFolderItems.adapter = folderAdapter
        updateFolderItemsUi()

        btnAddAction.setOnClickListener {
            showSimpleActionSelectorDialog { newAction ->
                folderActions.add(newAction)
                folderAdapter.notifyItemInserted(folderActions.size - 1)
                updateFolderItemsUi()
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.show()

        val displayMetrics = resources.displayMetrics
        dialog.window?.setLayout((displayMetrics.widthPixels * 0.96).toInt(), (displayMetrics.heightPixels * 0.90).toInt())

        val btnCancel = dialogView.findViewById<Button>(R.id.btnFolderCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnFolderSave)
        btnSave.text = if (existingFolder == null) "Create Folder" else "Save Folder"

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a folder name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val iconName = when {
                rbIconApps.isChecked -> "ic_folder_apps"
                rbIconTools.isChecked -> "ic_folder_tools"
                rbIconStar.isChecked -> "ic_folder_star"
                else -> "ic_action_folder"
            }

            val finalFolder = PieFolder(
                id = existingFolder?.id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                iconName = iconName,
                actions = folderActions
            )

            if (position >= 0 && position < config.folders.size) {
                config.folders[position] = finalFolder
            } else {
                config.folders.add(finalFolder)
            }

            saveConfig()
            renderFolders()
            renderRingActions()
            dialog.dismiss()
        }
    }

    private fun showSimpleActionSelectorDialog(onActionSelected: (PieAction) -> Unit) {
        val systemActionTypes = listOf(
            ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS, ActionType.ASSISTANT, ActionType.POWER, ActionType.SCREENSHOT,
            ActionType.LOCK_SCREEN, ActionType.VOLUME_UP, ActionType.VOLUME_DOWN, ActionType.FLASHLIGHT
        )
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.label.lowercase() }

        val options = arrayOf("System Action", "Installed App", "In-App Shortcut")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Action to Folder")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val labels = systemActionTypes.map { it.defaultLabel }.toTypedArray()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Select System Action")
                            .setItems(labels) { _, idx ->
                                val type = systemActionTypes[idx]
                                onActionSelected(PieAction(type = type, label = type.defaultLabel))
                            }
                            .show()
                    }
                    1 -> {
                        val appLabels = installedApps.map { it.label }.toTypedArray()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Select App")
                            .setItems(appLabels) { _, idx ->
                                val app = installedApps[idx]
                                onActionSelected(PieAction(type = ActionType.APP, label = app.label, data = app.packageName))
                            }
                            .show()
                    }
                    2 -> {
                        val providers = loadShortcutProviders()
                        if (providers.isEmpty()) {
                            Toast.makeText(this, "No apps offering shortcuts found", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val providerNames = providers.map { it.label }.toTypedArray()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Select Shortcut App")
                            .setItems(providerNames) { _, pIdx ->
                                val provider = providers[pIdx]
                                val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                                    component = ComponentName(provider.packageName, provider.activityName)
                                }
                                onShortcutResultCallback = { action ->
                                    onActionSelected(action)
                                }
                                try {
                                    shortcutLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Could not open shortcut picker: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showImportOptionsDialog() {
        val options = arrayOf("Import from JSON File", "Paste JSON Text")
        MaterialAlertDialogBuilder(this)
            .setTitle("Import Configuration")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFileLauncher.launch("*/*")
                    1 -> showPasteJsonDialog()
                }
            }
            .show()
    }

    private fun showPasteJsonDialog() {
        val editText = EditText(this).apply {
            hint = "Paste configuration JSON here..."
            setPadding(32, 32, 32, 32)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Paste Configuration")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val json = editText.text.toString().trim()
                if (prefsManager.importConfigJson(json)) {
                    config = prefsManager.loadConfig()
                    notifyServiceConfigChanged()
                    bindConfigToUi()
                    Toast.makeText(this, "Configuration imported successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid JSON format", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfig() {
        prefsManager.saveConfig(config)
        notifyServiceConfigChanged()
    }

    private fun notifyServiceConfigChanged() {
        if (PieOverlayService.isRunning) {
            val intent = Intent(this, PieOverlayService::class.java).apply {
                action = PieOverlayService.ACTION_RELOAD_CONFIG
            }
            startService(intent)
        }
    }

    private fun setServicePreviewMode(enabled: Boolean) {
        if (PieOverlayService.isRunning) {
            val intent = Intent(this, PieOverlayService::class.java).apply {
                action = PieOverlayService.ACTION_SET_PREVIEW_MODE
                putExtra(PieOverlayService.EXTRA_PREVIEW_MODE, enabled)
            }
            startService(intent)
        }
    }

    private fun ensureServiceState() {
        if (config.enabled && Settings.canDrawOverlays(this)) {
            val serviceIntent = Intent(this, PieOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Handled gracefully
            }
        }
    }

    // Helper data and adapter for App Picker
    data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

    class AppPickerAdapter(
        private var items: List<AppEntry>,
        initialSelectedPackage: String? = null,
        private val onSelect: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

        private var selectedPosition = if (initialSelectedPackage != null) {
            items.indexOfFirst { it.packageName == initialSelectedPackage }
        } else -1

        fun updateList(newItems: List<AppEntry>) {
            items = newItems
            selectedPosition = -1
            notifyDataSetChanged()
        }

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.label
            holder.ivIcon.setImageDrawable(item.icon)

            if (position == selectedPosition) {
                holder.view.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                holder.view.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.view.setOnClickListener {
                val prev = selectedPosition
                selectedPosition = holder.adapterPosition
                if (prev != -1) notifyItemChanged(prev)
                notifyItemChanged(selectedPosition)
                onSelect(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // Helper data and adapter for Shortcut Provider Picker
    data class ShortcutProviderEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable
    )

    private fun loadShortcutProviders(): List<ShortcutProviderEntry> {
        val pm = packageManager
        val queryIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        return pm.queryIntentActivities(queryIntent, 0).map { resolveInfo ->
            ShortcutProviderEntry(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = resolveInfo.activityInfo.packageName,
                activityName = resolveInfo.activityInfo.name,
                icon = resolveInfo.loadIcon(pm)
            )
        }.sortedBy { it.label.lowercase() }
    }

    class ShortcutPickerAdapter(
        private var items: List<ShortcutProviderEntry>,
        private val onSelect: (ShortcutProviderEntry) -> Unit
    ) : RecyclerView.Adapter<ShortcutPickerAdapter.ViewHolder>() {

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.label
            holder.ivIcon.setImageDrawable(item.icon)
            holder.view.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newItems: List<ShortcutProviderEntry>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
