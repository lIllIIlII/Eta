package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import android.os.StatFs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.GitHubMirrors
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Android NDK（ARM64/x86_64 主机）安装器。
 *
 * Google 官方从未发布 linux-aarch64 主机版本，因此 ARM64 设备使用
 * zhuwanhong/android-sdk-linux-arm64 社区重构建（从 AOSP/LLVM 源码编译，
 * 构建可复现并公布 sha256）；x86_64 设备直接使用官方 dl.google.com 制品。
 * 安装位置：/opt/eta/android-ndk/current，并写入 ndk-build 入口与环境变量脚本。
 */
internal class AndroidNdkInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val artifactDownloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    fun isReady(): Boolean = androidNdkReady(rootfs)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        val artifact = artifactFor(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext PackageProfileInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            )

        val stat = StatFs(rootfs.absolutePath)
        val availableBytes = stat.availableBytes
        val requiredBytes = artifact.sizeBytes * SPACE_MULTIPLIER
        if (availableBytes in 1 until requiredBytes) {
            return@withContext PackageProfileInstallResult.InsufficientSpace(
                requiredBytes = requiredBytes,
                availableBytes = availableBytes,
            )
        }

        coroutineContext.ensureActive()
        onProgress(
            PackageProfileInstallProgress(
                stage = PackageProfileInstallStage.DOWNLOADING,
                totalBytes = artifact.sizeBytes,
            ),
        )
        val archive = File(context.cacheDir, "linux-installer/profiles/${artifact.fileName}.download")
        val downloaded = try {
            artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }.takeIf { it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warn(
                "Android NDK action=download outcome=failed errorType=${throwable.safeLogType()}",
            )
            null
        }
        if (downloaded == null) {
            archive.delete()
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.DOWNLOADING)
        }

        coroutineContext.ensureActive()
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        val result = try {
            activate(artifact, archive)
        } finally {
            archive.delete()
        }
        if (!result) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    /** 下载的压缩包展开到 rootfs 内的固定位置，并写入入口脚本与就绪标记。 */
    private suspend fun activate(artifact: VerifiedArtifact, archive: File): Boolean {
        val installRoot = File(rootfs, "opt/eta/android-ndk")
        val target = File(installRoot, artifact.version)
        val staging = File(installRoot, "installing")
        val marker = File(rootfs, AlpineEnvironmentPaths.ANDROID_NDK_MARKER)
        return try {
            staging.deleteRecursively()
            target.deleteRecursively()
            if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
                extractRootless(archive, staging)
                flattenSingleRoot(staging)
                require(staging.renameTo(target)) { "无法移动 NDK 目录" }
                writeWrapperScriptsRootless(target)
            } else {
                extractWithRoot(archive, staging)
                flattenSingleRootWithRoot(staging)
                linkCurrentWithRoot(installRoot, target, marker)
            }
            marker.writeText("profile=${AlpineEnvironmentPaths.ANDROID_NDK_REVISION}\nndk=${artifact.version}\n")
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warn(
                "Android NDK action=activate outcome=failed errorType=${throwable.safeLogType()}",
            )
            staging.deleteRecursively()
            false
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun extractRootless(archive: File, staging: File) {
        if (archive.name.endsWith(".zip")) {
            RootlessZipExtractor.extract(archive, staging)
        } else {
            RootlessLinuxInstaller.extract(archive, staging, xz = false)
        }
    }

    private fun writeWrapperScriptsRootless(target: File) {
        val current = File(rootfs, "opt/eta/android-ndk/current")
        current.deleteRecursively()
        Files.createSymbolicLink(current.toPath(), Path.of(target.name))
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val ndkBuild = File(localBin, "ndk-build")
        ndkBuild.writeText(ndkBuildWrapper())
        require(ndkBuild.setExecutable(true, false))
        writeProfileScriptRootless()
    }

    private fun writeProfileScriptRootless() {
        val profileDir = File(rootfs, "etc/profile.d")
        if (profileDir.isDirectory || profileDir.mkdirs()) {
            File(profileDir, "eta-android-ndk.sh").writeText(profileScript())
        }
    }

    private suspend fun extractWithRoot(archive: File, staging: File) {
        val command = if (archive.name.endsWith(".zip")) {
            """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" mkdir -p ${shellQuote(staging.absolutePath)} || exit 66
            "${'$'}eta_busybox" unzip -q ${shellQuote(archive.absolutePath)} -d ${shellQuote(staging.absolutePath)} || exit 67
            """.trimIndent()
        } else {
            """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" mkdir -p ${shellQuote(staging.absolutePath)} || exit 66
            "${'$'}eta_busybox" tar -xzf ${shellQuote(archive.absolutePath)} -C ${shellQuote(staging.absolutePath)} || exit 67
            """.trimIndent()
        }
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 1_800,
            environment = TerminalEnvironment.ANDROID,
        )
        require(result.exitCode == 0) { "NDK 解压失败（exit=${result.exitCode}）" }
    }

    private suspend fun flattenSingleRootWithRoot(staging: File) {
        val children = staging.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val command = """
                ${AndroidBusyBox.discoveryScript()}
                [ -n "${'$'}eta_busybox" ] || exit 127
                eta_inner=${shellQuote(children[0].absolutePath)}
                "${'$'}eta_busybox" mv "${'$'}eta_inner"/* ${shellQuote(staging.absolutePath)}/ || exit 68
                "${'$'}eta_busybox" rmdir "${'$'}eta_inner" || true
            """.trimIndent()
            val result = InstallerShellRunner.run(
                command = command,
                timeoutSeconds = 300,
                environment = TerminalEnvironment.ANDROID,
            )
            require(result.exitCode == 0) { "NDK 目录整理失败" }
        }
    }

    private suspend fun linkCurrentWithRoot(installRoot: File, target: File, marker: File) {
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" ln -sfn ${shellQuote(target.name)} ${shellQuote(File(installRoot, "current").absolutePath)} || exit 70
            "${'$'}eta_busybox" mkdir -p ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)} || exit 70
            printf %s ${shellQuote(ndkBuildWrapper())} > ${shellQuote(File(rootfs, "usr/local/bin/ndk-build").absolutePath)} || exit 71
            "${'$'}eta_busybox" chmod 0755 ${shellQuote(File(rootfs, "usr/local/bin/ndk-build").absolutePath)} || exit 71
            "${'$'}eta_busybox" mkdir -p ${shellQuote(File(rootfs, "etc/profile.d").absolutePath)} || exit 70
            printf %s ${shellQuote(profileScript())} > ${shellQuote(File(rootfs, "etc/profile.d/eta-android-ndk.sh").absolutePath)} || exit 71
            "${'$'}eta_busybox" chmod 0644 ${shellQuote(File(rootfs, "etc/profile.d/eta-android-ndk.sh").absolutePath)} || true
            "${'$'}eta_busybox" rm -f ${shellQuote(marker.absolutePath)}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 120,
            environment = TerminalEnvironment.ANDROID,
        )
        require(result.exitCode == 0) { "NDK 激活失败（exit=${result.exitCode}）" }
    }

    private fun flattenSingleRoot(staging: File) {
        val children = staging.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles().orEmpty().forEach { child ->
                require(child.renameTo(File(staging, child.name))) { "NDK 目录整理失败" }
            }
            inner.delete()
        }
    }

    private fun ndkBuildWrapper(): String =
        """
        #!/bin/sh
        export ANDROID_NDK_HOME=/opt/eta/android-ndk/current
        exec "${'$'}ANDROID_NDK_HOME/ndk-build" "${'$'}@"
        """.trimIndent() + "\n"

    private fun profileScript(): String =
        """
        export ANDROID_NDK_HOME=/opt/eta/android-ndk/current
        export ANDROID_NDK_ROOT=${'$'}ANDROID_NDK_HOME
        case ":${'$'}PATH:" in
          *":${'$'}ANDROID_NDK_HOME:"*) ;;
          *) export PATH="${'$'}ANDROID_NDK_HOME:${'$'}PATH" ;;
        esac
        """.trimIndent() + "\n"

    companion object {
        private const val SPACE_MULTIPLIER = 6L
        private val installMutex = Mutex()

        internal val NDK_VERSION = "28.2.13676358"
        private const val SDK_ARM64_REPO = "zhuwanhong/android-sdk-linux-arm64"
        private const val SDK_ARM64_TAG = "v1.0.0"
        private const val OFFICIAL_X86_64_VERSION = "r28c"

        /** 按设备 ABI 选择 NDK 制品；aarch64 社区重构建带官方校验和，x86_64 走官方源。 */
        internal fun artifactFor(abis: List<String>): VerifiedArtifact? = when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> VerifiedArtifact(
                id = "android-ndk-aarch64",
                version = NDK_VERSION,
                fileName = "android-ndk-$NDK_VERSION-linux-aarch64-ours.tar.gz",
                url = "https://github.com/$SDK_ARM64_REPO/releases/download/$SDK_ARM64_TAG/" +
                    "android-ndk-$NDK_VERSION-linux-aarch64-ours.tar.gz",
                sha256 = "4c0b810194744c7b542e72a0daf456c0b3eb460b565d83ab032ceb59e5735c39",
                sizeBytes = 356_638_192L,
            ).let { artifact ->
                artifact.copy(preferredUrls = GitHubMirrors.preferred(artifact.url))
            }
            abis.any { it.equals("x86_64", ignoreCase = true) } -> VerifiedArtifact(
                id = "android-ndk-x86_64",
                version = OFFICIAL_X86_64_VERSION,
                fileName = "android-ndk-$OFFICIAL_X86_64_VERSION-linux.zip",
                url = "https://dl.google.com/android/repository/android-ndk-$OFFICIAL_X86_64_VERSION-linux.zip",
                // 官方未公布 sha256，HTTPS + 大小校验兜底。
                sha256 = "",
                sizeBytes = 722_261_334L,
            )
            else -> null
        }
    }
}

