package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

data class AgentCustomLinuxTool(
    val name: String,
    val description: String,
    val command: String,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val modelToolName: String get() = "$NAME_PREFIX$sanitizedName"

    val sanitizedName: String =
        name.trim().lowercase()
            .map { character -> if (character.isLetterOrDigit()) character.toString() else "_" }
            .joinToString("")
            .trim('_')
            .take(40)

    fun isValid(): Boolean =
        sanitizedName.isNotEmpty() && command.isNotBlank()

    fun buildCommand(arguments: String): String =
        if (command.contains(ARGS_PLACEHOLDER)) {
            command.replace(ARGS_PLACEHOLDER, arguments.trim())
        } else if (arguments.isNotBlank()) {
            "${command.trimEnd()} ${arguments.trim()}"
        } else {
            command
        }

    companion object {
        const val NAME_PREFIX = "linux_"
        const val ARGS_PLACEHOLDER = "{args}"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 180_000L
        const val MIN_TIMEOUT_MS = 1_000L

        fun parseAll(): List<AgentCustomLinuxTool> = parse(Prefs.localAgentString(Prefs.Keys.AGENT_CUSTOM_LINUX_TOOLS))

        fun parse(raw: String): List<AgentCustomLinuxTool> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(
                            AgentCustomLinuxTool(
                                name = item.optString("name").trim(),
                                description = item.optString("description").trim(),
                                command = item.optString("command").trim(),
                                timeoutMs = item.optLong("timeout_ms", DEFAULT_TIMEOUT_MS)
                                    .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
                            )
                        )
                    }
                }.filter { it.isValid() }
                    .distinctBy { it.sanitizedName }
            }.getOrElse { emptyList() }
        }

        fun find(name: String): AgentCustomLinuxTool? =
            parseAll().firstOrNull { it.modelToolName == name }
    }
}

internal object AgentCustomLinuxToolCatalog {

    fun appendTo(tools: JSONArray) {
        val customTools = AgentCustomLinuxTool.parseAll()
        customTools.forEach { tool ->
            if (tool.modelToolName in AgentToolRequirements.toolNames) return@forEach
            AgentToolRequirements.registerDynamic(
                tool.modelToolName,
                LocalToolRequirement(rootRequirement = RootRequirement.PARTIAL),
            )
            val description = buildString {
                append("用户自定义 Linux 工具：在设备的 Linux 环境（Alpine/Debian）中执行固定命令。")
                if (tool.description.isNotBlank()) {
                    append("用途：").append(tool.description).append("。")
                }
                append("基础命令：").append(tool.command).append("。")
                if (tool.command.contains(AgentCustomLinuxTool.ARGS_PLACEHOLDER)) {
                    append("arguments 会替换命令中的 {args} 占位符；")
                } else {
                    append("arguments 会以空格拼接在基础命令之后；")
                }
                append("多参数请自行加引号。无需参数时不要传 arguments。")
            }
            tools.put(
                AgentToolSchema.function(
                    name = tool.modelToolName,
                    description = description,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "arguments",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "传给命令的参数，例如文件路径、关键词或选项。不需要时留空。")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "命令超时毫秒数，默认 ${tool.timeoutMs}，最大 ${AgentCustomLinuxTool.MAX_TIMEOUT_MS}。")
                                )
                        )
                        .put("required", JSONArray()),
                )
            )
        }
    }
}
