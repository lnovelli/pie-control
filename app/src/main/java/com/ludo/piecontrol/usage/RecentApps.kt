package com.ludo.piecontrol.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig
import java.util.concurrent.TimeUnit

/** Reads on-device foreground events; call from a worker thread. */
internal class RecentApps(private val context: Context) {
    fun load(config: PieConfig, limit: Int = MAX_RESULTS): List<PieAction> {
        val maxResults = limit.coerceIn(0, MAX_RESULTS)
        if (maxResults == 0) return emptyList()

        return try {
            if (!UsageAccess.isGranted(context)) return emptyList()
            val mappedPackages = mappedAppPackages(config)

            val pm = context.packageManager
            val homePackage = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            val launchablePackages = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).mapNotNull { it.activityInfo?.packageName }.toSet()

            val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return emptyList()
            val endTime = System.currentTimeMillis()
            // Android retains events for only a few days; the seven-day window may be incomplete.
            val events = usageManager.queryEvents(endTime - LOOKBACK_MILLIS, endTime)
                ?: return emptyList()
            val foregroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val latestByPackage = mutableMapOf<String, Long>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) break
                if (event.eventType != foregroundEvent) continue
                val packageName = event.packageName ?: continue
                if (packageName == context.packageName || packageName == homePackage ||
                    packageName !in launchablePackages || packageName in mappedPackages
                ) continue
                val previous = latestByPackage[packageName]
                if (previous == null || event.timeStamp > previous) {
                    latestByPackage[packageName] = event.timeStamp
                }
            }

            val actions = mutableListOf<PieAction>()
            for (entry in latestByPackage.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
            )) {
                val packageName = entry.key
                val app = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    continue // The package may have been removed since the event was recorded.
                }
                if (!app.enabled || pm.getLaunchIntentForPackage(packageName) == null) continue
                actions.add(
                    PieAction(
                        type = ActionType.APP,
                        label = pm.getApplicationLabel(app).toString(),
                        data = packageName
                    )
                )
                if (actions.size == maxResults) break
            }
            // Access may have been revoked during the query or while loading metadata.
            if (UsageAccess.isGranted(context)) actions else emptyList()
        } catch (_: RuntimeException) {
            // A revoked permission or unavailable system service must not break the overlay.
            emptyList()
        }
    }

    private companion object {
        const val MAX_RESULTS = 10
        val LOOKBACK_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}
