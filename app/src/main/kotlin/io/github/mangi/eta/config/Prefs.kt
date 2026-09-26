package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences

internal object Prefs {

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    object Keys {
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_APPROVE_TOOLS = "agent_auto_approve_tools"
        const val AGENT_PHONE_CONTROL_APPROVAL = "agent_phone_control_approval"
        const val AGENT_BLOCK_FILE_ACCESS = "agent_block_file_access"
        const val AGENT_BLOCKED_FILE_PATHS = "agent_blocked_file_paths"
        const val AGENT_CUSTOM_LINUX_TOOLS = "agent_custom_linux_tools"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_APPROVE_TOOLS to false,
            AGENT_PHONE_CONTROL_APPROVAL to true,
            AGENT_BLOCK_FILE_ACCESS to false,
        )

        val LOCAL_AGENT_KEYS: Set<String> = BOOLEAN_DEFAULTS.keys
    }

    @Volatile
    private var localAgent: SharedPreferences? = null

    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return localAgent?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return localAgent?.getString(key, "") ?: ""
    }

    fun localAgentString(key: String): String {
        return localAgent?.getString(key, "") ?: ""
    }

    fun setLocalAgentString(key: String, value: String): Boolean {
        val preferences = localAgent ?: return false
        return runCatching { preferences.edit().putString(key, value).commit() }.getOrDefault(false)
    }

    fun localAgentPreferences(): SharedPreferences? = localAgent
}
