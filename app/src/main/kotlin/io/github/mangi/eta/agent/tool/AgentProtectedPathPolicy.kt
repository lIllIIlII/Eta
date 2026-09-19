package io.github.mangi.eta.agent.tool

import java.io.File

internal object AgentProtectedPathPolicy {

    private val PROTECTED_PREFIXES = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/oem", "/apex",
        "/boot", "/dtbo", "/recovery", "/init_boot", "/vendor_boot", "/vbmeta",
        "/efs", "/persist", "/metadata", "/firmware", "/modem", "/radio",
        "/cust", "/preload", "/custom",
        "/my_product", "/my_bigball", "/my_carrier", "/my_region", "/my_heytap",
        "/my_preload", "/my_stock", "/my_company", "/my_engineering", "/my_manifest",
        "/mnt/vendor", "/mnt/product", "/mnt/odm",
        "/data/system", "/data/adb", "/data/misc", "/data/property", "/data/vendor",
        "/data/dalvik-cache", "/data/system_de", "/data/system_ce", "/data/ota",
        "/cache/dalvik-cache", "/dev/block", "/dev/mapper", "/dev/bootdevice",
        "/proc/sys", "/config", "/dsp", "/xrom",
    )

    private val DESTRUCTIVE_TOKENS = setOf(
        "rm", "rmdir", "unlink", "shred", "dd", "mkfs", "mke2fs", "mkfs.ext2",
        "mkfs.ext4", "mkfs.f2fs", "mkfs.vfat", "mkyaffs2image", "truncate",
        "mount", "remount", "umount", "chmod", "chown", "chgrp", "chattr",
        "setfattr", "sed", "tee", "flash_image", "fastboot", "erase", "wipe",
        "format", "restorecon", "setenforce", "setprop", "cp", "mv", "install",
        "rsync", "ln", "patch", "ddof", "busybox", "toybox",
    )

    private val REDIRECT_PATTERN = Regex("(>+|>>+)\\s*(\\S+)")

    /** 解析用户配置的禁访路径列表：支持换行/逗号/分号分隔，忽略空项与注释。 */
    fun parseUserBlockedPaths(raw: String): List<String> = raw
        .split('\n', ',', ';', '，', '；')
        .map { it.trim().trim('"', '\'') }
        .filter { candidate -> candidate.startsWith("/") }
        .mapNotNull { candidate -> normalize(candidate) }
        .filter { it != "/" }
        .distinct()

    fun isUserBlocked(normalizedPath: String, userBlockedPaths: List<String>): Boolean {
        if (normalizedPath == "/") return false
        return userBlockedPaths.any { blocked ->
            normalizedPath == blocked || normalizedPath.startsWith("$blocked/")
        }
    }

    fun isProtected(rawPath: String): Boolean {
        val normalized = normalize(rawPath) ?: return false
        if (normalized == "/") return false
        return PROTECTED_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }

    fun violationForWrite(rawPath: String, userBlockedPaths: List<String> = emptyList()): String? {
        val normalized = normalize(rawPath) ?: return null
        return when {
            isProtected(normalized) -> "PROTECTED_PATH_WRITE_DENIED:$normalized"
            isUserBlocked(normalized, userBlockedPaths) -> "BLOCKED_PATH_ACCESS_DENIED:$normalized"
            else -> null
        }
    }

    /** 用户禁访路径同样禁止读取与列目录。 */
    fun violationForRead(rawPath: String, userBlockedPaths: List<String> = emptyList()): String? {
        if (userBlockedPaths.isEmpty()) return null
        val normalized = normalize(rawPath) ?: return null
        return if (isUserBlocked(normalized, userBlockedPaths)) {
            "BLOCKED_PATH_ACCESS_DENIED:$normalized"
        } else {
            null
        }
    }

    fun violationForCommand(
        rawCommand: String,
        userBlockedPaths: List<String> = emptyList(),
    ): String? {
        val command = rawCommand.trim()
        if (command.isEmpty()) return null
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return null

        // 用户禁访路径：读取或写入都拒绝，因此只要命令中出现对应路径即拦截。
        if (userBlockedPaths.isNotEmpty()) {
            tokens.forEachIndexed { _, token ->
                if (looksLikePath(token)) {
                    val normalized = normalize(token)
                    if (normalized != null && isUserBlocked(normalized, userBlockedPaths)) {
                        return "BLOCKED_PATH_ACCESS_DENIED:$normalized"
                    }
                }
            }
            REDIRECT_PATTERN.findAll(command).forEach { match ->
                val target = match.groupValues[2].trim('"', '\'', ' ')
                val normalized = normalize(target)
                if (normalized != null && isUserBlocked(normalized, userBlockedPaths)) {
                    return "BLOCKED_PATH_ACCESS_DENIED:$normalized"
                }
            }
        }

        val protectedIndexes = tokens
            .withIndex()
            .filter { looksLikePath(it.value) && isProtected(it.value) }
            .map { it.index }
            .toSet()
        if (protectedIndexes.isEmpty()) return null

        REDIRECT_PATTERN.findAll(command).forEach { match ->
            val target = match.groupValues[2].trim('"', '\'', ' ')
            if (isProtected(target)) {
                return "PROTECTED_PATH_WRITE_DENIED:$target"
            }
        }

        tokens.forEachIndexed { index, token ->
            if (index !in protectedIndexes) return@forEachIndexed
            var verbIndex = index - 1
            while (verbIndex >= 0 && isShellNoise(tokens[verbIndex])) verbIndex--
            val verb = tokens.getOrNull(verbIndex)?.substringAfterLast('/')?.lowercase() ?: return@forEachIndexed
            if (verb == "cp" || verb == "mv" || verb == "install" || verb == "rsync" || verb == "ln") {
                if (index != tokens.lastIndex) return@forEachIndexed
            }
            if (verb in DESTRUCTIVE_TOKENS) {
                return "PROTECTED_PATH_WRITE_DENIED:${token}"
            }
        }
        return null
    }

    private fun looksLikePath(token: String): Boolean = token.startsWith("/")

    private fun isShellNoise(token: String): Boolean =
        token == "&&" || token == "||" || token == "|" || token == ";" || token == "!" ||
            token == "sudo" || token == "sh" || token == "bash" || token == "-c" ||
            token == "&&" || token.startsWith("-") || token == ">"

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        command.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && quote != '\'' -> escaped = true
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote != null && ch == quote -> quote = null
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun normalize(rawPath: String): String? {
        var path = rawPath.trim().trim('"', '\'')
        if (path.isBlank()) return null
        if (path.startsWith("file://")) {
            path = path.removePrefix("file://")
        }
        if (!path.startsWith("/")) return null
        path = path.substringBefore('?').substringBefore('#')
        val parts = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun describe(rawPath: String): String = runCatching { File(rawPath).canonicalPath }.getOrDefault(rawPath)
}
