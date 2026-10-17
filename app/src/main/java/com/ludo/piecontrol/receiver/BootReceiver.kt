package com.ludo.piecontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.ludo.piecontrol.data.PreferencesManager
import com.ludo.piecontrol.service.PieOverlayService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PreferencesManager.getInstance(context)
            val config = prefs.loadConfig()

            if (config.enabled && Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, PieOverlayService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // Handled gracefully
                }
            }
        }
    }
}
