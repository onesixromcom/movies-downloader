package com.onesixromcom.movdow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

/** Latest version from GitHub releases (tags like "v1.2.0" with the release apk attached). */
object UpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/onesixromcom/movies-downloader/releases/latest"

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GitHub")
            response.body?.string().orEmpty()
        }
        val json = JSONObject(body)
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
        Release(
            version = json.getString("tag_name").removePrefix("v"),
            pageUrl = json.getString("html_url"),
            apkUrl = apk?.optString("browser_download_url")?.ifEmpty { null },
        )
    }

    /** "1.10.0" > "1.9.2"; suffixes like "-dev" are ignored. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split(".").map { it.toIntOrNull() ?: 0 }
}
