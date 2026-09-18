package com.mazhar.apkappstore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (StoreConfig.backendUrl(applicationContext).isBlank()) return Result.success()
        return try {
            val apps = StoreApi.apps(applicationContext)
            val updates = apps.filter { StoreApi.installedState(applicationContext, it).updateAvailable }
            if (updates.isEmpty()) return Result.success()
            createChannel()
            if (StoreConfig.autoDownloadOnWifi(applicationContext) && isWifi()) {
                val app = updates.first()
                val file = AppDownloader.download(applicationContext, app)
                val installIntent = AppDownloader.installIntent(applicationContext, file)
                val pending = PendingIntent.getActivity(
                    applicationContext,
                    app.packageName.hashCode(),
                    installIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                notify(
                    id = 2101,
                    title = "${app.name} is ready to update",
                    text = "Version ${app.versionName} downloaded. Tap to install.",
                    pendingIntent = pending
                )
            } else {
                val open = Intent(applicationContext, MainActivity::class.java)
                val pending = PendingIntent.getActivity(
                    applicationContext,
                    2102,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                notify(
                    id = 2102,
                    title = "${updates.size} app update${if (updates.size == 1) "" else "s"} available",
                    text = updates.take(3).joinToString(", ") { it.name },
                    pendingIntent = pending
                )
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun isWifi(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notify(id: Int, title: String, text: String, pendingIntent: PendingIntent) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(id, notification)
    }
}
