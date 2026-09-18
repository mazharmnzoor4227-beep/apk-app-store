package com.mazhar.apkappstore

import android.content.Context
import java.util.UUID

object StoreConfig {
    private const val PREFS = "apk_app_store"
    private const val WIFI_AUTO = "wifi_auto_download"
    private const val CUSTOMER_KEY = "customer_key"

    fun backendUrl(context: Context): String = BuildConfig.DEFAULT_BACKEND_URL.trim().trimEnd('/')

    fun customerKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(CUSTOMER_KEY, "").orEmpty()
        if (saved.isNotBlank()) return saved
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(CUSTOMER_KEY, created).apply()
        return created
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
    val isPaid: Boolean,
    val pricePkr: Int,
    val owned: Boolean,
    val purchaseStatus: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val changelog: String,
    val screenshots: List<String> = emptyList()
)

data class PaymentMethod(
    val id: Long,
    val type: String,
    val label: String,
    val accountTitle: String,
    val accountValue: String,
    val instructions: String
)

data class InstalledState(
    val installed: Boolean,
    val installedVersionCode: Long,
    val updateAvailable: Boolean
)
