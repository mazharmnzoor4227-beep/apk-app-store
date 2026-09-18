package com.mazhar.apkappstore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object StoreApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun apps(context: Context): List<StoreApp> = withContext(Dispatchers.IO) {
        val base = StoreConfig.backendUrl(context)
        if (base.isBlank()) return@withContext emptyList()
        val req = Request.Builder().url("$base/api/apps").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Store API ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val items = root.optJSONArray("apps") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) add(parseApp(items.getJSONObject(i), base))
            }
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
