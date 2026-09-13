package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal object ApkUpdateInstaller {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val target = updateApkFile(appContext)
            target.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            if (target.exists()) target.delete()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Eta-HotUpdate")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("download_failed_${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("download_empty_body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var read = 0L
                        var lastProgress = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0) {
                                val progress = ((read * 100L) / total).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
            target
        }

    fun install(context: Context, file: File) {
        val appContext = context.applicationContext
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
    }

    fun cleanup(context: Context) {
        runCatching { updateApkFile(context.applicationContext).delete() }
    }

    private fun updateApkFile(context: Context): File =
        File(File(context.cacheDir, "update"), "Eta-update.apk")
}
