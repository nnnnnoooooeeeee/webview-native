package com.webview.webview_app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.net.URLDecoder
import java.util.UUID

/**
 * Saves files the page downloads to the phone's Download folder. A WebView
 * ignores downloads (links answered with "Content-Disposition: attachment",
 * <a download>, blob: and data: URLs) unless the app handles them, so without
 * this an "Export to Excel" button does nothing.
 *
 * - http(s): handed to the system DownloadManager with the page's cookies, so
 *   downloads behind a login work; it shows a notification that opens the file.
 * - blob:/data: (files made by JavaScript): only the page can read them, so it
 *   reads them and hands the bytes to [Bridge], which saves them.
 */
class Downloader(private val activity: Activity, private val webView: WebView) : DownloadListener {

    /** Download waiting for the storage permission (Android 9 and older). */
    private var pending: (() -> Unit)? = null

    /** One-time tokens for the blob:/data: downloads the app asked the page to send. */
    private val expectedTokens = mutableSetOf<String>()

    fun install() {
        webView.setDownloadListener(this)
        webView.addJavascriptInterface(Bridge(), BRIDGE)
    }

    /** Remembers the download="name" of clicked links (once per page), for blob:/data: files. */
    fun injectNameWatcher() {
        webView.evaluateJavascript(NAME_WATCHER_JS, null)
    }

    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        when (Uri.parse(url).scheme?.lowercase()) {
            "http", "https" -> withStoragePermission { enqueue(url, userAgent, contentDisposition, mimeType) }
            "blob", "data" -> withStoragePermission { fetchInPage(url) }
            else -> toast(R.string.download_failed)
        }
    }

    // --- http(s) --------------------------------------------------------------

    private fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = fileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeFor(name, mimeType))
                // The download is a new request from another process: send the
                // login cookies and browser headers the page's own request had.
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                webView.url?.let { addRequestHeader("Referer", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast(R.string.download_started, name)
        } catch (e: Exception) {
            // Bad URL, no shared storage, DownloadManager disabled...
            toast(R.string.download_failed)
        }
    }

    // --- blob: / data: --------------------------------------------------------

    private fun fetchInPage(url: String) {
        val token = UUID.randomUUID().toString()
        expectedTokens += token
        webView.evaluateJavascript(fetchJs(url, token), null)
    }

    /** Called by the page from a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun save(token: String, dataUrl: String, suggestedName: String, mimeType: String) {
            if (!consume(token)) return
            val name = sanitize(suggestedName).ifEmpty { defaultName(mimeType) }
            val bytes = try {
                Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                toast(R.string.download_failed)
                return
            }
            val saved = try {
                writeToDownloads(name, mimeFor(name, mimeType), bytes)
            } catch (e: Exception) {
                null
            }
            if (saved != null) toast(R.string.download_saved, saved) else toast(R.string.download_failed)
        }

        @JavascriptInterface
        fun failed(token: String) {
            if (consume(token)) toast(R.string.download_failed)
        }
    }

    private fun consume(token: String): Boolean = synchronized(expectedTokens) { expectedTokens.remove(token) }

    /** Writes [bytes] to Download/ and returns the name it got (Android may add " (1)"). */
    private fun writeToDownloads(name: String, mimeType: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
            return name
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(activity, arrayOf(file.path), arrayOf(mimeType), null)
        return file.name
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    // --- Storage permission (Android 9 and older) -----------------------------

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pending = action
        activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
    }

    /** Forwarded from Activity.onRequestPermissionsResult; returns true when it was ours. */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_STORAGE) return false
        val action = pending
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            toast(R.string.download_permission_denied)
        }
        return true
    }

    // --- Helpers --------------------------------------------------------------

    private fun toast(message: Int, vararg args: Any) {
        activity.runOnUiThread {
            Toast.makeText(activity, activity.getString(message, *args), Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val BRIDGE = "AndroidDownloads"
        const val REQUEST_STORAGE = 2
        val GENERIC_TYPES = setOf("", "application/octet-stream", "application/force-download", "binary/octet-stream")

        /** The name from Content-Disposition (filename*= or filename=), else Android's guess. */
        fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
            val header = contentDisposition.orEmpty()
            val encoded = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)
            val fromHeader = when {
                encoded != null -> try {
                    URLDecoder.decode(encoded.groupValues[2].trim(), encoded.groupValues[1].ifEmpty { "UTF-8" })
                } catch (e: Exception) {
                    null
                }
                else -> Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)
                    ?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] }.trim() }
            }
            return sanitize(fromHeader.orEmpty()).ifEmpty {
                sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
            }.ifEmpty { defaultName(mimeType) }
        }

        fun sanitize(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_")
                .trim().trimStart('.')
                .takeLast(120)

        fun defaultName(mimeType: String?): String {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType?.lowercase().orEmpty())
            return "download_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
        }

        /** The server's type, or the one for the file's extension when that type is generic. */
        fun mimeFor(name: String, mimeType: String?): String {
            val type = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (type !in GENERIC_TYPES) return type
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun jsString(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c < ' ' || c == '\u2028' || c == '\u2029' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }

        /** Reads a blob:/data: URL in the page and sends it to the bridge as a base64 data URL. */
        fun fetchJs(url: String, token: String): String = """
            (function (url, token) {
              var name = (window.__downloadNames && window.__downloadNames[url]) || '';
              fetch(url).then(function (r) { return r.blob(); }).then(function (blob) {
                var reader = new FileReader();
                reader.onload = function () { $BRIDGE.save(token, reader.result, name, blob.type || ''); };
                reader.onerror = function () { $BRIDGE.failed(token); };
                reader.readAsDataURL(blob);
              }).catch(function () { $BRIDGE.failed(token); });
            })(${jsString(url)}, ${jsString(token)});
        """.trimIndent()

        val NAME_WATCHER_JS = """
            (function () {
              if (window.__downloadNameWatcher) return;
              window.__downloadNameWatcher = true;
              window.__downloadNames = {};
              document.addEventListener('click', function (e) {
                for (var el = e.target; el && el.nodeType === 1; el = el.parentElement) {
                  if (el.tagName === 'A' && el.hasAttribute('download')) {
                    window.__downloadNames[el.href] = el.getAttribute('download');
                    break;
                  }
                }
              }, true);
            })();
        """.trimIndent()
    }
}
