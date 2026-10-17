package com.ludo.piecontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class PieControlApp : Application() {

    companion object {
        const val CHANNEL_ID = "pie_control_service_channel"
        lateinit var instance: PieControlApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PieControl Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PieControl edge trigger gesture active"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
