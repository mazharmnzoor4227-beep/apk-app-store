package com.mazhar.apkappstore

import android.content.Context
import java.util.UUID

object StoreConfig {
    private const val PREFS = "apk_app_store"
    private const val WIFI_AUTO = "wifi_auto_download"
    private const val CUSTOMER_KEY = "customer_key"
    private const val AUTH_TOKEN = "auth_token"
    private const val ACCOUNT_ID = "account_id"
    private const val ACCOUNT_NAME = "account_name"
    private const val ACCOUNT_EMAIL = "account_email"
    private const val LIVE_BACKEND = "https://apk-app-store.mazharmnzoor4227.workers.dev"

    fun backendUrl(context: Context): String {
        val configured = BuildConfig.DEFAULT_BACKEND_URL.trim().trimEnd('/')
        return configured.ifBlank { LIVE_BACKEND }
    }

    fun customerKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(CUSTOMER_KEY, "").orEmpty()
        if (saved.isNotBlank()) return saved
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(CUSTOMER_KEY, created).apply()
        return created
    }

    fun authToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(AUTH_TOKEN, "").orEmpty()

    fun account(context: Context): AccountUser? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(ACCOUNT_ID, 0L)
        val email = prefs.getString(ACCOUNT_EMAIL, "").orEmpty()
        if (id <= 0L || email.isBlank() || authToken(context).isBlank()) return null
        return AccountUser(
            id = id,
            name = prefs.getString(ACCOUNT_NAME, "").orEmpty(),
            email = email
        )
    }

    fun saveSession(context: Context, session: AuthSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(AUTH_TOKEN, session.token)
            .putLong(ACCOUNT_ID, session.user.id)
            .putString(ACCOUNT_NAME, session.user.name)
            .putString(ACCOUNT_EMAIL, session.user.email)
            .apply()
    }

    fun updateAccount(context: Context, user: AccountUser) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(ACCOUNT_ID, user.id)
            .putString(ACCOUNT_NAME, user.name)
            .putString(ACCOUNT_EMAIL, user.email)
            .apply()
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(AUTH_TOKEN)
            .remove(ACCOUNT_ID)
            .remove(ACCOUNT_NAME)
            .remove(ACCOUNT_EMAIL)
            .apply()
    }

    fun autoDownloadOnWifi(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WIFI_AUTO, false)

    fun setAutoDownloadOnWifi(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(WIFI_AUTO, enabled).apply()
    }
}

data class AccountUser(
    val id: Long,
    val name: String,
    val email: String
)

data class AuthSession(
    val token: String,
    val user: AccountUser
)

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
