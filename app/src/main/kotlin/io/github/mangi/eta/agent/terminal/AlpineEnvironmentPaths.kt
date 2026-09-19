package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

internal object AlpineEnvironmentPaths {
    const val READY_MARKER = LinuxEnvironmentPaths.READY_MARKER
    const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
    const val APK_ANALYSIS_MARKER = ".eta-apk-analysis-ready"
    const val PYTHON_TOOLS_MARKER = ".eta-python-tools-ready"
    const val NODE_TOOLS_MARKER = ".eta-node-tools-ready"
    const val SSH_TOOLS_MARKER = ".eta-ssh-tools-ready"
    const val KIMI_TOOLS_MARKER = ".eta-kimi-tools-ready"
    const val GIT_TOOLS_MARKER = ".eta-git-tools-ready"
    const val DEV_TOOLS_MARKER = ".eta-dev-tools-ready"
    const val MEDIA_TOOLS_MARKER = ".eta-media-tools-ready"
    const val NETUTIL_TOOLS_MARKER = ".eta-netutil-tools-ready"
    const val ANDROID_BUILD_TOOLS_MARKER = ".eta-android-build-tools-ready"
    const val ANDROID_NDK_MARKER = ".eta-android-ndk-ready"
    const val TOOLSET_REVISION = 1
    const val APK_ANALYSIS_REVISION = 1
    const val PYTHON_TOOLS_REVISION = 1

    const val NODE_TOOLS_REVISION = 2
    const val SSH_TOOLS_REVISION = 1
    const val KIMI_TOOLS_REVISION = 1
    const val GIT_TOOLS_REVISION = 1
    const val DEV_TOOLS_REVISION = 1
    const val MEDIA_TOOLS_REVISION = 1
    const val NETUTIL_TOOLS_REVISION = 1
    const val ANDROID_BUILD_TOOLS_REVISION = 1
    const val ANDROID_NDK_REVISION = 1

    fun environmentDir(context: Context): File =
        LinuxEnvironmentPaths.environmentDir(context, LinuxDistribution.ALPINE)

    fun rootfsDir(context: Context): File =
        LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.ALPINE)

    fun artifactDir(context: Context): File =
        File(context.cacheDir, "linux-installer/artifacts")

    fun profileStagingDir(context: Context, profile: String): File =
        File(context.cacheDir, "linux-installer/profiles/$profile.installing")

    fun rootfsReady(rootfsPath: String?): Boolean {
        return LinuxEnvironmentPaths.rootfsReady(rootfsPath)
    }

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        val marker = File(rootfsPath, COMMON_TOOLS_MARKER)
        if (!marker.isFile) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "toolset=$TOOLSET_REVISION" }
            }
        }.getOrDefault(false)
    }

}
