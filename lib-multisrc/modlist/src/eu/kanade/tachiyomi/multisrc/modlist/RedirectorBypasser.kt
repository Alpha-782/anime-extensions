package eu.kanade.tachiyomi.multisrc.modlist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

class RedirectorBypasser(private val client: OkHttpClient, private val headers: Headers) {
    suspend fun bypass(url: String): String? {
        val lastDoc = client.newCall(GET(url, headers)).await()
            .let { recursiveDoc(it.asJsoup()) }

        val script = lastDoc.selectFirst("script:containsData(/?go=):containsData(href)")
            ?.data()
            ?: return null

        val nextUrl = script.substringAfter("\"href\",\"").substringBefore('"')
        val httpUrl = nextUrl.toHttpUrlOrNull() ?: return null
        val cookieName = httpUrl.queryParameter("go") ?: return null
        val cookieValue = script.substringAfter("'$cookieName', '").substringBefore("'")
        val cookie = Cookie.parse(httpUrl, "$cookieName=$cookieValue")!!
        val headers = headers.newBuilder().set("referer", lastDoc.location()).build()

        return getHostMutex(httpUrl).withLock {
            // Mutex to prevent overwriting cookies from parallel requests for the same host
            client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
            client.newCall(GET(nextUrl, headers)).await().asJsoup()
                .selectFirst("meta[http-equiv]")?.attr("content")
                ?.substringAfter("url=")
        }
    }

    private suspend fun recursiveDoc(doc: Document): Document {
        val form = doc.selectFirst("form#landing") ?: return doc
        val url = form.attr("action")
        val body = FormBody.Builder().apply {
            form.select("input").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        val headers = headers.newBuilder()
            .set("referer", doc.location())
            .build()

        val response = client.newCall(POST(url, headers, body)).await()
        return recursiveDoc(response.asJsoup())
    }

    companion object {
        private val hostMutexes = ConcurrentHashMap<String, Mutex>()

        private fun getHostMutex(url: HttpUrl): Mutex = hostMutexes.getOrPut(url.host) { Mutex() }
    }
}
