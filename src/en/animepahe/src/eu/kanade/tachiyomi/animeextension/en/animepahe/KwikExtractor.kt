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
    headers: Headers,
    private val context: Application? = null,
) {
    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

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

        var cfBypassResult: CfBypassResult? = null

        var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(kwikUrl)
        if (cfBypassResult != null) {
            fContentCookies = "$fContentCookies; ${cfBypassResult.cookies}"
        }

        val (fullString, key, v1, v2) = kwikParamsRegex.find(fContentString)!!.destructured
        val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())
        val uri = kwikDUrl.find(decrypted)!!.destructured.component1()
        val tok = kwikDToken.find(decrypted)!!.destructured.component1()

        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        //  Cookie grabber from Kwik URL to use MP4 videos
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

            if ((code == 403 || code == 419) && cfBypassResult == null && context != null) {
                cfBypassResult = CloudflareBypass(context).getCookies(kwikUrl)
                fContentCookies = "$fContentCookies; ${cfBypassResult.cookies}"
                tries = 0
            }
        }

        if (kwikLocation == null) throw Exception("Failed to extract the stream uri from kwik.")
        return kwikLocation!!
    }

    // This redirects to kwik URL to pass Cloudflare verification
    private suspend fun fetchKwikHtml(kwikUrl: String): Triple<String, String, String> {
        val response = kwikClient.newCall(GET(kwikUrl, Headers.headersOf("referer", "https://kwik.cx/"))).await()
        val html = response.use { it.body.string() }
        val baseCookies = response.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

        if (html.contains("eval(function(")) {
            return Triple(baseCookies, html, response.request.url.toString())
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
                return Triple("$bypassCookies; ${cfResult.cookies}", bypassHtml, bypassResponse.request.url.toString())
            }
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
