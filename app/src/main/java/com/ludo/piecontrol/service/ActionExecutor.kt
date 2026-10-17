package com.ludo.piecontrol.service

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction

object ActionExecutor {

    private var isTorchOn = false

    fun execute(context: Context, action: PieAction) {
        when (action.type) {
            ActionType.BACK,
            ActionType.HOME,
            ActionType.RECENTS,
            ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS,
            ActionType.POWER,
            ActionType.LOCK_SCREEN,
            ActionType.SCREENSHOT -> {
                val service = PieAccessibilityService.instance
                if (service != null) {
                    service.executeGlobalAction(action.type)
                } else {
                    Toast.makeText(
                        context,
                        "Enable PieControl in Accessibility Settings to use " + action.label,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            ActionType.ASSISTANT -> {
                launchAssistant(context)
            }

            ActionType.VOLUME_UP -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_RAISE,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }

            ActionType.VOLUME_DOWN -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }

            ActionType.FLASHLIGHT -> {
                toggleFlashlight(context)
            }

            ActionType.APP -> {
                launchApp(context, action.data)
            }

            ActionType.URL -> {
                launchUrl(context, action.data)
            }

            ActionType.SHORTCUT -> {
                launchShortcut(context, action.data)
            }
        }
    }

    private fun launchAssistant(context: Context) {
        val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (voiceIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(voiceIntent)
            return
        }
        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (assistIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(assistIntent)
            return
        }
        Toast.makeText(context, "No assistant app found", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    isTorchOn = !isTorchOn
                    cameraManager.setTorchMode(id, isTorchOn)
                    break
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Flashlight error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                Toast.makeText(context, "App not found: $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchUrl(context: Context, urlString: String) {
        if (urlString.isBlank()) return
        try {
            var formattedUrl = urlString.trim()
            if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                formattedUrl = "https://$formattedUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchShortcut(context: Context, intentUri: String) {
        if (intentUri.isBlank()) return
        try {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not launch shortcut: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
