package com.ludo.piecontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ludo.piecontrol.usage.UsageSuggestionNotifications
import com.ludo.piecontrol.usage.UsageSuggestionScheduler

class PieControlApp : Application() {

    companion object {
        const val CHANNEL_ID = "pie_control_service_channel"
        const val UPDATE_CHANNEL_ID = "pie_control_update_channel"
        lateinit var instance: PieControlApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        UsageSuggestionNotifications.createChannel(this)
        UsageSuggestionScheduler.sync(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "PieControl Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PieControl edge trigger gesture active"
                setShowBadge(false)
            }

            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for PieControl updates"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(updateChannel)
        }
    }
}
