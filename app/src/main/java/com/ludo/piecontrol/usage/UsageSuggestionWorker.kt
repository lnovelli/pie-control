package com.ludo.piecontrol.usage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ludo.piecontrol.R
import com.ludo.piecontrol.data.PreferencesManager
import com.ludo.piecontrol.ui.MainActivity
import java.util.concurrent.TimeUnit

internal class UsageSuggestionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val preferences = UsageSuggestionPreferences(applicationContext)
        val settings = preferences.settings()
        if (!settings.enabled) return Result.success()
        if (!UsageAccess.isGranted(applicationContext)) {
            UsageSuggestionScheduler.cancel(applicationContext)
            return Result.success()
        }

        val userManager = applicationContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        if (!userManager.isUserUnlocked) return Result.success()

        return try {
            val config = PreferencesManager.getInstance(applicationContext).loadConfig()
            val results = UsageSuggestionAnalyzer(applicationContext).analyze(settings, config)
            preferences.saveAnalysis(results, System.currentTimeMillis())
            maybeNotify(applicationContext, preferences)
            Result.success()
        } catch (_: SecurityException) {
            // Usage Access may be revoked between the AppOps check and the query.
            UsageSuggestionScheduler.cancel(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun maybeNotify(context: Context, preferences: UsageSuggestionPreferences) {
        preferences.postPendingNotifications(canPost = { canPostNotifications(context) }) { pending ->
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_USAGE_SUGGESTIONS, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_action_app)
                .setContentTitle(context.getString(R.string.usage_notification_title))
                .setContentText(context.getString(R.string.usage_notification_message, pending.size))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                true
            } catch (_: SecurityException) {
                // Leave candidates unmarked if posting is denied or permission was revoked.
                false
            }
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    internal companion object {
        const val CHANNEL_ID = "pie_usage_suggestions_channel"
        const val NOTIFICATION_ID = 2201
    }
}

internal object UsageSuggestionScheduler {
    private const val PERIODIC_WORK_NAME = "pie_usage_suggestions_periodic"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val settings = UsageSuggestionPreferences(appContext).settings()
        val workManager = WorkManager.getInstance(appContext)
        if (!settings.enabled || !UsageAccess.isGranted(appContext)) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<UsageSuggestionWorker>(
            settings.checkIntervalDays.toLong(),
            TimeUnit.DAYS
        ).setInitialDelay(settings.checkIntervalDays.toLong(), TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun requestCheck(context: Context): Boolean {
        val appContext = context.applicationContext
        val settings = UsageSuggestionPreferences(appContext).settings()
        if (!settings.enabled || !UsageAccess.isGranted(appContext)) return false
        val request = OneTimeWorkRequestBuilder<UsageSuggestionWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ON_DEMAND_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return true
    }

    fun observeOnDemandWork(context: Context): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ON_DEMAND_WORK_NAME)

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(ON_DEMAND_WORK_NAME)
    }

    private const val ON_DEMAND_WORK_NAME = "pie_usage_suggestions_on_demand"
}

internal object UsageSuggestionNotifications {
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(UsageSuggestionWorker.NOTIFICATION_ID)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            UsageSuggestionWorker.CHANNEL_ID,
            context.getString(R.string.usage_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.usage_notification_channel_description)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
