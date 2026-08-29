package eu.kanade.tachiyomi.multisrc.modlist

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.atomic.AtomicBoolean

abstract class ModList(
    override val name: String,
    private val defaultBaseUrl: String,
    override val lang: String,
    private val mmodlistType: String,
    private val hostKeyword: String,
) : AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, defaultBaseUrl)!!
    }

    @Volatile
    private var resolvedBaseUrl: String? = null

    protected val currentBaseUrl: String
        get() {
            resolvedBaseUrl?.let { return it }
            return runCatching {
                runBlocking {
                    withContext(Dispatchers.Default) {
                        resolveBaseUrl()
                    }
                }
            }.getOrDefault(baseUrl)
        }

    private suspend fun resolveBaseUrl(): String {
        val resolvedFromBase = runCatching {
            client.newCall(GET("$baseUrl/")).await().use { resp ->
                if (resp.isSuccessful) {
                    val origin = "${resp.request.url.scheme}://${resp.request.url.host}"
                    if (origin != baseUrl) {
                        preferences.edit().putString(PREF_DOMAIN_KEY, origin).apply()
                    }
                    origin
                } else {
                    null
                }
            }
        }.getOrNull()

        if (resolvedFromBase != null) {
            resolvedBaseUrl = resolvedFromBase
            return resolvedFromBase
        }

        val latest = runCatching {
            client.newCall(GET(mmodlistUrl, headers)).await().use { resp ->
                val body = resp.body.string()
                val specificRegex = Regex("""https://${Regex.escape(hostKeyword)}\.[a-z0-9-]+(?:\.[a-z]{2,})?(?=["'\s<>])""")
                specificRegex.find(body)?.value?.trimEnd('/', '.')
            }
        }.getOrNull()

        return latest?.let {
            preferences.edit().putString(PREF_DOMAIN_KEY, it).apply()
            resolvedBaseUrl = it
            it
        } ?: baseUrl
    }

    private val mmodlistUrl: String
        get() = "https://mmodlist.org/?type=$mmodlistType"

    override val supportsLatest = false

    protected val json: Json by injectLazy()

    protected val preferences by getPreferencesLazy()

    protected val playlistUtils by lazy { PlaylistUtils(client, headers) }
    protected val redirectBypasser by lazy { RedirectorBypasser(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$currentBaseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    protected open fun popularAnimeSelector(): String = "div#content_box div.post-cards > article"

    protected open fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        val img = element.selectFirst("div.featured-thumbnail > img")
        thumbnail_url = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("abs:src")
        title = element.select("a").attr("title")
            .replace("Download", "").trim()
    }

    protected open fun popularAnimeNextPageSelector(): String = "#content_box > nav > div > a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$currentBaseUrl/search/$cleanQuery/page/$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    protected open fun searchAnimeSelector(): String = popularAnimeSelector()

    protected open fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    protected open fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(currentBaseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.asJsoup())

    protected open fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        initialized = true
        title = document.selectFirst(".entry-title")?.text()
            ?.replace("Download", "", true)?.trim() ?: "Movie"
        status = SAnime.UNKNOWN
        val (authorText, descText) = document.parseImdbwp()
        author = authorText
        description = descText
    }

    protected fun Document.parseImdbwp(): Pair<String?, String?> {
        val author = selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__footer > span")?.text()
            ?: selectFirst("div.imdbwp__footer span")?.text()
        val description = selectFirst("div.entry-content > div.thecontent > div.imdbwp > div.imdbwp__content > div.imdbwp__teaser")?.text()
            ?: selectFirst("div.imdbwp__teaser")?.text()
            ?: selectFirst("div.thecontent")?.text()?.take(500)
        return Pair(author, description)
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET(currentBaseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodeElements = doc.select("p:has(a.maxbutton-episode-links,a.maxbutton-download-links)")
            .ifEmpty { doc.select("p:has(a[class*=maxbutton])") }
            .asSequence()

        if (!episodeElements.iterator().hasNext()) {
            throw Exception("No episode links found. Site may have changed or is behind Cloudflare.")
        }

        val isSerie = episodeElements.firstOrNull()?.selectFirst("a")?.text()?.equals("Episode Links", ignoreCase = true) == true

        val childPageLoaded = AtomicBoolean(false)
        val triples = episodeElements.toList().parallelMapNotNullBlocking { row ->
            runCatching {
                val prevP = row.previousElementSiblings()
                    .firstOrNull { it.text().isNotBlank() }?.text().orEmpty()

                val quality = QUALITY_REGEX.find(prevP)?.value ?: "HD"
                val defaultName = if (isSerie) {
                    SEASON_REGEX.find(prevP)?.value ?: "Season 1"
                } else {
                    MOVIE_TITLE_REGEX.find(prevP.replace("Download", "").trim())?.value?.trim() ?: "Movie"
                }

                val episodePageUrl = row.selectFirst("a[href]")?.attr("abs:href")?.takeUnless { it.isBlank() }
                    ?: return@parallelMapNotNullBlocking null

                val childUrl = extractChildUrl(episodePageUrl)

                val episodePageDocument = runCatching {
                    client.newCall(GET(childUrl, headers)).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNullBlocking null
                childPageLoaded.set(true)

                val links = episodePageDocument.select("div.timed-content-client_show_0_5_0 a")
                    .ifEmpty {
                        episodePageDocument.select("""a[href*="?sid="], a[href*="r?key="]""")
                    }

                links.mapIndexedNotNull { index, linkElement ->
                    val episode = if (isSerie) {
                        linkElement.text()
                            .replace("Episode", "", true)
                            .trim()
                            .toIntOrNull() ?: (index + 1)
                    } else {
                        0
                    }

                    val url = linkElement.attr("abs:href").takeUnless(String::isBlank)
                        ?: return@mapIndexedNotNull null

                    Triple(
                        Pair(defaultName, episode),
                        url,
                        if (isSerie) quality else "$quality ${linkElement.text()}".trim(),
                    )
                }
            }.getOrNull()
        }.flatten()

        val grouped = triples.groupBy { it.first }.values.mapIndexed { index, items ->
            val (itemName, episodeNum) = items.first().first

            SEpisode.create().apply {
                url = EpLinks(
                    urls = items.map { triple ->
                        EpUrl(url = triple.second, quality = triple.third)
                    },
                ).toJson()

                name = if (isSerie) "$itemName Ep $episodeNum" else itemName

                episode_number = if (isSerie) episodeNum.toFloat() else (index + 1).toFloat()
            }
        }

        if (grouped.isEmpty()) {
            throw Exception(
                if (childPageLoaded.get()) {
                    "Only Zip Pack Available"
                } else {
                    "Failed to load episode pages. Site may have changed or is behind Cloudflare."
                },
            )
        }
        return grouped.reversed()
    }

    protected fun extractChildUrl(mainUrl: String): String {
        return runCatching {
            val urlParam = mainUrl.toHttpUrl().queryParameter("url") ?: return mainUrl
            val decoded = runCatching { Base64.decode(urlParam, Base64.URL_SAFE) }
                .getOrElse { Base64.decode(urlParam, Base64.DEFAULT) }
            String(decoded)
        }.getOrDefault(mainUrl)
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)

        return urlJson.urls.parallelCatchingFlatMap { eplink ->
            val resolvedUrl = resolveEpUrl(eplink.url) ?: return@parallelCatchingFlatMap emptyList()
            val mediaUrl = getMediaUrl(EpUrl(url = resolvedUrl, quality = eplink.quality)) ?: return@parallelCatchingFlatMap emptyList()
            extractVideos(mediaUrl, eplink.quality)
        }
    }

    protected open suspend fun resolveEpUrl(url: String): String? = url

    protected suspend fun extractVideos(fileUrl: String, quality: String): List<Video> {
        val doc = runCatching { client.newCall(GET(fileUrl, headers)).await().asJsoup() }.getOrNull()
            ?: return emptyList()

        val btns = doc.select("div.card-body a.btn")
        if (btns.isEmpty()) return emptyList()

        return btns.flatMap { btn ->
            val href = btn.attr("abs:href").takeUnless { it.isBlank() } ?: return@flatMap emptyList()
            val size = SIZE_REGEX.find(btn.text())?.groupValues?.get(1)?.let { " - $it" } ?: ""

            when {
                href.contains("cdn.video-gen.xyz") || href.contains("video-seed.dev") ||
                    href.contains("r2.dev") || href.contains("instant.video-gen") -> {
                    val finalUrl = runCatching {
                        val headRequest = GET(href, headers).newBuilder().head().build()
                        client.newCall(headRequest).await().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            resp.request.url.queryParameter("url") ?: resp.request.url.toString()
                        }
                    }.getOrNull() ?: href

                    if (finalUrl.contains(".m3u8")) {
                        runCatching {
                            playlistUtils.extractFromHls(
                                finalUrl,
                                videoNameGen = { q -> "$quality - $q$size" },
                            )
                        }.getOrDefault(listOf(Video(finalUrl, "$quality - HLS$size", finalUrl)))
                    } else {
                        listOf(Video(finalUrl, "$quality - Instant$size", finalUrl))
                    }
                }
                href.contains(".m3u8") -> {
                    runCatching {
                        playlistUtils.extractFromHls(
                            href,
                            videoNameGen = { q -> "$quality - $q$size" },
                        )
                    }.getOrDefault(emptyList())
                }
                href.contains("/login") -> emptyList()
                else -> {
                    listOf(Video(href, "$quality - Direct$size", href))
                }
            }
        }
    }

    // ============================= Utilities ==============================
    protected suspend fun getMediaUrl(epUrl: EpUrl): String? {
        val url = epUrl.url
        val mediaResponse = if (url.contains("?sid=")) {
            val finalUrl = redirectBypasser.bypass(url) ?: return null
            client.newCall(GET(finalUrl, headers)).await()
        } else if (url.contains("r?key=")) {
            client.newCall(GET(url, headers)).await()
        } else {
            return null
        }

        return mediaResponse.use { resp ->
            val body = resp.body.string()
            val path = PATH_REGEX.find(body)?.groupValues?.get(1)
            if (path == "/404" || path == null) return null
            resp.request.url.resolve(path)?.toString()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val ascSort = preferences.getString(PREF_SIZE_SORT_KEY, PREF_SIZE_SORT_DEFAULT)!! == "asc"

        val comparator = compareByDescending<Video> { it.quality.startsWith(quality) }.let { cmp ->
            if (ascSort) {
                cmp.thenBy { it.quality.fixQuality() }
            } else {
                cmp.thenByDescending { it.quality.fixQuality() }
            }
        }
        return sortedWith(comparator)
    }

    private fun String.fixQuality(): Float {
        val size = substringAfterLast("-").trim()
        return if (size.contains("GB", true)) {
            size.replace("GB", "", true)
                .toFloatOrNull()?.let { it * 1000 } ?: 1F
        } else {
            size.replace("MB", "", true)
                .toFloatOrNull() ?: 1F
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SIZE_SORT_KEY
            title = PREF_SIZE_SORT_TITLE
            entries = PREF_SIZE_SORT_ENTRIES
            entryValues = PREF_SIZE_SORT_VALUES
            setDefaultValue(PREF_SIZE_SORT_DEFAULT)
            summary = PREF_SIZE_SORT_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            dialogTitle = PREF_DOMAIN_DIALOG_TITLE
            setDefaultValue(defaultBaseUrl)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).ifEmpty { defaultBaseUrl }
                    preferences.edit().putString(key, value).apply().also {
                        summary = getDomainPrefSummary()
                    }
                    true
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    @Serializable
    data class EpLinks(
        val urls: List<EpUrl>,
    )

    @Serializable
    data class EpUrl(
        val quality: String,
        val url: String,
    )

    protected fun EpLinks.toJson(): String = json.encodeToString(this)

    private fun getDomainPrefSummary(): String {
        val current = preferences.getString(PREF_DOMAIN_KEY, defaultBaseUrl)!!
        return "$current\nFor any change to be applied App restart is required."
    }

    companion object {
        private val SIZE_REGEX = "\\[([^]]+)]".toRegex(RegexOption.IGNORE_CASE)
        private val PATH_REGEX = Regex("""["'](/[^"']*)["']""")
        private val QUALITY_REGEX = "\\d{3,4}p(?:\\s+\\w+)?".toRegex(RegexOption.IGNORE_CASE)
        private val SEASON_REGEX = "[ .]?S(?:eason)?[ .]?(\\d{1,2})[ .]?".toRegex(RegexOption.IGNORE_CASE)
        private val MOVIE_TITLE_REGEX = "^[^(]+".toRegex(RegexOption.IGNORE_CASE)

        private const val PREF_DOMAIN_KEY = "pref_domain_new"
        private const val PREF_DOMAIN_TITLE = "Currently used domain"
        private const val PREF_DOMAIN_DIALOG_TITLE = PREF_DOMAIN_TITLE

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p")

        private const val PREF_SIZE_SORT_KEY = "preferred_size_sort"
        private const val PREF_SIZE_SORT_TITLE = "Preferred Size Sort"
        private const val PREF_SIZE_SORT_DEFAULT = "asc"
        private const val PREF_SIZE_SORT_SUMMARY = "%s\nSort order to be used after the videos are sorted by their quality."
        private val PREF_SIZE_SORT_ENTRIES = arrayOf("Ascending", "Descending")
        private val PREF_SIZE_SORT_VALUES = arrayOf("asc", "desc")
    }
}
