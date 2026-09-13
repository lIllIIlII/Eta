package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

internal object SessionStatusProtocol {

    fun newMarker(): String = "__ETA_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    fun statusCommand(marker: String): String =
        "printf '\\n$marker:%s:%s\\n' \"\$?\" \"\$PWD\""

    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; eta_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$eta_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

internal class ByteArrayOutputCollector {
    private val output = ByteArrayOutputStream()
    private var totalBytesRead = 0L
    private var truncated = false

    fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(this) {
                    totalBytesRead += read.toLong()
                    val allowed = (maxBytes - output.size()).coerceAtLeast(0)
                    if (allowed > 0) {
                        output.write(buffer, 0, read.coerceAtMost(allowed))
                    }
                    if (read > allowed) {
                        truncated = true
                    }
                }
            }
        }.onFailure { throwable ->
            if (throwable !is IOException) throw throwable
        }
    }

    fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

    fun text(): String = bytes().decodeToString()

    fun totalBytesRead(): Long = synchronized(this) { totalBytesRead }

    fun isTruncated(): Boolean = synchronized(this) { truncated }

    fun clear() {
        synchronized(this) {
            output.reset()
            totalBytesRead = 0
            truncated = false
        }
    }
}
