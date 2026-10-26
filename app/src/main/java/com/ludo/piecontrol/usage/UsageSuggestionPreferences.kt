package com.ludo.piecontrol.usage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class UsageSuggestionSettings(
    val enabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val checkIntervalDays: Int = 1,
    val analysisWindowDays: Int = 7,
    val minimumVisits: Int = 5,
    val minimumActiveDays: Int = 3
)

internal data class UsageSuggestion(
    val packageName: String,
    val visits: Int,
    val activeDays: Int
)

internal class UsageSuggestionPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (prefs.contains(KEY_DISMISSED_PACKAGES)) {
            synchronized(NOTIFICATION_LOCK) {
                val legacyDismissed = prefs.getStringSet(KEY_DISMISSED_PACKAGES, null).orEmpty()
                val editor = prefs.edit()
                if (legacyDismissed.isNotEmpty()) {
                    val muted = prefs.getStringSet(KEY_MUTED_NOTIFICATIONS, emptySet()).orEmpty().toMutableSet()
                    muted.addAll(legacyDismissed)
                    editor.putStringSet(KEY_MUTED_NOTIFICATIONS, muted)
                }
                editor.remove(KEY_DISMISSED_PACKAGES).apply()
            }
        }
    }

    fun settings(): UsageSuggestionSettings {
        val storedIntervalDays = prefs.getInt(KEY_INTERVAL_DAYS, 1)
        val intervalDays = when (storedIntervalDays) {
            1, 2, 3, 7 -> storedIntervalDays
            else -> 1
        }
        val storedWindowDays = prefs.getInt(KEY_WINDOW_DAYS, 7)
        val windowDays = when (storedWindowDays) {
            3, 7, 14, 30 -> storedWindowDays
            else -> 7
        }
        return UsageSuggestionSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, false),
            checkIntervalDays = intervalDays,
            analysisWindowDays = windowDays,
            minimumVisits = prefs.getInt(KEY_MIN_VISITS, 5).coerceIn(2, 20),
            minimumActiveDays = prefs.getInt(KEY_MIN_ACTIVE_DAYS, 3).coerceIn(1, windowDays)
        )
    }

    fun saveSettings(settings: UsageSuggestionSettings) {
        synchronized(NOTIFICATION_LOCK) {
            val windowDays = when (settings.analysisWindowDays) {
                3, 7, 14, 30 -> settings.analysisWindowDays
                else -> 7
            }
            val intervalDays = when (settings.checkIntervalDays) {
                1, 2, 3, 7 -> settings.checkIntervalDays
                else -> 1
            }
            prefs.edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
                .putInt(KEY_INTERVAL_DAYS, intervalDays)
                .putInt(KEY_WINDOW_DAYS, windowDays)
                .putInt(KEY_MIN_VISITS, settings.minimumVisits.coerceIn(2, 20))
                .putInt(KEY_MIN_ACTIVE_DAYS, settings.minimumActiveDays.coerceIn(1, windowDays))
                .apply()
        }
    }

    fun suggestions(): List<UsageSuggestion> {
        val json = prefs.getString(KEY_SUGGESTIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val packageName = row.optString("packageName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                UsageSuggestion(
                    packageName = packageName,
                    visits = row.optInt("visits", 0).coerceAtLeast(0),
                    activeDays = row.optInt("activeDays", 0).coerceAtLeast(0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAnalysis(results: List<UsageSuggestion>, checkedAt: Long) {
        val array = JSONArray()
        results.take(MAX_SUGGESTIONS).forEach { suggestion ->
            array.put(JSONObject().apply {
                put("packageName", suggestion.packageName)
                put("visits", suggestion.visits)
                put("activeDays", suggestion.activeDays)
            })
        }
        prefs.edit()
            .putString(KEY_SUGGESTIONS, array.toString())
            .putLong(KEY_LAST_CHECKED_AT, checkedAt)
            .apply()
    }

    fun lastCheckedAt(): Long = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)

    fun postPendingNotifications(canPost: () -> Boolean, post: (List<UsageSuggestion>) -> Boolean) {
        synchronized(NOTIFICATION_LOCK) {
            if (!prefs.getBoolean(KEY_ENABLED, false) ||
                !prefs.getBoolean(KEY_NOTIFICATIONS, false) ||
                !canPost()) return
            val alreadyNotified = prefs.getStringSet(KEY_NOTIFIED_PACKAGES, emptySet()).orEmpty()
            val muted = prefs.getStringSet(KEY_MUTED_NOTIFICATIONS, emptySet()).orEmpty()
            val pending = suggestions().filter {
                it.packageName !in alreadyNotified && it.packageName !in muted
            }
            if (pending.isNotEmpty() && post(pending)) {
                val merged = alreadyNotified.toMutableSet().apply { addAll(pending.map { it.packageName }) }
                prefs.edit().putStringSet(KEY_NOTIFIED_PACKAGES, merged).apply()
            }
        }
    }

    fun isNotificationMuted(packageName: String): Boolean =
        packageName in prefs.getStringSet(KEY_MUTED_NOTIFICATIONS, emptySet()).orEmpty()

    fun setNotificationMuted(packageName: String, muted: Boolean) {
        synchronized(NOTIFICATION_LOCK) {
            val mutedPackages = prefs.getStringSet(KEY_MUTED_NOTIFICATIONS, emptySet()).orEmpty().toMutableSet()
            if (muted) mutedPackages.add(packageName) else mutedPackages.remove(packageName)
            val editor = prefs.edit()
            if (mutedPackages.isEmpty()) editor.remove(KEY_MUTED_NOTIFICATIONS)
            else editor.putStringSet(KEY_MUTED_NOTIFICATIONS, mutedPackages)
            editor.apply()
        }
    }

    fun clearAnalysis() {
        synchronized(NOTIFICATION_LOCK) {
            prefs.edit()
                .remove(KEY_SUGGESTIONS)
                .remove(KEY_LAST_CHECKED_AT)
                .remove(KEY_NOTIFIED_PACKAGES)
                .remove(KEY_DISMISSED_PACKAGES)
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "pie_usage_suggestions"
        const val KEY_ENABLED = "enabled"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_INTERVAL_DAYS = "check_interval_days"
        const val KEY_WINDOW_DAYS = "analysis_window_days"
        const val KEY_MIN_VISITS = "minimum_visits"
        const val KEY_MIN_ACTIVE_DAYS = "minimum_active_days"
        const val KEY_SUGGESTIONS = "suggestions_json"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_NOTIFIED_PACKAGES = "notified_packages"
        const val KEY_DISMISSED_PACKAGES = "dismissed_packages"
        const val KEY_MUTED_NOTIFICATIONS = "muted_notification_packages"
        val NOTIFICATION_LOCK = Any()
        const val MAX_SUGGESTIONS = 20
    }
}
