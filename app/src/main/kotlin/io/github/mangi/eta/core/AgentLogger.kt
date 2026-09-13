package io.github.mangi.eta.core

import android.util.Log

internal interface AgentLogger {

    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    private val logThrottle = LogThrottle()

    override fun debug(message: () -> String) {
        Log.d(ModuleConfig.TAG, message())
    }

    override fun info(message: String) {
        Log.i(ModuleConfig.TAG, message)
    }

    override fun warn(message: String) {
        Log.w(ModuleConfig.TAG, message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(ModuleConfig.TAG, message)
        } else {
            Log.e(ModuleConfig.TAG, message, throwable)
        }
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
