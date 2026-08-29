package eu.kanade.tachiyomi.animeextension.en.animeflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.modlist.ModList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNullBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeFlix :
    ModList(
        name = "AnimeFlix",
        defaultBaseUrl = "https://animeflix.dad",
        lang = "en",
        mmodlistType = "animeflix",
        hostKeyword = "animeflix",
    ) {
    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$currentBaseUrl/page/$page/", headers)

    override fun popularAnimeSelector(): String = "article.latestPost.excerpt"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val titleLink = element.selectFirst("h2.title a") ?: element.selectFirst("a[title]")
        setUrlWithoutDomain(titleLink?.attr("abs:href") ?: element.selectFirst("a")?.attr("abs:href") ?: "")
        title = titleLink?.attr("title")?.ifBlank { titleLink.text() }?.replace("Download", "")?.trim()
            ?: titleLink?.text()?.replace("Download", "")?.trim() ?: ""
        val img = element.selectFirst("img.wp-post-image") ?: element.selectFirst("img")
        thumbnail_url = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("abs:src")
            ?: img?.attr("abs:data-pagespeed-lazy-src")
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim().replace(" ", "+")
        return if (page == 1) {
            GET("$currentBaseUrl/?s=$cleanQuery", headers)
        } else {
            GET("$currentBaseUrl/page/$page/?s=$cleanQuery", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    // Reuse ModList's animeDetailsParse which works for imdbwp structure
    // but ensure title fallback works for animeflix's single-title
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        initialized = true
        title = document.selectFirst("h1.single-title")?.text()
            ?: document.selectFirst(".entry-title")?.text()
                ?.replace("Download", "", true)?.trim() ?: "Anime"
        status = SAnime.UNKNOWN
        author = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__footer > span")?.text()
            ?: document.selectFirst("div.imdbwp__footer span")?.text()
        description = document.selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__teaser")?.text()
            ?: document.selectFirst("div.imdbwp__teaser")?.text()
            ?: document.selectFirst("div.thecontent")?.text()?.take(500)
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val archiveLinks = doc.select("a[href*=episodes.animeflix.dad/archives/]")
        if (archiveLinks.isEmpty()) {
            throw Exception("No episode links found. Site may have changed or is behind Cloudflare.")
        }

        val qualityRegex = "\\d{3,4}p".toRegex(RegexOption.IGNORE_CASE)

        // Each archive link corresponds to a quality (720p / 1080p)
        // Quality is in preceding heading, e.g. <h3>Download ... 720p ...</h3> -> next p a
        val triples = archiveLinks.parallelMapNotNullBlocking { archiveElement ->
            runCatching {
                // Find quality by scanning previous siblings for quality string
                var prev = archiveElement.parent()?.previousElementSibling()
                var quality: String? = null
                var attempts = 0
                while (prev != null && attempts < 5) {
                    val text = prev.text()
                    quality = qualityRegex.find(text)?.value
                    if (quality != null) break
                    prev = prev.previousElementSibling()
                }
                // Fallback: check parent container's previous heading or document title
                if (quality == null) {
                    val heading = archiveElement.parents().firstOrNull { it.selectFirst("h3") != null }
                        ?.selectFirst("h3")?.text()
                    quality = heading?.let { qualityRegex.find(it)?.value }
                }
                val finalQuality = quality ?: "HD"

                val archiveUrl = archiveElement.attr("abs:href").takeUnless { it.isBlank() }
                    ?: return@parallelMapNotNullBlocking null

                val archiveDoc = runCatching {
                    client.newCall(GET(archiveUrl, headers)).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNullBlocking null

                // Episodes page contains links like <a href="https://episodes.animeflix.dad/getlink/...">Episode 67</a>
                val episodeLinks = archiveDoc.select("a[href*=/getlink/]")
                if (episodeLinks.isEmpty()) return@parallelMapNotNullBlocking null

                episodeLinks.mapIndexedNotNull { index, linkElement ->
                    val epText = linkElement.text().trim()
                    val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: (index + 1)
                    val url = linkElement.attr("abs:href").takeUnless { it.isBlank() }
                        ?: return@mapIndexedNotNull null
                    Triple(
                        epNum,
                        url,
                        finalQuality,
                    )
                }
            }.getOrNull()
        }.flatten()

        // Group by episode number, merging qualities as separate mirrors
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

        if (grouped.isEmpty()) throw Exception("Only Zip Pack Available or failed to load episode pages.")

        // Ensure sorted descending by episode number as per app convention (reversed)
        return grouped.sortedBy { it.episode_number }.reversed()
    }

    private fun resolveGetLink(url: String): String? {
        // getlink returns 302 to driveseed.org/r?key=...
        return runCatching {
            client.newBuilder().followRedirects(false).build()
                .newCall(GET(url, headers)).execute().use { resp ->
                    val loc = resp.headers["location"]
                    if (!loc.isNullOrBlank()) {
                        // Resolve relative if needed
                        resp.request.url.resolve(loc)?.toString() ?: loc
                    } else {
                        // Fallback parse body for driveseed link
                        val body = resp.body.string()
                        Regex("""https://driveseed\.org[^\s"'<>]+""").find(body)?.value
                    }
                }
        }.getOrNull()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)
        return urlJson.urls.parallelCatchingFlatMap { eplink ->
            val driveseedUrl = resolveGetLink(eplink.url) ?: return@parallelCatchingFlatMap emptyList()
            val mediaUrl = getMediaUrl(EpUrl(url = driveseedUrl, quality = eplink.quality))
                ?: return@parallelCatchingFlatMap emptyList()
            extractVideos(mediaUrl, eplink.quality)
        }
    }
}
