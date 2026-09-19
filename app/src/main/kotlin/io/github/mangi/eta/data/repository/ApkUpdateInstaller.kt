package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.GitHubMirrors
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun formatDownloadSize(bytes: Long): String =
    if (bytes < 1024L * 1024L) {
        "${bytes / 1024} KB"
    } else {
        String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }

internal object ApkUpdateInstaller {

    private const val USER_AGENT = "Eta-Update-Download"
    private const val MIN_APK_BYTES = 2L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 512L * 1024L

    data class Progress(
        val percent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val attempt: Int,
        val sourceUrl: String,
    ) {
        val indeterminate: Boolean get() = totalBytes <= 0L
    }

    sealed interface DownloadResult {
        data class Success(val file: File, val sourceUrl: String, val attempts: Int) : DownloadResult

        data class Failed(val attempts: Int) : DownloadResult
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun download(context: Context, url: String, onProgress: (Progress) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val currentVersionCode = installedVersionCode(appContext)
            val candidates = GitHubMirrors.candidates(url, Prefs.githubMirrorFirst())
            if (candidates.isEmpty()) return@withContext DownloadResult.Failed(0)

            val target = updateApkFile(appContext)
            target.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            val partial = File(target.parentFile, "${target.name}.part")

            var attempts = 0
            for (candidate in candidates) {
                attempts++
                partial.delete()
                val downloaded = try {
                    downloadOnce(appContext, candidate, partial, currentVersionCode, attempts, onProgress)
                } catch (cancellation: CancellationException) {
                    partial.delete()
                    throw cancellation
                } catch (throwable: Throwable) {
                    false
                }
                if (!downloaded) {
                    partial.delete()
                    continue
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    continue
                }
                onProgress(
                    Progress(
                        percent = 100,
                        downloadedBytes = target.length(),
                        totalBytes = target.length(),
                        attempt = attempts,
                        sourceUrl = candidate,
                    ),
                )
                return@withContext DownloadResult.Success(
                    file = target,
                    sourceUrl = candidate,
                    attempts = attempts,
                )
            }
            target.delete()
            DownloadResult.Failed(attempts)
        }

    fun install(context: Context, file: File): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun cleanup(context: Context) {
        val target = updateApkFile(context.applicationContext)
        runCatching { target.delete() }
        runCatching { File(target.parentFile, "${target.name}.part").delete() }
    }

    fun installedVersionCode(context: Context): Long = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .longVersionCode
    }.getOrDefault(0L)

    private suspend fun downloadOnce(
        context: Context,
        url: String,
        partial: File,
        currentVersionCode: Long,
        attempt: Int,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream, */*")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val total = body.contentLength()
            var written = 0L
            var lastReportedBytes = 0L
            var lastPercent = Int.MIN_VALUE
            FileOutputStream(partial).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read.toLong()
                        val percent = if (total > 0L) {
                            ((written * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent || written - lastReportedBytes >= PROGRESS_STEP_BYTES) {
                            lastPercent = percent
                            lastReportedBytes = written
                            onProgress(
                                Progress(
                                    percent = percent,
                                    downloadedBytes = written,
                                    totalBytes = total,
                                    attempt = attempt,
                                    sourceUrl = url,
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }
        }
        return validateDownload(context, partial, currentVersionCode)
    }

    private fun validateDownload(context: Context, file: File, currentVersionCode: Long): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0),
            )
        }.getOrNull() ?: return false
        if (info.packageName != context.packageName) return false
        return currentVersionCode <= 0L || info.longVersionCode > currentVersionCode
    }

    private fun updateApkFile(context: Context): File =
        File(File(context.cacheDir, "update"), "Eta-update.apk")
}
