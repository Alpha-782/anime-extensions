/* The following file is slightly modified and taken from: https://github.com/LagradOst/CloudStream-3/blob/4d6050219083d675ba9c7088b59a9492fcaa32c7/app/src/main/java/com/lagradost/cloudstream3/animeproviders/AnimePaheProvider.kt
 * It is published under the following license:
 *
MIT License

Copyright (c) 2021 Osten

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 *
 */

package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class KwikExtractor(
    private val client: OkHttpClient,
    private val context: Application? = null,
) {
    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

    private val cfClearanceRegex = Regex("(?:^|; )cf_clearance=")

    private data class KwikHtmlResult(
        val cookies: String,
        val html: String,
        val url: String,
        val cfBypassResult: CfBypassResult? = null,
    )

    private val kwikClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(client.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val eContent = kwikClient.newCall(GET(kwikUrl, Headers.headersOf("referer", referer)))
            .await().useAsJsoup()
        val script = eContent.selectFirst("script:containsData(eval\\(function)")!!.data().substringAfterLast("eval(function(")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")!!
        return unpacked.substringAfter("const source=\\'").substringBefore("\\';")
    }

    suspend fun getStreamUrlFromKwik(paheUrl: String): String {
        val noRedirectClient = kwikClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val kwikUrl = "https://" + noRedirectClient.newCall(GET("$paheUrl/i")).await()
            .use { it.header("location")!!.substringAfterLast("https://") }

        val kwikResult = fetchKwikHtml(kwikUrl)
        var cfBypassResult = kwikResult.cfBypassResult
        var fContentCookies = kwikResult.cookies
        val fContentString = kwikResult.html
        val fContentUrl = kwikResult.url

        val (fullString, key, v1, v2) = kwikParamsRegex.find(fContentString)!!.destructured
        val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())
        val uri = kwikDUrl.find(decrypted)!!.destructured.component1()
        val tok = kwikDToken.find(decrypted)!!.destructured.component1()

        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        while (code != 302 && tries < 3) {
            val postHeaders = if (cfBypassResult != null) {
                Headers.headersOf(
                    "referer",
                    fContentUrl,
                    "cookie",
                    fContentCookies,
                    "User-Agent",
                    cfBypassResult.userAgent,
                )
            } else {
                Headers.headersOf(
                    "referer",
                    fContentUrl,
                    "cookie",
                    fContentCookies,
                )
            }

            noRedirectClient.newCall(
                POST(uri, postHeaders, FormBody.Builder().add("_token", tok).build()),
            ).await().use { content ->
                code = content.code
                kwikLocation = content.header("location")
            }
            ++tries

            if ((code == 403 || code == 419) && context != null) {
                cfBypassResult = CloudflareBypass(context).getCookies(kwikUrl)
                fContentCookies = "$fContentCookies; ${cfBypassResult.cookies}"
                tries = 0
            }
        }

        if (kwikLocation == null) throw Exception("Failed to extract the stream uri from kwik.")
        return kwikLocation!!
    }

    private suspend fun fetchKwikHtml(kwikUrl: String): KwikHtmlResult {
        val response = kwikClient.newCall(GET(kwikUrl, Headers.headersOf("referer", "https://kwik.cx/"))).await()
        val html = response.use { it.body.string() }
        val baseCookies = response.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

        if (html.contains("eval(function(") && !cfClearanceRegex.containsMatchIn(baseCookies)) {
            return KwikHtmlResult(baseCookies, html, response.request.url.toString())
        }

        if (context != null) {
            val cfResult = CloudflareBypass(context).getCookies(kwikUrl)

            val bypassHeaders = Headers.headersOf(
                "referer",
                "https://kwik.cx/",
                "cookie",
                cfResult.cookies,
                "User-Agent",
                cfResult.userAgent,
            )

            val bypassResponse = kwikClient.newCall(GET(kwikUrl, bypassHeaders)).await()
            val bypassHtml = bypassResponse.use { it.body.string() }
            val bypassCookies = bypassResponse.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

            if (bypassHtml.contains("eval(function(")) {
                return KwikHtmlResult(
                    "$bypassCookies; ${cfResult.cookies}",
                    bypassHtml,
                    bypassResponse.request.url.toString(),
                    cfResult,
                )
            }
        } else if (html.contains("eval(function(") && cfClearanceRegex.containsMatchIn(baseCookies)) {
            return KwikHtmlResult(baseCookies, html, response.request.url.toString())
        }

        throw Exception("Failed to fetch Kwik HTML. Blocked by Cloudflare.")
    }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1
            val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
            sb.append(decodedChar)
        }

        return sb.toString()
    }
}
