package io.github.mangi.eta.data.repository

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.GitHubMirrors
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject

internal object AppUpdateChecker {

    const val REPO_OWNER = "lIllIIlII"
    const val REPO_NAME = "Eta"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"

    private const val RELEASE_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val RELEASE_PAGE_URL = "$REPO_URL/releases/latest"
    private const val ASSET_APK_PREFIX = "Eta-v"
    private const val USER_AGENT = "Eta-Update-Check"
    private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
    private const val MAX_PAGE_BYTES = 512L * 1024L

    private val TAG_PATTERN = Regex("""/releases/tag/([^/?#"'\s]+)""")
    private val APK_ASSET_PATTERN = Regex("""/releases/download/[^/"'\s]+/([^/"'\s]+\.apk)""")

    data class ReleaseInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseUrl: String,
        val notes: String,
    )

    private data class Fetched(val body: String, val finalUrl: String)

    private data class Cached(val info: ReleaseInfo, val at: Long)

    @Volatile
    private var cached: Cached? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): String = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: ""
    }.getOrDefault("")

    fun mirrorFirst(): Boolean = Prefs.githubMirrorFirst()

    fun latestRelease(): ReleaseInfo? {
        val snapshot = cached
        if (snapshot != null && SystemClock.elapsedRealtime() - snapshot.at < CACHE_TTL_MILLIS) {
            return snapshot.info
        }
        val fresh = runCatching { fetchRelease() }.getOrNull()
        if (fresh == null) return snapshot?.info
        cached = Cached(fresh, SystemClock.elapsedRealtime())
        return fresh
    }

    fun invalidate() {
        cached = null
    }

    private fun fetchRelease(): ReleaseInfo? = fetchFromApi() ?: fetchFromReleasePage()

    private fun fetchFromApi(): ReleaseInfo? {
        for (url in GitHubMirrors.candidates(RELEASE_API_URL, mirrorFirst())) {
            val fetched = fetch(url) ?: continue
            val json = runCatching { JSONObject(fetched.body) }.getOrNull() ?: continue
            val rawTag = json.optString("tag_name").trim()
            if (rawTag.isEmpty()) continue
            val versionName = normalize(rawTag)
            if (versionName.isEmpty()) continue
            val assetName = firstApkAssetName(json)
                .ifBlank { "$ASSET_APK_PREFIX$versionName.apk" }
            return ReleaseInfo(
                versionName = versionName,
                downloadUrl = "$REPO_URL/releases/download/${tagPath(rawTag)}/$assetName",
                releaseUrl = json.optString("html_url").ifBlank { "$REPO_URL/releases" },
                notes = json.optString("body"),
            )
        }
        return null
    }

    private fun fetchFromReleasePage(): ReleaseInfo? {
        for (url in GitHubMirrors.candidates(RELEASE_PAGE_URL, mirrorFirst())) {
            val fetched = fetch(url) ?: continue
            val tag = TAG_PATTERN.find(fetched.finalUrl)?.groupValues?.getOrNull(1)?.trim()
                ?: TAG_PATTERN.find(fetched.body)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            val versionName = normalize(tag)
            if (versionName.isEmpty()) continue
            val assetName = discoverApkAssetName(tag)
                ?: "$ASSET_APK_PREFIX$versionName.apk"
            return ReleaseInfo(
                versionName = versionName,
                downloadUrl = "$REPO_URL/releases/download/${tagPath(tag)}/$assetName",
                releaseUrl = "$REPO_URL/releases/tag/${tagPath(tag)}",
                notes = "",
            )
        }
        return null
    }

    private fun discoverApkAssetName(tag: String): String? {
        val expandedUrl = "$REPO_URL/releases/expanded_assets/${tagPath(tag)}"
        for (url in GitHubMirrors.candidates(expandedUrl, mirrorFirst())) {
            val fetched = fetch(url) ?: continue
            APK_ASSET_PATTERN.findAll(fetched.body).lastOrNull()?.let { match ->
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun firstApkAssetName(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return ""
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) return name
        }
        return ""
    }

    private fun fetch(url: String): Fetched? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json, text/html")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                Fetched(
                    body = readLimited(body, MAX_PAGE_BYTES),
                    finalUrl = response.request.url.toString(),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun readLimited(body: ResponseBody, maxBytes: Long): String =
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < maxBytes) {
                val remaining = (maxBytes - total).coerceAtMost(buffer.size.toLong()).toInt()
                if (remaining <= 0) break
                val read = input.read(buffer, 0, remaining)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read.toLong()
            }
            output.toString("UTF-8")
        }

    private fun tagPath(rawTag: String): String {
        val tag = rawTag.trim()
        return if (tag.startsWith("v", ignoreCase = true)) tag else "v$tag"
    }

    private fun normalize(rawTag: String): String =
        rawTag.trim().removePrefix("v").removePrefix("V").trim()

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = parseVersion(latest)
        val currentParts = parseVersion(current)
        if (latestParts.isEmpty()) return false
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
