package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader

internal object CustomHeaderFilter {

    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        "authorization",
        "x-api-key",
        "anthropic-version"
    )

    private val SENSITIVE_NAMES = setOf(
        "authorization",
        "x-api-key",
        "api-key"
    )

    fun isForbidden(name: String): Boolean =
        name.trim().isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    fun sanitize(headers: List<CustomHeader>): List<CustomHeader> =
        headers.filterNot { isForbidden(it.name) }

    fun validationError(headers: List<CustomHeader>): String? {
        val names = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            val prefix = "第 ${index + 1} 个请求头"
            if (isForbidden(name)) return "${prefix}名称为空或由系统管理"
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                return "${prefix}名称包含无效字符"
            }
            if (header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "${prefix}值只能包含可打印的 ASCII 字符或制表符"
            }
            if (!names.add(name.lowercase())) return "${prefix}名称重复（不区分大小写）"
        }
        return null
    }

    fun mergeInto(
        builder: okhttp3.Headers.Builder,
        headers: List<CustomHeader>
    ) {
        sanitize(headers).forEach { header ->
            builder.set(header.name.trim(), header.value)
        }
    }

    fun redactForLog(headers: List<CustomHeader>): List<Pair<String, String>> =
        sanitize(headers).map { header ->
            val nameLower = header.name.lowercase()
            val value = if (nameLower in SENSITIVE_NAMES) {
                "***"
            } else {
                header.value
            }
            header.name to value
        }
}
