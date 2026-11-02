package dev.odysseus.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves the web page's downloads into the phone's Downloads folder. */
object Downloads {
    /** An http(s) download: hand it to the system download manager, with the login cookie. */
    fun enqueue(ctx: Context, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(ctx, "Can't download this one here; open Odysseus in the browser for it")
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        try {
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            toast(ctx, "Downloading $name")
        } catch (e: Exception) {
            toast(ctx, "Download failed: ${e.message}")
        }
    }

    /** A blob:/data: download the page already read: write it to Downloads. */
    fun saveBase64(ctx: Context, rawName: String, mime: String, base64: String) {
        val app = ctx.applicationContext
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(120).ifBlank { "download" }
        TurnHost.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext false
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (_: Exception) { false }
            }
            toast(app, if (ok) "Saved to Downloads: $name" else "Couldn't save $name")
        }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
}
