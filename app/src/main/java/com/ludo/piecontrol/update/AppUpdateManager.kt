package com.ludo.piecontrol.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ludo.piecontrol.PieControlApp
import com.ludo.piecontrol.R
import com.ludo.piecontrol.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val UPDATE_NOTIFICATION_ID = 2002
    private const val PRIMARY_VERSION_URL = "https://haven.ipv64.net/download/piecontrol_version.json"
    private const val FALLBACK_VERSION_URL = "https://secure.ipv64.net/download/piecontrol_version.json"
    const val DEFAULT_APK_URL = "https://haven.ipv64.net/download/piecontrol.apk"

    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Query remote server for version info.
     */
    fun checkForUpdates(
        context: Context,
        onResult: (hasUpdate: Boolean, remoteVersionCode: Long, remoteVersionName: String, downloadUrl: String, notes: String, error: String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var jsonText: String? = null
            var lastError: String? = null

            // Try primary then fallback URL
            for (urlStr in listOf(PRIMARY_VERSION_URL, FALLBACK_VERSION_URL)) {
                try {
                    val url = URL(urlStr)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 10000
                    connection.connect()

                    if (connection.responseCode in 200..299) {
                        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                            jsonText = reader.readText()
                        }
                        break
                    } else {
                        lastError = "Server returned HTTP ${connection.responseCode}"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Connection error"
                }
            }

            if (jsonText != null) {
                try {
                    val json = JSONObject(jsonText!!)
                    val remoteCode = json.optLong("version_code", 1L)
                    val remoteName = json.optString("version_name", "1.0.0")
                    val downloadUrl = json.optString("download_url", DEFAULT_APK_URL)
                    val notes = json.optString("release_notes", "")
                    val currentCode = getCurrentVersionCode(context)

                    val hasUpdate = remoteCode > currentCode
                    withContext(Dispatchers.Main) {
                        onResult(hasUpdate, remoteCode, remoteName, downloadUrl, notes, null)
                    }
                    return@launch
                } catch (e: Exception) {
                    lastError = "Failed to parse update manifest: ${e.message}"
                }
            }

            withContext(Dispatchers.Main) {
                onResult(false, 0L, "", "", "", lastError ?: "Failed to check for updates")
            }
        }
    }

    /**
     * Background check and prompt via notification
     */
    fun checkAndNotifyUpdate(context: Context, autoDownload: Boolean = false) {
        checkForUpdates(context) { hasUpdate, _, remoteVersionName, downloadUrl, notes, _ ->
            if (hasUpdate) {
                if (autoDownload) {
                    downloadAndPromptInstall(context, downloadUrl, "PieControl v$remoteVersionName Ready")
                } else {
                    showUpdateAvailableNotification(context, remoteVersionName, downloadUrl, notes)
                }
            }
        }
    }

    private fun showUpdateAvailableNotification(
        context: Context,
        remoteVersionName: String,
        downloadUrl: String,
        notes: String
    ) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("trigger_update_download", true)
            putExtra("update_download_url", downloadUrl)
            putExtra("update_version_name", remoteVersionName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            2003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, PieControlApp.UPDATE_CHANNEL_ID)
            .setContentTitle("New PieControl v$remoteVersionName Available")
            .setContentText("A new build is ready on the server. Tap to download & install.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Version $remoteVersionName is ready.\nNotes: $notes\nTap to download."))
            .setSmallIcon(R.drawable.ic_action_update)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifManager.notify(UPDATE_NOTIFICATION_ID, notif)
    }

    /**
     * Download the APK from the server and trigger installation prompt.
     */
    fun downloadAndPromptInstall(
        context: Context,
        downloadUrl: String = DEFAULT_APK_URL,
        title: String = "PieControl Update",
        onProgress: ((progress: Int) -> Unit)? = null,
        onError: ((error: String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                showDownloadProgressNotification(context, title, 0)

                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                val apkDir = File(context.cacheDir, "apk_updates")
                if (!apkDir.exists()) apkDir.mkdirs()
                val apkFile = File(apkDir, "piecontrol-update.apk")
                if (apkFile.exists()) apkFile.delete()

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                if (progress >= lastProgress + 5) {
                                    lastProgress = progress
                                    showDownloadProgressNotification(context, title, progress)
                                    withContext(Dispatchers.Main) {
                                        onProgress?.invoke(progress)
                                    }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    showInstallReadyNotification(context, apkFile, title)
                    launchInstallIntent(context, apkFile)
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed: ${e.message}", e)
                showFailureNotification(context, "Update failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    fun launchInstallIntent(context: Context, apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }

    private fun showDownloadProgressNotification(context: Context, title: String, progress: Int) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, PieControlApp.UPDATE_CHANNEL_ID)
            .setContentTitle("Downloading PieControl Update")
            .setContentText(if (progress > 0) "$progress%" else "Starting download...")
            .setSmallIcon(R.drawable.ic_action_update)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress > 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        notifManager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    private fun showInstallReadyNotification(context: Context, apkFile: File, title: String) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            2004,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, PieControlApp.UPDATE_CHANNEL_ID)
            .setContentTitle("Update Ready to Install")
            .setContentText("Tap to install the new PieControl update.")
            .setSmallIcon(R.drawable.ic_action_update)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifManager.notify(UPDATE_NOTIFICATION_ID, notif)
    }

    private fun showFailureNotification(context: Context, error: String) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, PieControlApp.UPDATE_CHANNEL_ID)
            .setContentTitle("Update Download Failed")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_action_update)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifManager.notify(UPDATE_NOTIFICATION_ID, notif)
    }
}
