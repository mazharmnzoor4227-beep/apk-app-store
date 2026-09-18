package com.mazhar.apkappstore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object StoreApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun base(context: Context): String = StoreConfig.backendUrl(context)

    private fun request(context: Context, url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("x-customer-key", StoreConfig.customerKey(context))

    suspend fun apps(context: Context): List<StoreApp> = withContext(Dispatchers.IO) {
        val base = base(context)
        if (base.isBlank()) return@withContext emptyList()
        val req = request(context, "$base/api/apps").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Store API ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val items = root.optJSONArray("apps") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) add(parseApp(items.getJSONObject(i), base))
            }
        }
    }

    suspend fun paymentMethods(context: Context): List<PaymentMethod> = withContext(Dispatchers.IO) {
        val base = base(context)
        if (base.isBlank()) return@withContext emptyList()
        val req = request(context, "$base/api/payment-methods").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Payment API ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val items = root.optJSONArray("methods") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    add(PaymentMethod(
                        id = o.optLong("id"),
                        type = o.optString("type"),
                        label = o.optString("label"),
                        accountTitle = o.optString("account_title"),
                        accountValue = o.optString("account_value"),
                        instructions = o.optString("instructions")
                    ))
                }
            }
        }
    }

    suspend fun submitPurchase(
        context: Context,
        app: StoreApp,
        name: String,
        phone: String,
        methodId: Long,
        transactionReference: String
    ): String = withContext(Dispatchers.IO) {
        val base = base(context)
        if (base.isBlank()) error("Store service is unavailable")
        val payload = JSONObject()
            .put("slug", app.slug)
            .put("customer_name", name.trim())
            .put("phone", phone.trim())
            .put("payment_method_id", methodId)
            .put("transaction_reference", transactionReference.trim())
            .toString()
        val req = request(context, "$base/api/purchases")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) error(root.optString("error", "Payment submission failed"))
            root.optString("status", "pending")
        }
    }

    private fun parseApp(o: JSONObject, base: String): StoreApp {
        fun absolute(value: String): String = when {
            value.isBlank() -> ""
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> "$base${if (value.startsWith('/')) value else "/$value"}"
        }
        val shots = mutableListOf<String>()
        val a = o.optJSONArray("screenshots") ?: JSONArray()
        for (i in 0 until a.length()) shots += absolute(a.optString(i))
        return StoreApp(
            slug = o.optString("slug"),
            packageName = o.optString("package_name"),
            name = o.optString("name"),
            shortDescription = o.optString("short_description"),
            description = o.optString("description"),
            category = o.optString("category", "Apps"),
            iconUrl = absolute(o.optString("icon_url")),
            featured = o.optBoolean("featured"),
            isPaid = o.optBoolean("is_paid"),
            pricePkr = o.optInt("price_pkr", 0),
            owned = o.optBoolean("owned", !o.optBoolean("is_paid")),
            purchaseStatus = o.optString("purchase_status", if (o.optBoolean("is_paid")) "none" else "free"),
            versionCode = o.optLong("version_code"),
            versionName = o.optString("version_name"),
            minSdk = o.optInt("min_sdk", 29),
            downloadUrl = absolute(o.optString("download_url")),
            fileSize = o.optLong("file_size"),
            sha256 = o.optString("sha256"),
            changelog = o.optString("changelog"),
            screenshots = shots
        )
    }

    fun installedState(context: Context, app: StoreApp): InstalledState {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(app.packageName, 0)
            }
            val installedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            InstalledState(true, installedCode, app.versionCode > installedCode)
        } catch (_: Exception) {
            InstalledState(false, 0L, false)
        }
    }

    fun client(): OkHttpClient = client
}
