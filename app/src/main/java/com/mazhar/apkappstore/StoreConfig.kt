package com.mazhar.apkappstore

import android.content.Context

object StoreConfig {
    private const val PREFS = "apk_app_store"
    private const val BACKEND = "backend_url"
    private const val WIFI_AUTO = "wifi_auto_download"

    fun backendUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BACKEND, "") ?: ""
        return saved.ifBlank { BuildConfig.DEFAULT_BACKEND_URL }.trimEnd('/')
    }

    fun setBackendUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(BACKEND, value.trim().trimEnd('/')).apply()
    }

    fun autoDownloadOnWifi(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WIFI_AUTO, false)

    fun setAutoDownloadOnWifi(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(WIFI_AUTO, enabled).apply()
    }
}

data class StoreApp(
    val slug: String,
    val packageName: String,
    val name: String,
    val shortDescription: String,
    val description: String,
    val category: String,
    val iconUrl: String,
    val featured: Boolean,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val changelog: String,
    val screenshots: List<String> = emptyList()
)

data class InstalledState(
    val installed: Boolean,
    val installedVersionCode: Long,
    val updateAvailable: Boolean
)
