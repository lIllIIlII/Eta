package io.github.mangi.eta.core

internal object GitHubMirrors {

    val PROXY_PREFIXES: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    private val PROXYABLE_HOSTS = listOf(
        "github.com",
        "www.github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com",
    )

    fun candidates(url: String, mirrorFirst: Boolean): List<String> {
        val target = url.trim()
        if (target.isEmpty()) return emptyList()
        if (!isProxyable(target)) return listOf(target)
        val proxied = PROXY_PREFIXES.map { prefix -> prefix + target }
        return if (mirrorFirst) (proxied + target).distinct() else (listOf(target) + proxied).distinct()
    }

    fun isProxyable(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return false
        return PROXYABLE_HOSTS.any { candidate ->
            host.equals(candidate, ignoreCase = true) || host.endsWith(".$candidate", ignoreCase = true)
        }
    }
}
