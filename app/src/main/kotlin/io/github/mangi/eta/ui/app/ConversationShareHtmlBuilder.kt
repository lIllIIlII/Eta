package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ConversationShareHtmlBuilder {

    data class SharedConversation(
        val title: String,
        val updatedAtMillis: Long,
        val messages: List<AgentChatMessageUi>,
    )

    fun build(conversations: List<SharedConversation>): String {
        val sections = conversations.joinToString(separator = "\n") { conversation ->
            buildSection(conversation)
        }
        return HTML_TEMPLATE.replace("__SECTIONS__", sections)
    }

    fun imageFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "Eta-Share-$timestamp.png"
    }

    private fun buildSection(conversation: SharedConversation): String {
        val title = escape(conversation.title.ifBlank { "对话" })
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(conversation.updatedAtMillis))
        val body = conversation.messages.joinToString(separator = "\n") { message ->
            renderMessage(message)
        }
        return """
<section class="conv">
<div class="conv-title">$title</div>
<div class="conv-time">更新于 ${escape(time)}</div>
$body
</section>"""
    }

    private fun renderMessage(message: AgentChatMessageUi): String = when (message) {
        is UserMessageUi -> {
            val prompt = AgentFileReferencePromptCodec.parse(message.content)
            val text = prompt.request.ifBlank { message.content }
            if (text.isBlank() && prompt.references.isEmpty()) {
                ""
            } else {
                val references = prompt.references.joinToString(separator = "<br>") { reference ->
                    "<span class=\"file-ref\">📎 ${escape(reference.absolutePath)}</span>"
                }
                "<div class=\"msg user\">${escape(text)}$references</div>"
            }
        }

        is AgentMessageUi -> message.content.takeIf { it.isNotBlank() }
            ?.let { content ->
                val usage = message.usage?.takeIf { !it.isEmpty }?.let(::renderUsage)
                "<div class=\"msg assistant\">${escape(content)}$usage</div>"
            }
            .orEmpty()

        is ThinkingMessageUi -> message.content.takeIf { it.isNotBlank() }
            ?.let { content ->
                "<details class=\"thinking\"><summary>思考过程</summary><div class=\"thinking-body\">${escape(content)}</div></details>"
            }
            .orEmpty()

        is ToolActivityMessageUi -> buildString {
            append("<div class=\"msg tool\">")
            append("[${escape(message.toolName)}] ${escape(message.argumentsSummary)}")
            message.command?.takeIf { it.isNotBlank() }?.let { command ->
                append("<pre class=\"tool-cmd\">${escape(command.take(2000))}</pre>")
            }
            message.resultSummary?.takeIf { it.isNotBlank() }?.let { result ->
                append("<div class=\"tool-result\">${escape(result.take(600))}</div>")
            }
            append("</div>")
        }

        is ToolSummaryMessageUi -> ""

        is SystemNoticeMessageUi -> "<div class=\"msg system_notice\">${escape(message.detail ?: "")}</div>"

        else -> ""
    }

    private fun renderUsage(usage: io.github.mangi.eta.ui.model.TokenUsageUi): String {
        val parts = mutableListOf<String>()
        usage.inputTokens?.takeIf { it > 0 }?.let { parts.add("输入 $it") }
        usage.outputTokens?.takeIf { it > 0 }?.let { parts.add("输出 $it") }
        usage.cachedTokens?.takeIf { it > 0 }?.let { cached ->
            val hitRate = usage.inputTokens?.takeIf { it > 0 }
                ?.let { input -> "（命中率 ${cached * 100 / input}%）" }
                .orEmpty()
            parts.add("缓存 $cached$hitRate")
        }
        usage.contextTokens?.takeIf { it > 0 }?.let { parts.add("上下文 $it") }
        if (parts.isEmpty()) return ""
        return "<div class=\"usage\">${escape(parts.joinToString(" · "))}</div>"
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private val HTML_TEMPLATE = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box}
body{margin:0;background:#eef0f5;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#1c1c1e;padding:20px 16px}
.brand{text-align:center;font-size:13px;color:#8e8e93;margin-bottom:14px}
.conv{max-width:900px;margin:0 auto 28px;background:#f7f8fa;border-radius:20px;padding:16px}
.conv-title{font-size:18px;font-weight:700;padding:4px 6px 0}
.conv-time{font-size:12px;color:#8e8e93;padding:2px 6px 10px;border-bottom:1px solid rgba(0,0,0,.06);margin-bottom:10px}
.msg{padding:10px 14px;border-radius:16px;margin:8px 0;font-size:15px;line-height:1.65;white-space:pre-wrap;word-break:break-word;max-width:88%}
.user{background:#0a84ff;color:#fff;margin-left:auto}
.assistant{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.05)}
.tool{background:#e8e8ed;color:#3a3a3c;font-size:12px;max-width:95%}
.tool-cmd{font-family:ui-monospace,Menlo,monospace;font-size:11px;background:rgba(0,0,0,.05);border-radius:8px;padding:8px;margin:6px 0 0;white-space:pre-wrap;word-break:break-all;max-height:220px;overflow:hidden}
.tool-result{margin-top:6px;opacity:.75;white-space:pre-wrap;word-break:break-word}
.system_notice{background:#fff3d6;color:#7a5b00;font-size:12px;max-width:95%}
.usage{margin-top:8px;padding-top:6px;border-top:1px dashed rgba(0,0,0,.08);font-size:11px;color:#a0a0a5}
.thinking{margin:8px 0;max-width:88%}
.thinking summary{font-size:12px;color:#8e8e93;cursor:default;list-style:none}
.thinking-body{margin-top:6px;padding:10px 12px;border-radius:12px;background:#eceef2;color:#5f6368;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-word}
.file-ref{display:block;font-size:11px;opacity:.8;margin-top:4px}
</style>
</head>
<body>
<div class="brand">Eta · 聊天记录长图</div>
__SECTIONS__
</body>
</html>"""
}
