package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal object AppUpdateChecker {

    const val REPO_URL = "https://github.com/lIllIIlII/Eta"
    private const val RELEASES_URL = "https://api.github.com/repos/lIllIIlII/Eta/releases/latest"

    data class ReleaseInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseUrl: String,
        val notes: String,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): String = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: ""
    }.getOrDefault("")

    fun latestRelease(): ReleaseInfo? {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Eta-Update-Check")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val tagName = json.optString("tag_name").removePrefix("v").removePrefix("V")
            if (tagName.isBlank()) return null
            val assets = json.optJSONArray("assets")
            var downloadUrl = ""
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            return ReleaseInfo(
                versionName = tagName,
                downloadUrl = downloadUrl,
                releaseUrl = json.optString("html_url", REPO_URL),
                notes = json.optString("body"),
            )
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = parseVersion(latest)
        val currentParts = parseVersion(current)
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrNull(index) ?: 0
            val c = currentParts.getOrNull(index) ?: 0
            if (l != c) return l > c
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+')
            .mapNotNull { part ->
                part.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
            }
}
