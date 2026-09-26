package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_APPROVE_TOOLS to false,
                Prefs.Keys.AGENT_PHONE_CONTROL_APPROVAL to true,
                Prefs.Keys.AGENT_BLOCK_FILE_ACCESS to false,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(Prefs.Keys.BOOLEAN_DEFAULTS.keys, Prefs.Keys.LOCAL_AGENT_KEYS)
    }
}
