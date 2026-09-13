package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class CloudSyncConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val backgroundSync: Boolean = false,
)

internal data class CloudSyncStatus(
    val lastSyncAt: Long = 0L,
    val lastSyncMessage: String = "",
    val pendingUploads: Int = 0,
)

internal class CloudSyncException(
    val code: String,
    message: String,
) : IllegalStateException(message)

private val Context.cloudDataStore: DataStore<Preferences> by preferencesDataStore(name = "eta_cloud_sync")

internal object CloudSyncRepository {

    private val ENABLED = booleanPreferencesKey("enabled")
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
    private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    private val LAST_SYNC_MESSAGE = stringPreferencesKey("last_sync_message")
    private val LAST_MEMORY_PUSH_REVISION = stringPreferencesKey("last_memory_push_revision")

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val syncMutex = Mutex()

    private fun Context.store(): DataStore<Preferences> = applicationContext.cloudDataStore

    private fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    fun configFlow(context: Context): Flow<CloudSyncConfig> =
        context.store().safeData().map { prefs ->
            CloudSyncConfig(
                enabled = prefs[ENABLED] ?: false,
                serverUrl = prefs[SERVER_URL].orEmpty().trim().trimEnd('/'),
                token = prefs[TOKEN].orEmpty(),
                backgroundSync = prefs[BACKGROUND_SYNC] ?: true,
            )
        }

    suspend fun config(context: Context): CloudSyncConfig =
        configFlow(context).first()

    suspend fun saveConfig(context: Context, config: CloudSyncConfig) {
        context.store().edit { prefs ->
            prefs[ENABLED] = config.enabled
            prefs[SERVER_URL] = config.serverUrl.trim().trimEnd('/')
            prefs[TOKEN] = config.token.trim()
            prefs[BACKGROUND_SYNC] = config.backgroundSync
        }
    }

    fun statusFlow(context: Context): Flow<CloudSyncStatus> = combine(
        context.store().safeData(),
        pendingUploadCountFlow(context),
    ) { prefs, pending ->
        CloudSyncStatus(
            lastSyncAt = prefs[LAST_SYNC_AT] ?: 0L,
            lastSyncMessage = prefs[LAST_SYNC_MESSAGE].orEmpty(),
            pendingUploads = pending,
        )
    }

    suspend fun status(context: Context): CloudSyncStatus = statusFlow(context).first()

    private fun pendingUploadCountFlow(context: Context): Flow<Int> =
        kotlinx.coroutines.flow.flow {
            val dir = outboxDir(context.applicationContext)
            while (true) {
                val count = withContext(Dispatchers.IO) {
                    dir.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
                }
                emit(count)
                kotlinx.coroutines.delay(2_000)
            }
        }

    private fun outboxDir(context: Context): File =
        File(context.applicationContext.filesDir, "cloud_outbox")

