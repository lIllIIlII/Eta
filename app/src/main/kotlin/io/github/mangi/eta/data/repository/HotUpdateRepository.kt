package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.hotupdate.HotUpdateState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class HotUpdateRemoteConfig(
    val version: Int = 0,
    val notice: String = "",
    val systemPromptPrefix: String = "",
    val flags: Map<String, String> = emptyMap(),
    val apkVersionCode: Int = 0,
    val apkVersionName: String = "",
    val apkUrl: String = "",
    val apkNotes: String = "",
)

internal data class HotUpdateOutcome(
    val applied: Boolean,
    val fromCache: Boolean,
    val config: HotUpdateRemoteConfig,
)

internal object HotUpdateRepository {

    const val CONFIG_URL = "https://raw.githubusercontent.com/lIllIIlII/Eta/main/hotupdate/hotupdate.json"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun refresh(context: Context): HotUpdateOutcome {
        val appContext = context.applicationContext
        val fetched = runCatching { fetchConfig() }.getOrNull()
        val config = fetched ?: readCache(appContext)
        if (config != null) {
            HotUpdateState.apply(
                HotUpdateState.Config(
                    version = config.version,
                    notice = config.notice,
                    systemPromptPrefix = config.systemPromptPrefix,
                    flags = config.flags,
                ),
            )
        }
        if (fetched != null) {
            runCatching { writeCache(appContext, fetched) }
        }
        return HotUpdateOutcome(
            applied = config != null,
            fromCache = fetched == null && config != null,
            config = config ?: HotUpdateRemoteConfig(),
        )
    }

    fun cached(context: Context): HotUpdateRemoteConfig? {
        val config = readCache(context.applicationContext) ?: return null
        HotUpdateState.apply(
            HotUpdateState.Config(
                version = config.version,
                notice = config.notice,
                systemPromptPrefix = config.systemPromptPrefix,
                flags = config.flags,
            ),
        )
        return config
    }

    fun pendingApkUpdate(context: Context, config: HotUpdateRemoteConfig?): HotUpdateRemoteConfig? {
        val candidate = config ?: return null
        if (candidate.apkUrl.isBlank()) return null
        val currentCode = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
        }.getOrDefault(0L)
        return if (candidate.apkVersionCode > currentCode) candidate else null
    }

    private fun fetchConfig(): HotUpdateRemoteConfig? {
        val request = Request.Builder()
            .url(CONFIG_URL)
            .header("User-Agent", "Eta-HotUpdate")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return parse(body) ?: return null
        }
    }

    private fun parse(body: String): HotUpdateRemoteConfig? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val flagsJson = json.optJSONObject("flags")
        val flags = buildMap {
            if (flagsJson != null) {
                for (key in flagsJson.keys()) {
                    put(key, flagsJson.optString(key))
                }
            }
        }
        val apk = json.optJSONObject("apk")
        return HotUpdateRemoteConfig(
            version = json.optInt("version", 0),
            notice = json.optString("notice").trim(),
            systemPromptPrefix = json.optString("systemPromptPrefix"),
            flags = flags,
            apkVersionCode = apk?.optInt("versionCode", 0) ?: 0,
            apkVersionName = apk?.optString("versionName").orEmpty(),
            apkUrl = apk?.optString("url").orEmpty().trim(),
            apkNotes = apk?.optString("notes").orEmpty(),
        )
    }

    private fun cacheFile(context: Context): File =
        File(context.applicationContext.filesDir, "hotupdate/config.json")

    private fun readCache(context: Context): HotUpdateRemoteConfig? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        val body = runCatching { file.readText() }.getOrNull().orEmpty()
        if (body.isBlank()) return null
        return parse(body)
    }

    private fun writeCache(context: Context, config: HotUpdateRemoteConfig) {
        val file = cacheFile(context)
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        val json = JSONObject().apply {
            put("version", config.version)
            put("notice", config.notice)
            put("systemPromptPrefix", config.systemPromptPrefix)
            put("flags", JSONObject(config.flags))
            put(
                "apk",
                JSONObject().apply {
                    put("versionCode", config.apkVersionCode)
                    put("versionName", config.apkVersionName)
                    put("url", config.apkUrl)
                    put("notes", config.apkNotes)
                },
            )
        }
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun refreshSuspend(context: Context): HotUpdateOutcome =
        withContext(Dispatchers.IO) { refresh(context) }
}
