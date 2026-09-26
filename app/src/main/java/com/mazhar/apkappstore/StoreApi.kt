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

    private fun request(context: Context, url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("x-customer-key", StoreConfig.customerKey(context))
        val token = StoreConfig.authToken(context)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun parseAccount(o: JSONObject): AccountUser = AccountUser(
        id = o.optLong("id"),
        name = o.optString("name"),
        email = o.optString("email")
    )

    suspend fun register(context: Context, name: String, email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        authRequest(context, "/api/auth/register", JSONObject()
            .put("name", name.trim())
            .put("email", email.trim())
            .put("password", password))
    }

    suspend fun login(context: Context, email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        authRequest(context, "/api/auth/login", JSONObject()
            .put("email", email.trim())
            .put("password", password))
    }

    private fun authRequest(context: Context, path: String, payload: JSONObject): AuthSession {
        val req = Request.Builder()
            .url(base(context) + path)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) error(root.optString("error").ifBlank { "Account service error (${response.code})" })
            val token = root.optString("token")
            val user = root.optJSONObject("user") ?: error("Invalid account response")
            if (token.isBlank()) error("Invalid account session")
            AuthSession(token, parseAccount(user))
        }
    }

    suspend fun me(context: Context): AccountUser = withContext(Dispatchers.IO) {
        val req = request(context, base(context) + "/api/auth/me").get().build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) error(root.optString("error", "Account session expired"))
            parseAccount(root.optJSONObject("user") ?: error("Invalid account response"))
        }
    }

    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        val req = request(context, base(context) + "/api/auth/logout")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching { client.newCall(req).execute().close() }
        StoreConfig.clearSession(context)
    }

    suspend fun apps(context: Context): List<StoreApp> = withContext(Dispatchers.IO) {
        val backend = runCatching { backendApps(context) }
        val github = runCatching { githubCatalogApps() }

        if (backend.isFailure && github.isFailure) {
            val message = listOfNotNull(
                backend.exceptionOrNull()?.message,
                github.exceptionOrNull()?.message
            ).distinct().joinToString(" • ")
            error(message.ifBlank { "Could not load the store." })
        }

        mergeApps(
            backend.getOrDefault(emptyList()),
            github.getOrDefault(emptyList())
        )
    }

    private fun backendApps(context: Context): List<StoreApp> {
        val base = base(context)
        val req = request(context, "$base/api/apps").get().build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Store API ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            parseApps(root, base)
        }
    }

    private fun githubCatalogApps(): List<StoreApp> {
        val url = StoreConfig.catalogUrl()
        if (url.isBlank()) return emptyList()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "APK-App-Store/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("GitHub catalog ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            parseApps(root, "")
        }
    }

    private fun parseApps(root: JSONObject, base: String): List<StoreApp> {
        val items = root.optJSONArray("apps") ?: JSONArray()
        return buildList {
            for (i in 0 until items.length()) {
                val parsed = parseApp(items.getJSONObject(i), base)
                if (
                    parsed.slug.isNotBlank() &&
                    (parsed.downloadUrl.startsWith("https://") || (parsed.isPaid && !parsed.owned))
                ) add(parsed)
            }
        }
    }

    private fun mergeApps(backend: List<StoreApp>, github: List<StoreApp>): List<StoreApp> {
        val merged = linkedMapOf<String, StoreApp>()

        fun key(app: StoreApp): String =
            app.packageName.trim().ifBlank { app.slug.trim() }.lowercase()

        github.forEach { app ->
            val k = key(app)
            val current = merged[k]
            if (current == null || app.versionCode > current.versionCode) merged[k] = app
        }

        backend.forEach { app ->
            val k = key(app)
            val catalog = merged[k]
            merged[k] = when {
                catalog == null -> app
                app.isPaid -> app
                app.versionCode >= catalog.versionCode -> app
                else -> catalog.copy(
                    name = app.name.ifBlank { catalog.name },
                    shortDescription = app.shortDescription.ifBlank { catalog.shortDescription },
                    description = app.description.ifBlank { catalog.description },
                    category = app.category.ifBlank { catalog.category },
                    iconUrl = app.iconUrl.ifBlank { catalog.iconUrl },
                    featured = app.featured || catalog.featured,
                    screenshots = if (app.screenshots.isNotEmpty()) app.screenshots else catalog.screenshots
                )
            }
        }

        return merged.values
            .filter {
                it.packageName.isNotBlank() &&
                    it.versionCode > 0 &&
                    (it.downloadUrl.startsWith("https://") || (it.isPaid && !it.owned))
            }
            .sortedWith(compareByDescending<StoreApp> { it.featured }.thenBy { it.name.lowercase() })
    }

    suspend fun paymentMethods(context: Context): List<PaymentMethod> = withContext(Dispatchers.IO) {
        val base = base(context)
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
            base.isBlank() -> value
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
