package com.mazhar.apkappstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

private class OpenHandledException : Exception("")

object AppDownloader {
    suspend fun download(context: Context, app: StoreApp, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val state = StoreApi.installedState(context, app)
        if (state.installed && !state.updateAvailable) {
            withContext(Dispatchers.Main) {
                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
            }
            throw OpenHandledException()
        }

        require(app.downloadUrl.startsWith("https://") || app.downloadUrl.startsWith("http://")) { "Invalid download URL" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "${app.packageName}-${app.versionCode}.apk")
        val request = Request.Builder().url(app.downloadUrl).get().build()
        StoreApi.client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: ${response.code}")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        if (app.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(app.sha256, ignoreCase = true)) {
                target.delete()
                error("APK integrity check failed")
            }
        }
        target
    }

    fun installIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun requestInstall(context: Context, file: File) {
        context.startActivity(installIntent(context, file))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
