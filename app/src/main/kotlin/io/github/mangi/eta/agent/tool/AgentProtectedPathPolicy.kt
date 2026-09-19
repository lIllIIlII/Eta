package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.config.Prefs
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

    fun isProtected(rawPath: String): Boolean {
        val normalized = normalize(rawPath) ?: return false
        if (normalized == "/") return false
        return PROTECTED_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }

    fun violationForWrite(rawPath: String): String? {
        val normalized = normalize(rawPath) ?: return null
        if (isUserBlocked(normalized)) return "USER_BLOCKED_PATH_DENIED:$normalized"
        return if (isProtected(normalized)) {
            "PROTECTED_PATH_WRITE_DENIED:$normalized"
        } else {
            null
        }
    }

    fun isUserBlocked(rawPath: String): Boolean {
        val normalized = normalize(rawPath) ?: return false
        val prefixes = userBlockedPrefixes()
        if (prefixes.isEmpty()) return false
        return prefixes.any { prefix -> normalized == prefix || normalized.startsWith("$prefix/") }
    }

    private fun userBlockedPrefixes(): List<String> {
        if (!Prefs.blocklistEnabled()) return emptyList()
        return Prefs.blocklistPaths().mapNotNull { entry ->
            val normalized = normalize(entry) ?: return@mapNotNull null
            normalized.takeIf { it != "/" }
        }
    }

    fun violationForCommand(rawCommand: String): String? {
        val command = rawCommand.trim()
        if (command.isEmpty()) return null
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return null

        val blockedPrefixes = userBlockedPrefixes()
        if (blockedPrefixes.isNotEmpty()) {
            REDIRECT_PATTERN.findAll(command).forEach { match ->
                val target = match.groupValues[2].trim('"', '\'', ' ')
                val normalized = normalize(target)
                if (normalized != null && blockedPrefixes.any { prefix ->
                        normalized == prefix || normalized.startsWith("$prefix/")
                    }
                ) {
                    return "USER_BLOCKED_PATH_DENIED:$normalized"
                }
            }
            tokens.forEach { token ->
                if (!looksLikePath(token)) return@forEach
                val normalized = normalize(token) ?: return@forEach
                if (blockedPrefixes.any { prefix ->
                        normalized == prefix || normalized.startsWith("$prefix/")
                    }
                ) {
                    return "USER_BLOCKED_PATH_DENIED:$normalized"
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
