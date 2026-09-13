package io.github.mangi.eta.agent.accessibility

internal data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false

        if (text != refreshed.text) return false
        if (description != refreshed.description) return false
        return true
    }
}

internal object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
    ): Boolean =
        identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated)
}
