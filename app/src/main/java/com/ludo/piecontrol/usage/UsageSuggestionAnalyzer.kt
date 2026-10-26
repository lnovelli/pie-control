package com.ludo.piecontrol.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

internal class UsageSuggestionAnalyzer(private val context: Context) {
    fun analyze(settings: UsageSuggestionSettings, config: PieConfig): List<UsageSuggestion> {
        val packageManager = context.packageManager
        val launchablePackages = queryLaunchablePackages(packageManager)
        val homePackage = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        val mappedPackages = mappedPackages(config)

        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.DAYS.toMillis(settings.analysisWindowDays.toLong())
        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageManager.queryEvents(startTime, endTime) ?: return emptyList()
        val counts = mutableMapOf<String, MutableList<Long>>()
        val event = UsageEvents.Event()
        val foregroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val screenOffEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            UsageEvents.Event.SCREEN_NON_INTERACTIVE
        } else {
            -1
        }
        val keyguardShownEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            UsageEvents.Event.KEYGUARD_SHOWN
        } else {
            -1
        }
        var lastResumedPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventType = event.eventType
            if (eventType == screenOffEvent || eventType == keyguardShownEvent) {
                lastResumedPackage = null
                continue
            }
            if (eventType != foregroundEvent) continue

            val packageName = event.packageName ?: continue
            if (packageName != lastResumedPackage &&
                packageName != context.packageName &&
                packageName != homePackage &&
                packageName in launchablePackages &&
                packageName !in mappedPackages) {
                counts.getOrPut(packageName) { mutableListOf() }.add(event.timeStamp)
            }
            lastResumedPackage = packageName
        }

        val zone = ZoneId.systemDefault()
        return counts.mapNotNull { (packageName, visits) ->
            val activeDays = visits.asSequence()
                .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
                .toSet()
                .size
            if (visits.size < settings.minimumVisits || activeDays < settings.minimumActiveDays) {
                null
            } else {
                UsageSuggestion(packageName, visits.size, activeDays)
            }
        }.sortedWith(compareByDescending<UsageSuggestion> { it.visits }
            .thenByDescending { it.activeDays }
            .thenBy { it.packageName })
            .take(MAX_RESULTS)
    }

    private fun queryLaunchablePackages(packageManager: PackageManager): Set<String> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun mappedPackages(config: PieConfig): Set<String> {
        val mapped = mutableSetOf<String>()
        val visitedFolders = mutableSetOf<String>()

        fun visit(action: PieAction) {
            when (action.type) {
                ActionType.APP -> action.data.takeIf { it.isNotBlank() }?.let(mapped::add)
                ActionType.SHORTCUT -> {
                    if (action.data.isNotBlank()) {
                        try {
                            val intent = Intent.parseUri(action.data, Intent.URI_INTENT_SCHEME)
                            (intent.`package` ?: intent.component?.packageName)?.let(mapped::add)
                        } catch (_: Exception) {
                            // Invalid legacy shortcut URIs do not map an app package.
                        }
                    }
                }
                ActionType.FOLDER -> {
                    val folder = config.folders.firstOrNull { it.id == action.data || it.name == action.data }
                    if (folder != null && visitedFolders.add(folder.id)) folder.actions.forEach(::visit)
                }
                else -> Unit
            }
            action.secondaryAction?.let(::visit)
        }

        (config.layer1 + config.layer2 + config.layer3).forEach(::visit)
        return mapped
    }

    private companion object {
        const val MAX_RESULTS = 20
    }
}
