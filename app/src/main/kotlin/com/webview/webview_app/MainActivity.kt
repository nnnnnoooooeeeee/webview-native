package com.webview.webview_app

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.net.URISyntaxException

/**
 * Shows the configured URL (assets/webview.json, see [AppConfig]) full screen in
 * the phone's own WebView, with pull-to-refresh, a progress bar, an offline/error
 * screen, back navigation, file uploads and links to other apps (tel:, mailto:...).
 */
class MainActivity : Activity() {

    private lateinit var config: AppConfig
    private lateinit var webView: WebView
    private lateinit var pullToRefresh: PullToRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView

    /** The page's pending <input type="file">, answered in onActivityResult. */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** Android 13+: handles Back while the page has history (an OnBackInvokedCallback). */
    private var backCallback: Any? = null
    private var backRegistered = false

    /** The WebView's renderer died; the WebView must not be used any more. */
    private var webViewDead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.load(this)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        pullToRefresh = findViewById(R.id.pull_to_refresh)
        progressBar = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        errorMessage = findViewById(R.id.error_message)
        findViewById<View>(R.id.retry).setOnClickListener { reload() }

        if (Build.VERSION.SDK_INT >= 30) drawBehindSystemBars()
        setUpWebView()
        setUpPullToRefresh()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(config.url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            builtInZoomControls = true // pinch to zoom, where the page allows it
            displayZoomControls = false
        }
        webView.webViewClient = PageClient()
        webView.webChromeClient = ChromeClient()
    }

    //========== PULL TO REFRESH ==========

    private fun setUpPullToRefresh() {
        pullToRefresh.isEnabled = config.pullToRefresh
        pullToRefresh.onRefresh = { reload() }
        if (config.pullToRefresh) webView.addJavascriptInterface(ScrollBridge(), SCROLL_BRIDGE)
    }

    /**
     * Lets the page report whether the touched element is inside an inner scroll
     * area that is not at its top (common in web apps with a fixed header). The
     * WebView itself is then at the top, but a pull should scroll that area
     * instead of refreshing. It only receives a boolean, so any page may call it.
     */
    private inner class ScrollBridge {
        @JavascriptInterface
        fun setInnerScrolled(scrolled: Boolean) {
            pullToRefresh.innerScrolled = scrolled
        }
    }

    /** Installs the touchstart listener that feeds [ScrollBridge] (once per page). */
    private fun injectScrollWatcher() {
        if (!config.pullToRefresh) return
        webView.evaluateJavascript(SCROLL_WATCHER_JS, null)
    }

    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            openInOtherApp(request.url)

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            hideError()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectScrollWatcher()
            pullToRefresh.finishRefreshing()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError(error.description?.toString().orEmpty())
                pullToRefresh.finishRefreshing()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateBackHandling()
        }

        @TargetApi(26)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page crashed or Android killed it to free memory: start over
            // instead of letting the whole app crash.
            webViewDead = true
            recreate()
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            // While the pull-to-refresh spinner runs it is the only loading sign, like in Chrome.
            val loading = newProgress < 100 && errorView.visibility != View.VISIBLE
            progressBar.visibility = if (loading && !pullToRefresh.isRefreshing) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            try {
                startActivityForResult(fileChooserIntent(fileChooserParams), PICK_FILES)
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
            }
            return true
        }
    }

    /** An intent for the system file picker that honours the input's accept="" and multiple. */
    private fun fileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val mimeTypes = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { type ->
                if (type.startsWith(".")) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1)) ?: "*/*"
                } else {
                    type
                }
            }
            .distinct()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            when {
                mimeTypes.isEmpty() || "*/*" in mimeTypes -> type = "*/*"
                mimeTypes.size == 1 -> type = mimeTypes[0]
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                }
            }
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }

    @Deprecated("Activity result API from the platform; fine without AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK_FILES) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = fileCallback ?: return
        fileCallback = null
        val uris = if (resultCode != RESULT_OK || data == null) {
            null
        } else {
            data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data.data?.let { arrayOf(it) }
        }
        callback.onReceiveValue(uris)
    }

    /**
     * Web links stay in the WebView; anything else (tel:, mailto:, whatsapp:,
     * intent:...) goes to the app that handles it. Returns true when the WebView
     * should not load the URL itself.
     */
    private fun openInOtherApp(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme in WEB_SCHEMES) return false

        var intent: Intent? = null
        try {
            intent = if (scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // Only let a web page start apps the way a browser would.
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app for it. intent: links may name a web page to show instead.
            val fallback = intent?.getStringExtra("browser_fallback_url")
            if (fallback != null && Uri.parse(fallback).scheme?.lowercase() in setOf("http", "https")) {
                webView.loadUrl(fallback)
            }
        } catch (e: URISyntaxException) {
            // Malformed intent: link; ignore it.
        }
        return true
    }

    private fun reload() {
        hideError()
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") {
            webView.loadUrl(config.url)
        } else {
            webView.reload()
        }
    }

    private fun showError(message: String) {
        errorMessage.text = getString(R.string.error_message, message)
        errorView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
    }

    // --- Back button ----------------------------------------------------------

    /**
     * Android 13+ asks for a back callback only while there is somewhere to go
     * back to; otherwise the system handles Back (with its predictive animation).
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val wanted = webView.canGoBack()
        if (wanted == backRegistered) return
        val callback = (backCallback as OnBackInvokedCallback?)
            ?: OnBackInvokedCallback { if (webView.canGoBack()) webView.goBack() }.also { backCallback = it }
        if (wanted) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        backRegistered = wanted
    }

    /** Android 12 and older. */
    @Deprecated("Replaced by updateBackHandling() on Android 13+.")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // --- Window ---------------------------------------------------------------

    /**
     * Android 15 always draws apps behind the status and navigation bars, so
     * keep the content clear of them (and of the keyboard) ourselves.
     */
    @TargetApi(30)
    private fun drawBehindSystemBars() {
        // Deprecated because Android 15+ always does this; still needed on Android 11-14.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    // --- Lifecycle ------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (!webViewDead) webView.onResume()
    }

    override fun onPause() {
        if (!webViewDead) webView.onPause()
        CookieManager.getInstance().flush() // keep the login when Android closes the app
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDead) webView.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val PICK_FILES = 1
        val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        const val SCROLL_BRIDGE = "AndroidPullToRefresh"

        /**
         * On every touchstart, walks up from the touched element and reports
         * whether any scrollable ancestor is scrolled down (scrollTop > 0).
         */
        val SCROLL_WATCHER_JS = """
            (function () {
              if (window.__pullToRefreshWatcher) return;
              window.__pullToRefreshWatcher = true;
              document.addEventListener('touchstart', function (e) {
                var scrolled = false;
                for (var el = e.target; el && el.nodeType === 1; el = el.parentElement) {
                  if (el.scrollTop > 0) {
                    var overflow = getComputedStyle(el).overflowY;
                    if (overflow === 'auto' || overflow === 'scroll' || overflow === 'overlay') { scrolled = true; break; }
                  }
                }
                $SCROLL_BRIDGE.setInnerScrolled(scrolled);
              }, { passive: true, capture: true });
            })();
        """.trimIndent()
    }
}
