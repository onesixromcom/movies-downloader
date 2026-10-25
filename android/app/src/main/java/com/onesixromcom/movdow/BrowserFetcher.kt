package com.onesixromcom.movdow

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Replacement for curl-impersonate: pages are loaded by a real Chromium WebView,
 * so Cloudflare sees a genuine browser. Ajax calls run as fetch() inside the page,
 * sharing its cookies and origin.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserFetcher(private val webView: WebView) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var pageStarted: CompletableDeferred<Unit>? = null

    val userAgent: String get() = webView.settings.userAgentString

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageStarted?.complete(Unit)
            }
        }
    }

    /** Load [url] and return its HTML once [isReady] accepts it (e.g. after a Cloudflare challenge passed). */
    suspend fun loadPage(url: String, timeoutMs: Long = 45_000, isReady: (String) -> Boolean): String =
        withContext(Dispatchers.Main) {
            val started = CompletableDeferred<Unit>()
            pageStarted = started
            AppLog.i("WebView: loading $url")
            webView.loadUrl(url)
            withTimeoutOrNull(timeoutMs) {
                // Wait for the new navigation, so the previous page is not read.
                // Don't wait for onPageFinished: it fires only after all ads/trackers are loaded.
                started.await()
                var html = ""
                var attempts = 0
                while (!isReady(html)) {
                    delay(500)
                    html = evaluate("document.readyState == 'loading' ? '' : document.documentElement.outerHTML")
                    if (++attempts % 10 == 0) AppLog.i("WebView: still waiting for the page (${attempts / 2}s)…")
                }
                AppLog.i("WebView: page ready, ${html.length / 1024} KB")
                html
            } ?: throw IOException("Page was not loaded in time (Cloudflare check?). Open the page in the browser once and try again.")
        }

    /** fetch() [url] from inside the currently loaded page. */
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 30_000): String =
        withContext(Dispatchers.Main) {
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<String>()
            pending[id] = result
            val quotedId = JSONObject.quote(id)
            AppLog.i("WebView: fetch $url")
            webView.evaluateJavascript(
                """
                fetch(${JSONObject.quote(url)}, {credentials: 'include', headers: ${JSONObject(headers)}})
                  .then(r => r.ok ? r.text() : Promise.reject('HTTP ' + r.status))
                  .then(t => AndroidBridge.onResult($quotedId, t))
                  .catch(e => AndroidBridge.onError($quotedId, String(e)));
                """.trimIndent(),
                null,
            )
            try {
                withTimeoutOrNull(timeoutMs) { result.await() } ?: throw IOException("Request timed out: $url")
            } finally {
                pending.remove(id)
            }
        }

    private suspend fun evaluate(js: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { raw ->
            // Result comes JSON-encoded ("\"<html>...\"").
            val value = if (raw == null || raw == "null") "" else JSONTokener(raw).nextValue() as? String ?: raw
            cont.resume(value)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, body: String) {
            pending[id]?.complete(body)
        }

        @JavascriptInterface
        fun onError(id: String, error: String) {
            pending[id]?.completeExceptionally(IOException(error))
        }
    }
}
