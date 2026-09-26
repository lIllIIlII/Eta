package io.github.mangi.eta.ui.app

import android.content.Context

internal class EnhancementSettingsHistory(context: Context) {
    private val snapshot = context.applicationContext
        .getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)

    val hasUsedSystemizer: Boolean get() = snapshot.getBoolean("has_used_systemizer", false)

    fun recordSystemizerUse() {
        snapshot.edit().putBoolean("has_used_systemizer", true).apply()
    }
}
