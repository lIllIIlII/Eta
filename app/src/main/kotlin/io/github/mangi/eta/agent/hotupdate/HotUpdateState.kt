package io.github.mangi.eta.agent.hotupdate

import java.util.concurrent.atomic.AtomicReference

internal object HotUpdateState {

    data class Config(
        val version: Int = 0,
        val notice: String = "",
        val systemPromptPrefix: String = "",
        val flags: Map<String, String> = emptyMap(),
    )

    private val current = AtomicReference(Config())

    fun apply(config: Config) {
        current.set(config)
    }

    fun snapshot(): Config = current.get()

    fun flag(key: String, default: Boolean): Boolean =
        snapshot().flags[key]?.let { value ->
            value.equals("true", ignoreCase = true) || value == "1"
        } ?: default

    fun flag(key: String, default: String): String =
        snapshot().flags[key] ?: default
}
