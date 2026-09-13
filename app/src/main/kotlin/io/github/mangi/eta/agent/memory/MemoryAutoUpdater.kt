package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal object MemoryAutoUpdater {

    private const val MIN_EXCHANGE_CHARS = 24
    private const val MIN_INTERVAL_MS = 20_000L
    private const val MAX_MEMORY_APPEND_CHARS = 1_200

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-memory-auto").apply { isDaemon = true }
    }
    private val lastRunAt = AtomicLong(0L)

    fun submit(config: AgentModelClient.ModelConfig, userText: String, assistantText: String) {
        if (assistantText.length < MIN_EXCHANGE_CHARS) return
        if (userText.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastRunAt.get() < MIN_INTERVAL_MS) return
        lastRunAt.set(now)
        executor.execute {
            runCatching {
                runBlocking { updateMemory(config, userText.take(4_000), assistantText.take(6_000)) }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("memory_auto_update_failed") {
                    "Auto memory update failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private suspend fun updateMemory(
        config: AgentModelClient.ModelConfig,
        userText: String,
        assistantText: String,
    ) {
        if (!AgentMemoryRepository.isEnabled()) return
        if (!SettingsDataStore.memoryAutoUpdateEnabled()) return
        val snapshot = AgentMemoryRepository.snapshot()
        val prompt = buildString {
            appendLine("你负责维护一份用户的长期记忆文件(MEMORY.md)。记忆只保存跨对话仍有价值的稳定事实、偏好、关系与持续项目；不保存密钥、验证码、一次性请求或临时情绪。")
            appendLine("以下是当前记忆全文：")
            appendLine("<memory>")
            appendLine(snapshot.content.ifBlank { "(空)" })
            appendLine("</memory>")
            appendLine("以下是一轮最新对话：")
            appendLine("<exchange>")
            appendLine("用户：$userText")
            appendLine("助手：$assistantText")
            appendLine("</exchange>")
            append("判断这轮对话是否包含值得长期记住的新信息（新事实、偏好、关系变化、持续项目进展）。")
            append("如果有，输出一个 JSON 对象：{\"add\":\"要追加到记忆的简洁条目，每行一条，使用 `- ` 开头的 Markdown 列表，总长度不超过 300 字\"}。")
            append("如果记忆中已有该信息、信息不值得长期保留，或没有可靠的新信息，输出 {\"add\":\"\"} 或 {\"none\":true}。")
            append("只输出 JSON，不要输出其他内容、代码块标记或解释。")
        }
        val extractionConfig = config.copy(
            systemPrompt = "你是记忆维护助手，只输出符合要求的 JSON。",
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            hostedWebSearchEnabled = false,
        )
        val response = AgentModelClient.complete(
            config = extractionConfig,
            prompt = prompt,
            toolExecutor = { throw UnsupportedOperationException("memory extraction has no tools") },
            history = emptyList(),
            rewriteReply = true,
        )
        val entry = parseEntry(response.content) ?: return
        if (entry.isBlank()) return
        if (entry.length > MAX_MEMORY_APPEND_CHARS) return
        if (snapshot.content.contains(entry.trim(), ignoreCase = true)) return
        val appendText = if (entry.trim().startsWith("-")) entry.trim() else "- ${entry.trim()}"
        val writeResult = AgentMemoryRepository.mutate(
            AgentMemoryMutation.Append(revision = snapshot.revision, content = appendText),
        )
        when (writeResult) {
            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Success -> {
                AndroidAgentLogger.debug { "Auto memory updated (${appendText.length} chars)" }
                AgentMemoryRepository.notifyCloudPushNeeded()
            }

            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Conflict -> Unit
        }
    }

    private fun parseEntry(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val jsonText = normalizeJson(trimmed) ?: return null
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (json.optBoolean("none", false)) return ""
        val add = json.optString("add").trim()
        if (add.isEmpty()) return ""
        return add.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun normalizeJson(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }
}
