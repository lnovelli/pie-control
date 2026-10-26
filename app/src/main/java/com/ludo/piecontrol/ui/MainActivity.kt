package com.ludo.piecontrol.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.text.DateFormat
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
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
import com.ludo.piecontrol.usage.UsageAccess
import com.ludo.piecontrol.usage.UsageSuggestion
import com.ludo.piecontrol.usage.UsageSuggestionNotifications
import com.ludo.piecontrol.usage.UsageSuggestionPreferences
import com.ludo.piecontrol.usage.UsageSuggestionScheduler
import com.ludo.piecontrol.usage.UsageSuggestionSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_USAGE_SUGGESTIONS = "open_usage_suggestions"
        private const val USAGE_SUGGESTIONS_TAB_POSITION = 4
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var usagePreferences: UsageSuggestionPreferences
    private lateinit var config: PieConfig
    private var isBindingConfig = false
    private var isBindingUsageSettings = false
    private var usageRenderGeneration = 0
    private var isUsageCheckRunning = false
    private var usageCheckStartedAt = 0L
    private var selectedSettingsTab = 0
    private var tabScrollPositions = IntArray(5)
    private var configSavePending = false
    private val persistConfigRunnable = Runnable { saveConfig() }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pickerIconCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val usageNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val settings = usagePreferences.settings()
            usagePreferences.saveSettings(settings.copy(notificationsEnabled = granted))
            if (granted && settings.enabled) UsageSuggestionScheduler.requestCheck(this)
            if (!granted) {
                Toast.makeText(this, R.string.usage_notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
            refreshUsageSuggestionsUi()
        }

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
        val callback = onShortcutResultCallback
        onShortcutResultCallback = null
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
                callback?.invoke(action)
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
        usagePreferences = UsageSuggestionPreferences(this)
        config = prefsManager.loadConfig()

        setupSettingsTabs(savedInstanceState)
        setupPermissionsUi()
        bindConfigToUi()
        setupListeners()
        setupUsageSuggestionsUi()
        observeUsageChecks()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_USAGE_SUGGESTIONS, false) == true) {
            binding.settingsTabs.getTabAt(USAGE_SUGGESTIONS_TAB_POSITION)?.select()
        }
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
        val usageSettings = usagePreferences.settings()
        if (!usageSettings.enabled) {
            UsageSuggestionScheduler.cancel(this)
        } else if (!UsageAccess.isGranted(this)) {
            UsageSuggestionScheduler.cancel(this)
            usagePreferences.clearAnalysis()
            UsageSuggestionNotifications.cancel(this)
        } else {
            UsageSuggestionScheduler.sync(this)
            if (usagePreferences.lastCheckedAt() == 0L) UsageSuggestionScheduler.requestCheck(this)
        }
        refreshUsageSuggestionsUi()
    }

    override fun onPause() {
        if (configSavePending) saveConfig()
        super.onPause()
        setServicePreviewMode(false)
    }

    override fun onDestroy() {
        activityScope.cancel()
        pickerIconCache.evictAll()
        binding.root.removeCallbacks(persistConfigRunnable)
        onShortcutResultCallback = null
        setServicePreviewMode(false)
        super.onDestroy()
    }

    private fun setupSettingsTabs(savedInstanceState: Bundle?) {
        val titles = listOf(
            R.string.tab_general, R.string.tab_appearance, R.string.tab_actions,
            R.string.tab_folders, R.string.tab_usage_suggestions, R.string.tab_more
        )
        val summaries = listOf(
            R.string.tab_general_summary, R.string.tab_appearance_summary,
            R.string.tab_actions_summary, R.string.tab_folders_summary,
            R.string.tab_usage_suggestions_summary, R.string.tab_more_summary
        )
        val sections = listOf(
            listOf(binding.cardMaster, binding.cardPermissions, binding.cardTriggers),
            listOf(binding.cardAppearance),
            listOf(binding.cardLayer1, binding.cardLayer2, binding.cardLayer3),
            listOf(binding.cardFolders),
            listOf(binding.cardUsageSuggestions),
            listOf(binding.cardBackup, binding.cardAbout)
        )
        tabScrollPositions = savedInstanceState?.getIntArray("tab_scroll_positions")
            ?.takeIf { it.size == titles.size } ?: IntArray(titles.size)
        selectedSettingsTab = (savedInstanceState?.getInt("settings_tab") ?: 0).coerceIn(titles.indices)

        fun showTab(position: Int) {
            sections.forEachIndexed { index, cards ->
                cards.forEach { it.visibility = if (index == position) View.VISIBLE else View.GONE }
            }
            binding.settingsTitle.setText(titles[position])
            binding.settingsSummary.setText(summaries[position])
            binding.settingsScroll.doOnLayout {
                if (selectedSettingsTab == position) {
                    binding.settingsScroll.scrollTo(0, tabScrollPositions[position])
                }
            }
        }

        titles.forEach { binding.settingsTabs.addTab(binding.settingsTabs.newTab().setText(it), false) }
        binding.settingsTabs.getTabAt(selectedSettingsTab)?.select()
        showTab(selectedSettingsTab)
        binding.settingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabScrollPositions[selectedSettingsTab] = binding.settingsScroll.scrollY
                selectedSettingsTab = tab.position
                showTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupUsageSuggestionsUi() {
        val settings = usagePreferences.settings()
        val frequencyDays = intArrayOf(1, 2, 3, 7)
        val windowDays = intArrayOf(3, 7, 14, 30)
        val frequencyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.usage_frequency_options)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val windowAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resources.getStringArray(R.array.usage_window_options)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        isBindingUsageSettings = true
        binding.spinnerUsageFrequency.adapter = frequencyAdapter
        binding.spinnerUsageFrequency.setSelection(frequencyDays.indexOf(settings.checkIntervalDays).coerceAtLeast(0))
        binding.spinnerUsageWindow.adapter = windowAdapter
        binding.spinnerUsageWindow.setSelection(windowDays.indexOf(settings.analysisWindowDays).coerceAtLeast(0))
        binding.switchUsageSuggestions.isChecked = settings.enabled
        binding.switchUsageNotifications.isChecked = settings.notificationsEnabled
        binding.sliderUsageMinimumVisits.value = settings.minimumVisits.coerceIn(2, 20).toFloat()
        configureMinimumDaysSlider(settings.analysisWindowDays, settings.minimumActiveDays)
        updateUsageHeuristicLabels(settings)
        isBindingUsageSettings = false

        binding.btnUsageAccess.setOnClickListener {
            try {
                startActivity(UsageAccess.settingsIntent())
            } catch (_: Exception) {
                Toast.makeText(this, R.string.usage_access_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnUsageCheckNow.setOnClickListener {
            val current = usagePreferences.settings()
            if (!current.enabled || !UsageAccess.isGranted(this)) return@setOnClickListener
            usageCheckStartedAt = System.currentTimeMillis()
            if (UsageSuggestionScheduler.requestCheck(this)) {
                isUsageCheckRunning = true
                updateUsageCheckButton()
            } else {
                isUsageCheckRunning = false
                updateUsageCheckButton()
            }
        }
        binding.switchUsageSuggestions.setOnCheckedChangeListener { _, enabled ->
            if (isBindingUsageSettings) return@setOnCheckedChangeListener
            val updated = usagePreferences.settings().copy(enabled = enabled)
            usagePreferences.saveSettings(updated)
            if (!enabled) {
                usagePreferences.clearAnalysis()
                UsageSuggestionNotifications.cancel(this)
            }
            UsageSuggestionScheduler.sync(this)
            if (enabled) UsageSuggestionScheduler.requestCheck(this)
            refreshUsageSuggestionsUi()
        }
        binding.switchUsageNotifications.setOnCheckedChangeListener { _, enabled ->
            if (isBindingUsageSettings) return@setOnCheckedChangeListener
            if (!enabled) {
                usagePreferences.saveSettings(usagePreferences.settings().copy(notificationsEnabled = false))
                UsageSuggestionNotifications.cancel(this)
                refreshUsageSuggestionsUi()
                return@setOnCheckedChangeListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                isBindingUsageSettings = true
                binding.switchUsageNotifications.isChecked = false
                isBindingUsageSettings = false
                usageNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val settings = usagePreferences.settings()
                usagePreferences.saveSettings(settings.copy(notificationsEnabled = true))
                if (settings.enabled) UsageSuggestionScheduler.requestCheck(this)
                refreshUsageSuggestionsUi()
            }
        }
        binding.spinnerUsageFrequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isBindingUsageSettings || position !in frequencyDays.indices) return
                val current = usagePreferences.settings()
                val selectedInterval = frequencyDays[position]
                if (current.checkIntervalDays == selectedInterval) return
                val updated = current.copy(checkIntervalDays = selectedInterval)
                usagePreferences.saveSettings(updated)
                UsageSuggestionScheduler.sync(this@MainActivity)
                refreshUsageSuggestionsUi()
            }
        }
        binding.spinnerUsageWindow.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isBindingUsageSettings || position !in windowDays.indices) return
                val current = usagePreferences.settings()
                val selectedWindow = windowDays[position]
                if (current.analysisWindowDays == selectedWindow) return
                val updated = current.copy(
                    analysisWindowDays = selectedWindow,
                    minimumActiveDays = current.minimumActiveDays.coerceAtMost(selectedWindow)
                )
                usagePreferences.saveSettings(updated)
                UsageSuggestionScheduler.requestCheck(this@MainActivity)
                isBindingUsageSettings = true
                configureMinimumDaysSlider(selectedWindow, updated.minimumActiveDays)
                updateUsageHeuristicLabels(updated)
                isBindingUsageSettings = false
                refreshUsageSuggestionsUi()
            }
        }
        binding.sliderUsageMinimumVisits.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateUsageHeuristicLabels(usagePreferences.settings().copy(minimumVisits = value.toInt()))
        }
        binding.sliderUsageMinimumVisits.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                usagePreferences.saveSettings(usagePreferences.settings().copy(minimumVisits = slider.value.toInt()))
                UsageSuggestionScheduler.requestCheck(this@MainActivity)
                refreshUsageSuggestionsUi()
            }
        })
        binding.sliderUsageMinimumDays.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateUsageHeuristicLabels(usagePreferences.settings().copy(minimumActiveDays = value.toInt()))
        }
        binding.sliderUsageMinimumDays.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                usagePreferences.saveSettings(usagePreferences.settings().copy(minimumActiveDays = slider.value.toInt()))
                UsageSuggestionScheduler.requestCheck(this@MainActivity)
                refreshUsageSuggestionsUi()
            }
        })
        refreshUsageSuggestionsUi()
    }

    private fun observeUsageChecks() {
        UsageSuggestionScheduler.observeOnDemandWork(this).observe(this) { workInfos ->
            val wasRunning = isUsageCheckRunning
            isUsageCheckRunning = workInfos.any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
            if (wasRunning && !isUsageCheckRunning) {
                val finalState = workInfos.lastOrNull()?.state
                val failedManualCheck = finalState == WorkInfo.State.FAILED &&
                    usageCheckStartedAt > 0L && usagePreferences.lastCheckedAt() < usageCheckStartedAt
                usageCheckStartedAt = 0L
                refreshUsageSuggestionsUi()
                if (failedManualCheck) {
                    Toast.makeText(this, R.string.usage_check_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                updateUsageCheckButton()
            }
        }
    }

    private fun updateUsageCheckButton() {
        val settings = usagePreferences.settings()
        val canCheck = settings.enabled && UsageAccess.isGranted(this)
        binding.btnUsageCheckNow.isEnabled = canCheck && !isUsageCheckRunning
        binding.btnUsageCheckNow.setText(
            if (isUsageCheckRunning) R.string.usage_checking_now else R.string.usage_check_now
        )
    }

    private fun configureMinimumDaysSlider(windowDays: Int, selectedDays: Int) {
        val slider = binding.sliderUsageMinimumDays
        val safeWindow = windowDays.coerceIn(3, 30).toFloat()
        val safeValue = selectedDays.coerceIn(1, safeWindow.toInt()).toFloat()
        if (safeWindow < slider.valueTo) {
            slider.value = safeValue.coerceAtMost(slider.valueTo)
            slider.valueTo = safeWindow
        } else {
            slider.valueTo = safeWindow
            slider.value = safeValue
        }
    }

    private fun updateUsageHeuristicLabels(settings: UsageSuggestionSettings) {
        binding.tvUsageMinimumVisits.text = getString(
            R.string.usage_minimum_visits,
            settings.minimumVisits.coerceIn(2, 20)
        )
        binding.tvUsageMinimumDays.text = getString(
            R.string.usage_minimum_days,
            settings.minimumActiveDays.coerceIn(1, settings.analysisWindowDays)
        )
    }

    private fun refreshUsageSuggestionsUi() {
        if (!::usagePreferences.isInitialized) return
        val settings = usagePreferences.settings()
        val hasUsageAccess = UsageAccess.isGranted(this)
        isBindingUsageSettings = true
        binding.switchUsageSuggestions.isChecked = settings.enabled
        binding.switchUsageNotifications.isChecked = settings.notificationsEnabled
        binding.btnUsageAccess.setText(if (hasUsageAccess) R.string.usage_manage_access else R.string.usage_grant_access)
        binding.tvUsageAccessStatus.setText(if (hasUsageAccess) R.string.usage_access_granted else R.string.usage_access_missing)
        binding.tvUsageFeatureStatus.setText(
            when {
                !settings.enabled -> R.string.usage_feature_disabled
                !hasUsageAccess -> R.string.usage_waiting_for_access
                usagePreferences.lastCheckedAt() == 0L -> R.string.usage_waiting_for_scan
                else -> R.string.usage_feature_active
            }
        )
        val mapped = mappedAppPackages(config)
        val suggestionsToShow = usagePreferences.suggestions().filterNot { it.packageName in mapped }
        val checkedAt = usagePreferences.lastCheckedAt()
        binding.tvUsageLastChecked.text = if (checkedAt == 0L) {
            getString(R.string.usage_not_checked_yet)
        } else {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date(checkedAt))
            getString(R.string.usage_last_checked, date, suggestionsToShow.size)
        }
        isBindingUsageSettings = false
        updateUsageCheckButton()

        val generation = ++usageRenderGeneration
        activityScope.launch {
            val displayItems = withContext(Dispatchers.IO) {
                suggestionsToShow.mapNotNull { suggestion ->
                    try {
                        val appInfo = packageManager.getApplicationInfo(suggestion.packageName, 0)
                        val label = packageManager.getApplicationLabel(appInfo).toString()
                        UsageSuggestionDisplay(suggestion, label)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            }
            if (generation != usageRenderGeneration || isFinishing || isDestroyed) return@launch
            renderUsageSuggestionRows(displayItems, settings.analysisWindowDays)
        }
    }

    private data class UsageSuggestionDisplay(val suggestion: UsageSuggestion, val label: String)

    private fun renderUsageSuggestionRows(items: List<UsageSuggestionDisplay>, windowDays: Int) {
        binding.usageSuggestionsList.removeAllViews()
        binding.tvUsageSuggestionsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val selectableRowBackground = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val selectableButtonBackground = android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                if (selectableRowBackground.resourceId != 0) {
                    foreground = ContextCompat.getDrawable(this@MainActivity, selectableRowBackground.resourceId)
                }
                setPadding(dp(12), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { openSuggestedApp(item.suggestion.packageName) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(this).apply {
                text = item.label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val details = TextView(this).apply {
                text = getString(R.string.usage_candidate_details, item.suggestion.visits, item.suggestion.activeDays, windowDays)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            }
            textColumn.addView(title)
            textColumn.addView(details)
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val muted = usagePreferences.isNotificationMuted(item.suggestion.packageName)
            val muteButton = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (selectableButtonBackground.resourceId != 0) setBackgroundResource(selectableButtonBackground.resourceId)
                setImageResource(if (muted) R.drawable.ic_notifications_off else R.drawable.ic_action_notifications)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                contentDescription = getString(
                    if (muted) R.string.usage_unmute_app_notifications else R.string.usage_mute_app_notifications,
                    item.label
                )
                setOnClickListener {
                    usagePreferences.setNotificationMuted(item.suggestion.packageName, !muted)
                    refreshUsageSuggestionsUi()
                }
            }
            row.addView(muteButton)
            binding.usageSuggestionsList.addView(row)
        }
    }

    private fun openSuggestedApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Toast.makeText(this, R.string.usage_app_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            launchIntent.setPackage(packageName)
            startActivity(launchIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.usage_app_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.usage_app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mappedAppPackages(currentConfig: PieConfig): Set<String> {
        val mapped = mutableSetOf<String>()
        val visitedFolders = mutableSetOf<String>()
        fun visit(action: PieAction) {
            when (action.type) {
                ActionType.APP -> action.data.takeIf { it.isNotBlank() }?.let(mapped::add)
                ActionType.SHORTCUT -> if (action.data.isNotBlank()) {
                    try {
                        val shortcutIntent = Intent.parseUri(action.data, Intent.URI_INTENT_SCHEME)
                        (shortcutIntent.`package` ?: shortcutIntent.component?.packageName)?.let(mapped::add)
                    } catch (_: Exception) {
                        // Ignore invalid shortcut data while determining ring coverage.
                    }
                }
                ActionType.FOLDER -> {
                    val folder = currentConfig.folders.firstOrNull { it.id == action.data || it.name == action.data }
                    if (folder != null && visitedFolders.add(folder.id)) folder.actions.forEach(::visit)
                }
                else -> Unit
            }
            action.secondaryAction?.let(::visit)
        }
        (currentConfig.layer1 + currentConfig.layer2 + currentConfig.layer3).forEach(::visit)
        return mapped
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        tabScrollPositions[selectedSettingsTab] = binding.settingsScroll.scrollY
        outState.putInt("settings_tab", selectedSettingsTab)
        outState.putIntArray("tab_scroll_positions", tabScrollPositions)
        super.onSaveInstanceState(outState)
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
        isBindingConfig = true
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
        isBindingConfig = false
        ensureServiceState()
    }

    private fun setupListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
            config.enabled = isChecked
            saveConfig()
            binding.tvMasterStatus.text = if (isChecked) getString(R.string.status_enabled) else getString(R.string.status_disabled)
            ensureServiceState()
        }

        binding.switchLeftEdge.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
            config.leftEdgeEnabled = isChecked
            saveConfig()
        }

        binding.switchRightEdge.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
            config.rightEdgeEnabled = isChecked
            saveConfig()
        }

        binding.switchBottomEdge.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
            config.bottomEdgeEnabled = isChecked
            saveConfig()
        }

        binding.sliderTriggerHeight.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.triggerHeightDp = value.toInt()
            binding.tvTriggerHeightLabel.text = "Trigger Height: ${config.triggerHeightDp} dp"
            scheduleConfigSave()
        }

        binding.sliderTriggerWidth.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.triggerWidthDp = value.toInt()
            binding.tvTriggerWidthLabel.text = "Trigger Thickness: ${config.triggerWidthDp} dp"
            scheduleConfigSave()
        }

        binding.sliderTriggerPos.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.triggerPositionPercent = value.toInt()
            binding.tvTriggerPosLabel.text = "Vertical Position: ${config.triggerPositionPercent}%"
            scheduleConfigSave()
        }

        binding.switchShowIndicator.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
            config.showTriggerIndicator = isChecked
            saveConfig()
        }

        binding.sliderPieScale.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.pieScalePercent = value.toInt()
            binding.tvPieScaleLabel.text = "Semicircle Scale: ${config.pieScalePercent}%"
            scheduleConfigSave()
        }

        binding.sliderIconScale.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.iconScalePercent = value.toInt()
            binding.tvIconScaleLabel.text = "Icon Scale: ${config.iconScalePercent}%"
            scheduleConfigSave()
        }

        binding.sliderPieAlpha.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.pieAlphaPercent = value.toInt()
            binding.tvPieAlphaLabel.text = "Opacity: ${config.pieAlphaPercent}%"
            scheduleConfigSave()
        }

        binding.sliderInnerRadius.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.innerRadiusDp = value.toInt()
            binding.tvInnerRadiusLabel.text = "Distance from Finger: ${config.innerRadiusDp} dp"
            scheduleConfigSave()
        }

        binding.sliderShortHoverDelay.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.shortHoverDelayMs = value.toInt()
            binding.tvShortHoverLabel.text = if (config.shortHoverDelayMs == 0) "Short Hover Activation: Instant (0 ms)" else "Short Hover Activation: ${config.shortHoverDelayMs} ms"
            scheduleConfigSave()
        }

        binding.sliderSecondaryHoldDelay.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            config.secondaryHoldDelayMs = value.toInt()
            binding.tvSecondaryHoldLabel.text = "Secondary Action Hold Trigger: ${config.secondaryHoldDelayMs} ms"
            scheduleConfigSave()
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingConfig) return@setOnCheckedChangeListener
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
        binding.tvUpdateStatus.text = "Checking GitHub Releases for updates..."
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
        binding.settingsTabs.getTabAt(4)?.select()
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
        val existingAdapter = binding.rvFolders.adapter as? FolderAdapter
        if (existingAdapter != null) {
            existingAdapter.folders = folders
            existingAdapter.notifyDataSetChanged()
            return
        }
        binding.rvFolders.layoutManager = LinearLayoutManager(this)
        binding.rvFolders.adapter = FolderAdapter(
            folders = folders,
            onEditClick = { folder, pos -> showFolderEditDialog(folder, pos) },
            onDeleteClick = { folder, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Folder")
                    .setMessage("Are you sure you want to delete folder \"${folder.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        config.folders.removeAll { it.id == folder.id }
                        saveConfig()
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

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                    viewHolder?.itemView?.apply {
                        animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                        alpha = 0.85f
                        elevation = 12f
                    }
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
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

        val dialogScope = CoroutineScope(activityScope.coroutineContext +
            SupervisorJob(activityScope.coroutineContext[Job]))
        var installedApps = emptyList<AppEntry>()

        // Primary App Picker
        var selectedPrimaryApp: AppEntry? = if (existingAction?.type == ActionType.APP) {
            AppEntry(existingAction.label, existingAction.data)
        } else null
        val primaryAppAdapter = AppPickerAdapter(
            items = installedApps,
            initialSelectedPackage = selectedPrimaryApp?.packageName,
            scope = dialogScope,
            loadIcon = { loadPickerIcon(it.packageName) }
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
            AppEntry(existingSec.label, existingSec.data)
        } else null
        val secAppAdapter = AppPickerAdapter(
            items = installedApps,
            initialSelectedPackage = selectedSecApp?.packageName,
            scope = dialogScope,
            loadIcon = { loadPickerIcon(it.packageName) }
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
        var shortcutProviders = emptyList<ShortcutProviderEntry>()

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
        val primaryShortcutAdapter = ShortcutPickerAdapter(shortcutProviders, dialogScope,
            { loadPickerIcon(it.packageName, it.activityName) }) { provider ->
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
        val secShortcutAdapter = ShortcutPickerAdapter(shortcutProviders, dialogScope,
            { loadPickerIcon(it.packageName, it.activityName) }) { provider ->
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

        dialog.setOnDismissListener {
            dialogScope.cancel()
            onShortcutResultCallback = null
        }
        dialog.show()

        val appSearchHint = etSearchApp.hint
        val shortcutSearchHint = etSearchShortcut.hint
        listOf(etSearchApp, etSecSearchApp, etSearchShortcut, etSecSearchShortcut).forEach {
            it.hint = getString(R.string.loading_apps)
        }
        dialogScope.launch {
            try {
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps() }
                fun filteredApps(search: EditText): List<AppEntry> {
                    val query = search.text.toString()
                    return installedApps.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                }
                primaryAppAdapter.updateList(filteredApps(etSearchApp))
                secAppAdapter.updateList(filteredApps(etSecSearchApp))
                shortcutProviders = withContext(Dispatchers.IO) { loadShortcutProviders() }
                fun filteredShortcuts(search: EditText): List<ShortcutProviderEntry> {
                    val query = search.text.toString()
                    return shortcutProviders.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                }
                primaryShortcutAdapter.updateList(filteredShortcuts(etSearchShortcut))
                secShortcutAdapter.updateList(filteredShortcuts(etSecSearchShortcut))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not load apps", Toast.LENGTH_SHORT).show()
            } finally {
                etSearchApp.hint = appSearchHint
                etSecSearchApp.hint = appSearchHint
                etSearchShortcut.hint = shortcutSearchHint
                etSecSearchShortcut.hint = shortcutSearchHint
            }
        }

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

        var itemTouchHelper: ItemTouchHelper? = null
        val folderAdapter = RingActionAdapter(
            context = this,
            actions = folderActions,
            onActionClick = { _, _ -> },
            onActionDelete = { action, pos ->
                val currentIdx = folderActions.indexOfFirst { it.id == action.id }
                if (currentIdx != -1) {
                    folderActions.removeAt(currentIdx)
                } else if (pos in folderActions.indices) {
                    folderActions.removeAt(pos)
                }
                rvFolderItems.adapter?.notifyDataSetChanged()
                updateFolderItemsUi()
            },
            onOrderChanged = {},
            onStartDragListener = { holder ->
                itemTouchHelper?.startDrag(holder)
            }
        )
        rvFolderItems.layoutManager = LinearLayoutManager(this)
        rvFolderItems.adapter = folderAdapter

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
                    folderAdapter.onItemMove(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    rvFolderItems.parent?.requestDisallowInterceptTouchEvent(true)
                    viewHolder?.itemView?.apply {
                        animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                        alpha = 0.85f
                        elevation = 12f
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                rvFolderItems.parent?.requestDisallowInterceptTouchEvent(false)
                viewHolder.itemView.apply {
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    alpha = 1.0f
                    elevation = 0f
                }
                folderAdapter.onDragComplete()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(rvFolderItems)
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

        dialog.setOnDismissListener { onShortcutResultCallback = null }
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
                        showLoadedPicker("Select App", { loadInstalledApps() }, { it.label }) { app ->
                            onActionSelected(PieAction(type = ActionType.APP, label = app.label, data = app.packageName))
                        }
                    }
                    2 -> {
                        showLoadedPicker("Select Shortcut App", { loadShortcutProviders() }, { it.label }) { provider ->
                            val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                                component = ComponentName(provider.packageName, provider.activityName)
                            }
                            onShortcutResultCallback = onActionSelected
                            try {
                                shortcutLauncher.launch(intent)
                            } catch (e: Exception) {
                                onShortcutResultCallback = null
                                Toast.makeText(this, "Could not open shortcut picker: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun <T> showLoadedPicker(
        title: String,
        load: () -> List<T>,
        label: (T) -> String,
        onSelected: (T) -> Unit
    ) {
        val loading = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.loading_apps)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val job = activityScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { load() }
                loading.setOnDismissListener(null)
                loading.dismiss()
                if (items.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No matching apps found", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(title)
                        .setItems(items.map(label).toTypedArray()) { _, index -> onSelected(items[index]) }
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not load apps", Toast.LENGTH_SHORT).show()
            } finally {
                loading.setOnDismissListener(null)
                loading.dismiss()
            }
        }
        loading.setOnDismissListener { job.cancel() }
        loading.show()
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

    private fun scheduleConfigSave() {
        configSavePending = true
        binding.root.removeCallbacks(persistConfigRunnable)
        binding.root.postDelayed(persistConfigRunnable, 150)
    }

    private fun saveConfig() {
        binding.root.removeCallbacks(persistConfigRunnable)
        configSavePending = false
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
        if (config.enabled && !PieOverlayService.isRunning && Settings.canDrawOverlays(this)) {
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
    private fun loadInstalledApps(): List<AppEntry> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    private suspend fun loadPickerIcon(packageName: String, activityName: String? = null): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$packageName/$activityName"
            pickerIconCache.get(key)?.let { return@withContext it }
            try {
                val drawable = if (activityName == null) {
                    packageManager.getApplicationIcon(packageName)
                } else {
                    packageManager.getActivityIcon(ComponentName(packageName, activityName))
                }
                val size = (40 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                drawable.toBitmap(size, size).also { pickerIconCache.put(key, it) }
            } catch (e: Exception) {
                null
            }
        }

    data class AppEntry(val label: String, val packageName: String)

    class AppPickerAdapter(
        private var items: List<AppEntry>,
        initialSelectedPackage: String? = null,
        private val scope: CoroutineScope,
        private val loadIcon: suspend (AppEntry) -> Bitmap?,
        private val onSelect: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

        private var selectedPackage = initialSelectedPackage

        fun updateList(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.label
            holder.iconJob?.cancel()
            holder.ivIcon.setImageResource(R.drawable.ic_action_app)
            holder.iconJob = scope.launch {
                loadIcon(item)?.let { holder.ivIcon.setImageBitmap(it) }
            }

            if (item.packageName == selectedPackage) {
                holder.view.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                holder.view.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.view.setOnClickListener {
                val position = holder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val selected = items[position]
                val prev = items.indexOfFirst { it.packageName == selectedPackage }
                selectedPackage = selected.packageName
                if (prev != -1) notifyItemChanged(prev)
                notifyItemChanged(position)
                onSelect(selected)
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.iconJob?.cancel()
            holder.ivIcon.setImageDrawable(null)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = items.size
    }

    // Helper data and adapter for Shortcut Provider Picker
    data class ShortcutProviderEntry(
        val label: String,
        val packageName: String,
        val activityName: String
    )

    private fun loadShortcutProviders(): List<ShortcutProviderEntry> {
        val pm = packageManager
        val queryIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        return pm.queryIntentActivities(queryIntent, 0).map { resolveInfo ->
            ShortcutProviderEntry(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = resolveInfo.activityInfo.packageName,
                activityName = resolveInfo.activityInfo.name
            )
        }.sortedBy { it.label.lowercase() }
    }

    class ShortcutPickerAdapter(
        private var items: List<ShortcutProviderEntry>,
        private val scope: CoroutineScope,
        private val loadIcon: suspend (ShortcutProviderEntry) -> Bitmap?,
        private val onSelect: (ShortcutProviderEntry) -> Unit
    ) : RecyclerView.Adapter<ShortcutPickerAdapter.ViewHolder>() {

        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.label
            holder.iconJob?.cancel()
            holder.ivIcon.setImageResource(R.drawable.ic_action_shortcut)
            holder.iconJob = scope.launch {
                loadIcon(item)?.let { holder.ivIcon.setImageBitmap(it) }
            }
            holder.view.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: ViewHolder) {
            holder.iconJob?.cancel()
            holder.ivIcon.setImageDrawable(null)
            super.onViewRecycled(holder)
        }

        fun updateList(newItems: List<ShortcutProviderEntry>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
