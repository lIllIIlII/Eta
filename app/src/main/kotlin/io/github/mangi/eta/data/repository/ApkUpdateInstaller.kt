package io.github.mangi.eta.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.mangi.eta.core.GitHubMirrors
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal object ApkUpdateInstaller {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
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

    /**
     * 下载更新 APK。直连失败时自动切换国内镜像代理；
     * [onProgress] 回调已下载字节与总字节（总字节可能为 0，表示服务器未返回长度）。
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val target = updateApkFile(appContext)
        target.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        if (target.exists()) target.delete()
        var lastException: Exception? = null
        for (candidate in GitHubMirrors.candidates(url)) {
            try {
                downloadOnce(candidate, target, onProgress)
                return@withContext target
            } catch (exception: java.io.IOException) {
                lastException = exception
                target.delete()
            }
        }
        throw lastException ?: java.io.IOException("download_failed")
    }

    private fun downloadOnce(
        url: String,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Eta-Update-Check")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("download_failed_${response.code}")
            }
            val body = response.body ?: throw java.io.IOException("download_empty_body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var read = 0L
                    var lastReported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        // 无论能否得知总大小都持续回调，避免界面进度卡死；
                        // 长时间无数据时由 callTimeout 兜底取消。
                        if (read - lastReported >= PROGRESS_INTERVAL_BYTES || read == total) {
                            lastReported = read
                            onProgress(read, total)
                        }
                    }
                    output.flush()
                    onProgress(read, if (total > 0) total else read)
                }
            }
            if (target.length() <= 0L) throw java.io.IOException("download_empty_file")
        }
    }

    /**
     * 通过 PackageInstaller 会话安装，可靠性优于 ACTION_VIEW 跳转：
     * 直接拉起系统安装确认界面，失败时回退到文件管理器方式。
     */
    fun install(context: Context, file: File) {
        val appContext = context.applicationContext
        if (!file.isFile || file.length() <= 0L) {
            throw IllegalStateException("install_apk_missing")
        }
        runCatching { installViaSession(appContext, file) }
            .recoverCatching { installViaIntent(appContext, file) }
            .getOrThrow()
    }

    private fun installViaSession(context: Context, file: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite("Eta-update", 0, file.length()).use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                }
                session.fsync(output)
            }
            val confirmationIntent = Intent(Intent.ACTION_MAIN)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                confirmationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        } catch (throwable: Throwable) {
            runCatching { session.abandon() }
            throw throwable
        } finally {
            session.close()
        }
    }

    private fun installViaIntent(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun cleanup(context: Context) {
        runCatching { updateApkFile(context.applicationContext).delete() }
    }

    private fun updateApkFile(context: Context): File =
        File(File(context.cacheDir, "update"), "Eta-update.apk")

    private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
}
