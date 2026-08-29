package eu.kanade.tachiyomi.animeextension.en.animeflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.modlist.ModList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelMapNotNullBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

class AnimeFlix :
    ModList(
        name = "AnimeFlix",
        defaultBaseUrl = "https://animeflix.dad",
        lang = "en",
        mmodlistType = "animeflix",
        hostKeyword = "animeflix",
    ) {

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // Add a Referer header to all requests to bypass anti-hotlinking for thumbnails
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$currentBaseUrl/page/$page/", headers)

    override fun popularAnimeSelector(): String = "article.latestPost.excerpt"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleLink = element.selectFirst("h2.title a") ?: element.selectFirst("a[title]") ?: element.selectFirst("a")
        setUrlWithoutDomain(titleLink?.attr("abs:href") ?: "")
        title = titleLink?.attr("title")?.ifBlank { titleLink.text() }?.replace("Download", "")?.trim()
            ?: titleLink?.text()?.replace("Download", "")?.trim() ?: ""

        val img = element.selectFirst("div.featured-thumbnail img") ?: element.selectFirst("img")

        thumbnail_url = img?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: img?.attr("abs:data-src")
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+")
        return if (page == 1) {
            GET("$currentBaseUrl/?s=$cleanQuery", headers)
        } else {
            GET("$currentBaseUrl/page/$page/?s=$cleanQuery", headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        initialized = true
        title = (
            document.selectFirst("h1.single-title")?.text()
                ?: document.selectFirst(".entry-title")?.text()
                ?: "Anime"
            ).replace("Download", "", true).trim()
        status = SAnime.UNKNOWN
        val (authorText, descText) = document.parseImdbwp()
        author = authorText
        description = descText
        // Also try to extract thumbnail from details page
        val img = document.selectFirst("div.featured-thumbnail img")
            ?: document.selectFirst("img.wp-post-image")
            ?: document.selectFirst("img.attachment-full")
        thumbnail_url = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val archiveLinks = doc.select("a[href*=episodes.animeflix.dad/archives/]")
        if (archiveLinks.isEmpty()) {
            throw Exception("No episode links found. Site may have changed or is behind Cloudflare.")
        }

        val childPageLoaded = AtomicBoolean(false)

        val triples = archiveLinks.parallelMapNotNullBlocking { archiveElement ->
            runCatching {
                var prev = archiveElement.parent()?.previousElementSibling()
                var quality: String? = null
                var attempts = 0
                while (prev != null && attempts < 5) {
                    val text = prev.text()
                    quality = QUALITY_REGEX.find(text)?.value
                    if (quality != null) break
                    prev = prev.previousElementSibling()
                    attempts++
                }
                if (quality == null) {
                    val heading = archiveElement.parents().firstOrNull { it.selectFirst("h3") != null }
                        ?.selectFirst("h3")?.text()
                    quality = heading?.let { QUALITY_REGEX.find(it)?.value }
                }
                val finalQuality = quality ?: "HD"

                val archiveUrl = archiveElement.attr("abs:href").takeUnless { it.isBlank() }
                    ?: return@parallelMapNotNullBlocking null

                val archiveDoc = runCatching {
                    client.newCall(GET(archiveUrl, headers)).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNullBlocking null
                childPageLoaded.set(true)

                val episodeLinks = archiveDoc.select("a[href*=/getlink/]")
                if (episodeLinks.isEmpty()) return@parallelMapNotNullBlocking null

                episodeLinks.mapIndexedNotNull { index, linkElement ->
                    val epText = linkElement.text()
                    val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: (index + 1)
                    val url = linkElement.attr("abs:href").takeUnless { it.isBlank() }
                        ?: return@mapIndexedNotNull null
                    Triple(epNum, url, finalQuality)
                }
            }.getOrNull()
        }.flatten()

        val grouped = triples.groupBy { it.first }.values.map { items ->
            val epNum = items.first().first
            SEpisode.create().apply {
                url = EpLinks(
                    urls = items.map { triple -> EpUrl(url = triple.second, quality = triple.third) },
                ).toJson()
                name = "Episode $epNum"
                episode_number = epNum.toFloat()
            }
        }

        if (grouped.isEmpty()) {
            throw Exception(
                if (childPageLoaded.get()) {
                    "Only Zip Pack Available or no episodes found in archives."
                } else {
                    "Failed to load episode pages. Site may have changed or is behind Cloudflare."
                },
            )
        }

        return grouped.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun resolveEpUrl(url: String): String? = resolveGetLink(url)

    private suspend fun resolveGetLink(url: String): String? = runCatching {
        noRedirectClient.newCall(GET(url, headers)).await().use { resp ->
            val loc = resp.headers["location"]
            if (!loc.isNullOrBlank()) {
                resp.request.url.resolve(loc)?.toString() ?: loc
            } else {
                val body = resp.body.string()
                SEED_REGEX.find(body)?.value
            }
        }
    }.getOrNull()

    companion object {
        private val SEED_REGEX = Regex("""https://driveseed\.org[^\s"'<>]+""")
        private val QUALITY_REGEX = "\\d{3,4}p".toRegex(RegexOption.IGNORE_CASE)
    }
}
