package eu.kanade.tachiyomi.animeextension.en.senshi

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResolvedStream(
    val m3u8Url: String,
    val subtitles: List<Track> = emptyList(),
)

class EM3u8Resolver(
    private val headers: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private class JsBridge(private val onMedia: (String, String?) -> Unit) {
        @JavascriptInterface
        @Suppress("UNUSED")
        fun passMedia(url: String, subUrl: String?) {
            onMedia(url, subUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(watchUrl: String): ResolvedStream? {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var extractedUrl: String? = null
        val subtitles = mutableListOf<Track>()

        fun handleMedia(url: String, subUrl: String?) {
            if (extractedUrl == null && url.isNotBlank()) {
                extractedUrl = url
                if (!subUrl.isNullOrBlank() && subtitles.none { it.url == subUrl }) {
                    subtitles.add(Track(subUrl, parseSubLabel(subUrl)))
                }
                latch.countDown()
            }
        }

        val bridge = JsBridge { url, subUrl -> handleMedia(url, subUrl) }
        val headersMap = headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }

        handler.post {
            val wv = WebView(context)
            webView = wv

            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }

            wv.addJavascriptInterface(bridge, "AndroidBridge")

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (request == null) return null
                    val url = request.url.toString()

                    if (isVttUrl(url)) {
                        val label = parseSubLabel(url)
                        if (subtitles.none { it.url == url }) {
                            subtitles.add(Track(url, label))
                        }
                    }

                    if (isM3u8Url(url)) {
                        handleMedia(url, null)
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(AUTO_PLAY_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(AUTO_PLAY_SCRIPT, null)
                }
            }

            wv.loadUrl(watchUrl, headersMap)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val resultUrl = extractedUrl ?: return null
        return ResolvedStream(resultUrl, subtitles)
    }

    private fun isM3u8Url(url: String): Boolean {
        if (url.contains("anidap") || url.contains("/stream/")) return false
        val cleanUrl = url.substringBefore("?")
        return cleanUrl.endsWith(".m3u8") ||
            cleanUrl.endsWith(".txt") ||
            url.contains("bcdn") ||
            url.contains("/master.") ||
            url.contains("/index.") ||
            url.contains(".m3u8?") ||
            url.contains(".txt?")
    }

    private fun isVttUrl(url: String): Boolean {
        val cleanUrl = url.substringBefore("?")
        return cleanUrl.endsWith(".vtt") || url.contains("/subtitles/")
    }

    private fun parseSubLabel(url: String): String = when {
        url.contains("en") -> "English"
        url.contains("dub") -> "English [Dub]"
        else -> "Subtitle"
    }

    companion object {
        private const val TIMEOUT_SEC = 20L

        private const val AUTO_PLAY_JS = """
            (function() {
                if (window.__artplayer_hooked) {
                    triggerPlay();
                    return;
                }
                window.__artplayer_hooked = true;

                function notify(options) {
                    try {
                        if (options && options.url) {
                            var subUrl = (options.subtitle && options.subtitle.url) ? options.subtitle.url : "";
                            if (window.AndroidBridge && window.AndroidBridge.passMedia) {
                                window.AndroidBridge.passMedia(options.url, subUrl);
                            }
                        }
                    } catch(e) {}
                }

                var _art = undefined;
                try {
                    Object.defineProperty(window, 'Artplayer', {
                        configurable: true,
                        enumerable: true,
                        get: function() { return _art; },
                        set: function(val) {
                            if (typeof val === 'function') {
                                _art = function(options) {
                                    notify(options);
                                    return new val(options);
                                };
                                Object.setPrototypeOf(_art, val);
                                Object.assign(_art, val);
                            } else {
                                _art = val;
                            }
                        }
                    });
                } catch(e) {}

                function triggerPlay() {
                    try {
                        if (window.Artplayer && window.Artplayer.instances) {
                            window.Artplayer.instances.forEach(function(art) {
                                if (art.option && art.option.url) {
                                    notify(art.option);
                                }
                                try { art.play(); } catch(e) {}
                            });
                        }
                        var playBtn = document.querySelector('.art-state') ||
                                      document.querySelector('.art-icon-play') ||
                                      document.querySelector('.art-control-play');
                        if (playBtn) playBtn.click();
                        var video = document.querySelector('video');
                        if (video) video.play().catch(function(){});
                    } catch(e) {}
                }

                var count = 0;
                var interval = setInterval(function() {
                    triggerPlay();
                    count++;
                    if (count > 20) clearInterval(interval);
                }, 500);
            })();
        """

        private const val AUTO_PLAY_SCRIPT = "javascript:$AUTO_PLAY_JS"
    }
}
