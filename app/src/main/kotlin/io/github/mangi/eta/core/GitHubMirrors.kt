package io.github.mangi.eta.core

/**
 * GitHub 国内访问加速镜像。
 *
 * 以下服务均为「前缀代理」：把镜像前缀拼在原始 GitHub URL 前即可走国内 CDN 下载，
 * 覆盖 github.com（Releases/Archive/Blob）与 api.github.com/repos 请求。
 * 顺序即优先级；所有下载场景都应先尝试直连，失败后依次降级到镜像。
 */
internal object GitHubMirrors {

    val PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
    )

    /** 原始 URL 优先，随后依次是各镜像前缀代理的候选列表。 */
    fun candidates(url: String): List<String> = buildList {
        add(url)
        PREFIXES.forEach { prefix -> add(prefix + url) }
    }

    /** 仅返回镜像前缀候选（不含原始 URL），用于 VerifiedArtifact.preferredUrls。 */
    fun preferred(url: String): List<String> = PREFIXES.map { prefix -> prefix + url }
}
