package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

data class CfBypassResult(
    val cookies: String,
    val userAgent: String,
)

// This bypasses Cloudflare and takes the hidden WebView cookie

class CloudflareBypass(private val context: Application) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getCookies(pageUrl: String): CfBypassResult = withContext(Dispatchers.Main) {
        withTimeout(25_000) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val defaultUserAgent = settings.userAgentString

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            pollForClearance(view, pageUrl, defaultUserAgent, continuation)
                        }
                    }

                    CookieManager.getInstance().setCookie(pageUrl, "")
                    loadUrl(pageUrl)
                }

                continuation.invokeOnCancellation {
                    try {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun pollForClearance(
        view: WebView,
        url: String,
        userAgent: String,
        continuation: kotlinx.coroutines.CancellableContinuation<CfBypassResult>,
    ) {
        val handler = Handler(Looper.getMainLooper())
        var pollCount = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!continuation.isActive) {
                    view.destroy()
                    return
                }

                pollCount++
                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies != null && "(?:^|; )cf_clearance=".toRegex().containsMatchIn(cookies)) {
                    view.destroy()
                    continuation.resume(CfBypassResult(cookies, userAgent))
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(runnable, 1000)
    }
}
