package com.deekshith.droidserve.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Last-resort extractor for JS-computed/obfuscated streams: load the page in a (hidden) WebView so
 * its own player runs, and capture the media URL from network interception + the <video> element.
 */
object WebViewResolver {

    private val MEDIA_EXT = Regex("\\.(m3u8|mpd|ts|mp4|m4v|webm|mov|aac|mp3)(\\?|$)", RegexOption.IGNORE_CASE)

    private fun isMediaUrl(u: String): Boolean =
        MEDIA_EXT.containsMatchIn(u) || u.contains("m3u8", true) || u.contains("mp4", true)

    suspend fun extract(context: Context, pageUrl: String, timeoutMs: Long = 8000): String? =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context)
                var finished = false
                var found: String? = null
                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    try { webView.stopLoading(); webView.destroy() } catch (_: Exception) { }
                    cont.resume(result)
                }
                val handler = Handler(Looper.getMainLooper())
                val client = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString()
                        if (u != null && found == null && isMediaUrl(u)) {
                            found = u
                            handler.post { finish(found) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):'';})()"
                        ) { res ->
                            val cleaned = res?.trim()?.trim('"') ?: ""
                            if (cleaned.startsWith("http") && found == null) {
                                found = cleaned
                                finish(found)
                            }
                        }
                        handler.postDelayed({ finish(found) }, timeoutMs)
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        // keep waiting for timeout
                    }
                }
                webView.webViewClient = client
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.loadUrl(pageUrl)
                handler.postDelayed({ finish(found) }, timeoutMs + 2000)
            }
        }
}