    private suspend fun writeOutbox(context: Context, name: String, payload: String) =
        withContext(Dispatchers.IO) {
            val dir = outboxDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                throw CloudSyncException("OUTBOX_UNAVAILABLE", "无法创建云端同步队列目录")
            }
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }

    suspend fun enqueueConversationRows(
        context: Context,
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity>,
    ) = withContext(Dispatchers.IO) {
        val byConversation = messages.groupBy { it.conversationId }
        val checkpoints = contextCheckpoints.associateBy { it.conversationId }
        conversations.forEach { conversation ->
            val blob = conversationBlob(
                conversation = conversation,
                messages = byConversation[conversation.id].orEmpty(),
                checkpoint = checkpoints[conversation.id],
            )
            writeOutbox(
                context,
                "conv_${sanitizeId(conversation.id)}_${System.currentTimeMillis()}.json",
                blob.toString(),
            )
        }
    }

    suspend fun enqueueMemoryPush(context: Context) {
        writeOutbox(context, "memory_${System.currentTimeMillis()}.flag", "")
    }

    suspend fun flushOutbox(context: Context): Int {
        val cfg = config(context)
        if (!cfg.isValid()) return 0
        var flushed = 0
        val files = outboxDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in files) {
            when {
                file.extension == "json" -> {
                    val payload = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                        file.delete(); continue
                    }
                    val blob = runCatching { JSONObject(payload) }.getOrElse {
                        file.delete(); continue
                    }
                    val id = blob.optString("id")
                    if (id.isBlank()) {
                        file.delete(); continue
                    }
                    putJson(cfg, "/v1/conversations/${java.net.URLEncoder.encode(id, "UTF-8")}", blob)
                    file.delete()
                    flushed++
                }

                file.extension == "flag" -> {
                    pushMemory(context, cfg)
                    file.delete()
                    flushed++
                }

                else -> file.delete()
            }
        }
        return flushed
    }

    suspend fun syncNow(context: Context, restoreMemory: Boolean = false): String = syncMutex.withLock {
        val cfg = config(context)
        if (!cfg.isValid()) {
            throw CloudSyncException("NOT_CONFIGURED", "请先填写服务器地址和访问令牌")
        }
        withContext(Dispatchers.IO) {
            getJson(cfg, "/v1/ping")
        }
        val pulled = pullAndMerge(context, cfg)
        val pushedMemory = runCatching { pushMemory(context, cfg) }.getOrDefault(false)
        var restoredMemory = false
        if (restoreMemory) {
            restoredMemory = runCatching { pullMemory(context) }.getOrDefault(false)
        }
        var pending = 0
        runCatching { pending = flushOutbox(context) }
        val message = buildString {
            append("云端同步完成")
            if (pulled.updated > 0) append("，更新 $pulled.updated 个对话")
            if (pulled.added > 0) append("，新增 $pulled.added 个对话")
            if (pulled.removed > 0) append("，移除 $pulled.removed 个对话")
            if (pending > 0) append("，上传 $pending 项")
            if (pushedMemory || restoredMemory) append("，记忆已同步")
        }
        context.store().edit { prefs ->
            prefs[LAST_SYNC_AT] = System.currentTimeMillis()
            prefs[LAST_SYNC_MESSAGE] = message
        }
        message
    }

    suspend fun pushAllConversations(context: Context): Int = syncMutex.withLock {
        val cfg = config(context)
        if (!cfg.isValid()) {
            throw CloudSyncException("NOT_CONFIGURED", "请先填写服务器地址和访问令牌")
        }
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val conversations = dao.conversationEntities()
            val messages = dao.messages()
            val checkpoints = dao.contextCheckpoints()
            val byConversation = messages.groupBy { it.conversationId }
            val checkpointsById = checkpoints.associateBy { it.conversationId }
            conversations.forEach { conversation ->
                val blob = conversationBlob(
                    conversation = conversation,
                    messages = byConversation[conversation.id].orEmpty(),
                    checkpoint = checkpointsById[conversation.id],
                )
                putJson(
                    cfg,
                    "/v1/conversations/${java.net.URLEncoder.encode(conversation.id, "UTF-8")}",
                    blob,
                )
            }
            conversations.size
        }
    }

    private data class PullResult(val added: Int, val updated: Int, val removed: Int)

    private suspend fun pullAndMerge(context: Context, cfg: CloudSyncConfig): PullResult =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val dao = EtaDatabase.get(appContext).conversationDao()
            val remote = getJson(cfg, "/v1/conversations").optJSONArray("conversations")
                ?: JSONArray()
            var added = 0
            var updated = 0
            var removed = 0
            val remoteIds = mutableSetOf<String>()
            for (index in 0 until remote.length()) {
                val item = remote.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                remoteIds += id
                val remoteUpdatedAt = item.optLong("updatedAt", 0L)
                val deleted = item.optBoolean("deleted", false)
                val local = dao.conversationEntity(id)
                if (deleted) {
                    if (local != null) {
                        dao.deleteConversationCompletely(id)
                        removed++
                    }
                    continue
                }
                if (local != null && local.updatedAt >= remoteUpdatedAt) continue
                val blob = runCatching {
                    getJson(cfg, "/v1/conversations/${java.net.URLEncoder.encode(id, "UTF-8")}")
                }.getOrNull() ?: continue
                if (upsertBlob(appContext, blob)) {
                    if (local == null) added++ else updated++
                }
            }
            PullResult(added, updated, removed)
        }

    private suspend fun pushMemory(context: Context, cfg: CloudSyncConfig): Boolean {
        val snapshot = AgentMemoryRepository.snapshot()
        context.store().edit { prefs ->
            prefs[LAST_MEMORY_PUSH_REVISION] = snapshot.revision
        }
        if (snapshot.content.isBlank()) return false
        putJson(cfg, "/v1/memory", JSONObject().put("content", snapshot.content))
        return true
    }

    private suspend fun pullMemory(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cfg = config(context)
        val payload = getJson(cfg, "/v1/memory")
        val content = payload.optString("content")
        AgentMemoryRepository.replaceAll(content)
        context.store().edit { prefs ->
            prefs[LAST_MEMORY_PUSH_REVISION] = AgentMemoryRepository.snapshot().revision
        }
        true
    }

    suspend fun upsertBlob(context: Context, blob: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = EtaDatabase.get(appContext).conversationDao()
        val conversation = conversationFromBlob(blob) ?: return@withContext false
        val messages = messagesFromBlob(blob)
        val checkpoint = checkpointFromBlob(blob)
        dao.deleteConversationCompletely(conversation.id)
        dao.insertConversations(listOf(conversation))
        if (checkpoint != null) dao.insertContextCheckpoints(listOf(checkpoint))
        if (messages.isNotEmpty()) dao.insertMessages(messages)
        true
    }

    internal fun conversationBlob(
        conversation: ConversationEntity,
        messages: List<ConversationMessageEntity>,
        checkpoint: ConversationContextCheckpointEntity?,
    ): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", conversation.id)
        .put("title", conversation.title)
        .put("thinkingEnabled", conversation.thinkingEnabled)
        .put("reasoningEffort", conversation.reasoningEffort)
        .put("historyJson", conversation.historyJson)
        .put("appliedRuntimeRunIdsJson", conversation.appliedRuntimeRunIdsJson)
        .put("roleplayJson", conversation.roleplayJson)
        .put("revisionsJson", conversation.revisionsJson)
        .put("createdAt", conversation.createdAt)
        .put("updatedAt", conversation.updatedAt)
        .put(
            "messages",
            JSONArray().also { array ->
                messages.forEach { message ->
                    array.put(
                        JSONObject()
                            .put("id", message.id)
                            .put("conversationId", message.conversationId)
                            .put("sortIndex", message.sortIndex)
                            .put("type", message.type)
                            .put("content", message.content)
                            .put("imagesJson", message.imagesJson)
                            .put("isEdited", message.isEdited)
                            .put("renderMarkdown", message.renderMarkdown ?: JSONObject.NULL)
                            .put("contextTokens", message.contextTokens ?: JSONObject.NULL)
                            .put("inputTokens", message.inputTokens ?: JSONObject.NULL)
                            .put("outputTokens", message.outputTokens ?: JSONObject.NULL)
                            .put("reasoningTokens", message.reasoningTokens ?: JSONObject.NULL)
                            .put("cachedTokens", message.cachedTokens ?: JSONObject.NULL)
                            .put("elapsedSeconds", message.elapsedSeconds ?: JSONObject.NULL)
                            .put("toolName", message.toolName ?: JSONObject.NULL)
                            .put("toolStatus", message.toolStatus ?: JSONObject.NULL)
                            .put("argumentsSummary", message.argumentsSummary ?: JSONObject.NULL)
                            .put("resultSummary", message.resultSummary ?: JSONObject.NULL)
                            .put("imageCount", message.imageCount)
                            .put("toolsJson", message.toolsJson),
                    )
                }
            },
        )
        .put(
            "historyCheckpoint",
            checkpoint?.let {
                JSONObject()
                    .put("conversationId", it.conversationId)
                    .put("historyJson", it.historyJson)
                    .put("journalJson", it.journalJson)
            } ?: JSONObject.NULL,
        )

    private fun conversationFromBlob(blob: JSONObject): ConversationEntity? {
        val id = blob.optString("id")
        if (id.isBlank()) return null
        return ConversationEntity(
            id = id,
            title = blob.optString("title"),
            thinkingEnabled = blob.optBoolean("thinkingEnabled", true),
            reasoningEffort = blob.optString("reasoningEffort").ifBlank { "default" },
            historyJson = blob.optString("historyJson").ifBlank { "[]" },
            appliedRuntimeRunIdsJson = blob.optString("appliedRuntimeRunIdsJson").ifBlank { "[]" },
            roleplayJson = blob.optString("roleplayJson"),
            revisionsJson = blob.optString("revisionsJson"),
            createdAt = blob.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = blob.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun messagesFromBlob(blob: JSONObject): List<ConversationMessageEntity> {
        val array = blob.optJSONArray("messages") ?: return emptyList()
        val conversationId = blob.optString("id")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ConversationMessageEntity(
                        id = item.optString("id"),
                        conversationId = item.optString("conversationId", conversationId),
                        sortIndex = item.optInt("sortIndex", index),
                        type = item.optString("type"),
                        content = item.optString("content"),
                        imagesJson = item.optString("imagesJson", "[]"),
                        isEdited = item.optBoolean("isEdited", false),
                        renderMarkdown = nullableBoolean(item, "renderMarkdown"),
                        contextTokens = nullableInt(item, "contextTokens"),
                        inputTokens = nullableInt(item, "inputTokens"),
                        outputTokens = nullableInt(item, "outputTokens"),
                        reasoningTokens = nullableInt(item, "reasoningTokens"),
                        cachedTokens = nullableInt(item, "cachedTokens"),
                        elapsedSeconds = nullableInt(item, "elapsedSeconds"),
                        toolName = nullableString(item, "toolName"),
                        toolStatus = nullableString(item, "toolStatus"),
                        argumentsSummary = nullableString(item, "argumentsSummary"),
                        resultSummary = nullableString(item, "resultSummary"),
                        imageCount = item.optInt("imageCount", 0),
                        toolsJson = item.optString("toolsJson", "[]"),
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.conversationId.isNotBlank() }
    }

    private fun checkpointFromBlob(blob: JSONObject): ConversationContextCheckpointEntity? {
        val checkpoint = blob.optJSONObject("historyCheckpoint") ?: return null
        val conversationId = checkpoint.optString("conversationId", blob.optString("id"))
        if (conversationId.isBlank()) return null
        return ConversationContextCheckpointEntity(
            conversationId = conversationId,
            historyJson = checkpoint.optString("historyJson", "[]"),
            journalJson = checkpoint.optString("journalJson", ""),
        )
    }

    private fun nullableBoolean(item: JSONObject, key: String): Boolean? =
        if (item.isNull(key)) null else item.optBoolean(key)

    private fun nullableInt(item: JSONObject, key: String): Int? =
        if (item.isNull(key)) null else item.optInt(key)

    private fun nullableString(item: JSONObject, key: String): String? =
        if (item.isNull(key)) null else item.optString(key)

    private fun sanitizeId(id: String): String =
        id.map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_' }
            .joinToString("")
            .take(80)

    private fun CloudSyncConfig.isValid(): Boolean =
        enabled && serverUrl.startsWith("http") && serverUrl.length > 8

    private fun headers(cfg: CloudSyncConfig): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("X-Eta-Token", cfg.token)
            .add("Accept", "application/json")
            .build()

    private fun getJson(cfg: CloudSyncConfig, path: String): JSONObject {
        val request = Request.Builder()
            .url(cfg.serverUrl + path)
            .headers(headers(cfg))
            .get()
            .build()
        return execute(request)
    }

    private fun putJson(cfg: CloudSyncConfig, path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(cfg.serverUrl + path)
            .headers(headers(cfg))
            .put(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudSyncException(
                    code = "HTTP_${response.code}",
                    message = "云端服务器返回错误 ${response.code}",
                )
            }
            if (body.isBlank()) return JSONObject()
            return runCatching { JSONObject(body) }.getOrElse {
                throw CloudSyncException("BAD_RESPONSE", "云端服务器返回了无法解析的数据")
            }
        }
    }
}