/** PROOT 免 Root 模式下的 Java 侧 zip 解压（带大小与路径防护）。 */
private object RootlessZipExtractor {

    fun extract(archive: File, destination: File) {
        require(destination.mkdirs() || destination.isDirectory)
        val root = destination.canonicalFile
        var bytes = 0L
        var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "归档条目过多" }
                val name = entry.name.removePrefix("./")
                require(name.isNotBlank() && !name.contains("..")) { "非法归档路径" }
                val target = File(root, name)
                require(target.canonicalPath.startsWith(root.canonicalPath)) { "非法归档路径" }
                when {
                    entry.isDirectory -> require(target.mkdirs() || target.isDirectory)
                    else -> {
                        target.parentFile?.let { parent ->
                            require(parent.mkdirs() || parent.isDirectory)
                        }
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= MAX_EXPANDED_BYTES) { "归档展开大小超限" }
                                output.write(buffer, 0, count)
                            }
                        }
                        require(target.setReadable(true, true) && target.setWritable(true, true))
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private const val MAX_ENTRIES = 200_000
    private const val MAX_EXPANDED_BYTES = 3L * 1024 * 1024 * 1024
}

internal fun androidNdkReady(rootfs: File): Boolean {
    val marker = File(rootfs, AlpineEnvironmentPaths.ANDROID_NDK_MARKER)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${AlpineEnvironmentPaths.ANDROID_NDK_REVISION}" }
    }
}
