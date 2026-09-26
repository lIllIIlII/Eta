package io.github.mangi.eta.ui.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal object ConversationImageRenderer {

    private const val IMAGE_WIDTH_PX = 900
    private const val MAX_IMAGE_HEIGHT_PX = 10_000
    private const val SETTLE_DELAY_MS = 450L

    suspend fun render(context: Context, html: String): Bitmap? =
        withContext(Dispatchers.Main) {
            renderBlocking(context.applicationContext, html)
        }

    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, ConversationShareHtmlBuilder.imageFileName())
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Eta")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                } ?: throw IllegalStateException("无法写入图片")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.onFailure {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            uri
        }

    private suspend fun renderBlocking(context: Context, html: String): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            var settled = false
            val webView = WebView(context)
            webView.visibility = View.INVISIBLE
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webView.setBackgroundColor(0xFFEEF0F5.toInt())
            webView.settings.javaScriptEnabled = false
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.postDelayed({
                        if (settled) return@postDelayed
                        settled = true
                        val bitmap = runCatching { capture(view) }.getOrNull()
                        runCatching { view.destroy() }
                        if (continuation.isActive) {
                            continuation.resume(bitmap)
                        }
                    }, SETTLE_DELAY_MS)
                }
            }
            continuation.invokeOnCancellation {
                settled = true
                runCatching { webView.destroy() }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }

    private fun capture(view: WebView): Bitmap? {
        val width = IMAGE_WIDTH_PX
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = view.measuredHeight.coerceIn(200, MAX_IMAGE_HEIGHT_PX)
        if (height <= 200) return null
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }
}
