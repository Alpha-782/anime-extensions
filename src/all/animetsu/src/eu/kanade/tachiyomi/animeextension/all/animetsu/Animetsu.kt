package eu.kanade.tachiyomi.animeextension.all.animetsu

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class Animetsu :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animetsu"

    override val baseUrl = "https://animetsu.live"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val domain: String
        get() = preferences.getString(PREF_DOMAIN_KEY, baseUrl) ?: baseUrl

    private val apiUrl: String
        get() = "$domain/v2/api"

    private val proxyUrl = "https://mega-cloud.top/proxy"

    override val lang = "all"

    override val supportsLatest = true

    private val titleLanguage: String
        get() = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val audioType: String
        get() = preferences.getString(PREF_AUDIO_TYPE_KEY, PREF_AUDIO_TYPE_DEFAULT) ?: PREF_AUDIO_TYPE_DEFAULT

    /** Extract the raw anime ID from SAnime.url – handles "/anime/{id}" and plain "{id}" */
    private fun extractAnimeId(url: String): String = url.substringAfterLast("/")

    private fun apiHeaders(referer: String = "$domain/browse"): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0")
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    override val client = network.client.newBuilder()
        .rateLimit(5, 1, TimeUnit.SECONDS)
        .build()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/anime/search/?sort=popularity&page=$page&per_page=35", apiHeaders())

    override fun popularAnimeParse(response: Response): AnimesPage {
        val dto = response.parseAs<AnimetsuSearchDto>()
        val filteredResults = if (hideAdult) dto.results.filter { !it.isAdult } else dto.results
        val animes = filteredResults.map { it.toSAnime() }

        return AnimesPage(animes, dto.page < dto.last_page)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/anime/search/?sort=date_desc&page=$page&per_page=35", apiHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimetsuFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiUrl/anime/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "35")

            if (query.isNotBlank()) addQueryParameter("query", query)

            filters.filterIsInstance<AnimetsuFilters.SortFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("sort", it) }
            filters.filterIsInstance<AnimetsuFilters.FormatFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("format", it) }
            filters.filterIsInstance<AnimetsuFilters.StatusFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("status", it) }
            filters.filterIsInstance<AnimetsuFilters.SeasonFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("season", it) }
            filters.filterIsInstance<AnimetsuFilters.YearFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("year", it) }
            filters.filterIsInstance<AnimetsuFilters.CountryFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("country", it) }
            filters.filterIsInstance<AnimetsuFilters.SourceFilter>().firstOrNull()?.getValue()?.let { addQueryParameter("source", it) }

            filters.filterIsInstance<AnimetsuFilters.GenreFilter>().firstOrNull()?.getSelectedValues()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("genres", it) }
            filters.filterIsInstance<AnimetsuFilters.TagFilter>().firstOrNull()?.getSelectedValues()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("tags", it) }
        }

        return GET(urlBuilder.build(), apiHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    /**
     * Override to handle 502 from the details API gracefully.
     * Search results already contain all anime details (title, thumbnail, genre,
     * status, description), so falling back to the existing SAnime is safe.
     */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = try {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        animeDetailsParse(response)
    } catch (_: Exception) {
        anime // Fallback: return existing anime populated from search
    }

    override fun getAnimeUrl(anime: SAnime): String = "$domain/anime/${extractAnimeId(anime.url)}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/${extractAnimeId(anime.url)}", apiHeaders(getAnimeUrl(anime)))

    override fun animeDetailsParse(response: Response): SAnime = try {
        response.parseAs<AnimetsuAnimeDto>().toSAnime()
    } catch (e: Exception) {
        SAnime.create().apply {
            url = "/anime/${response.request.url.pathSegments.last()}"
        }
    }

    // ============================== Episodes ==============================

    /**
     * Override to handle 502 from episode/details API gracefully.
     * Tries multiple endpoints, then falls back to generating episodes
     * from totalEps stored in SAnime.artist during search.
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = extractAnimeId(anime.url)
        val referer = "$domain/anime/$animeId"

        // Attempt 1: /anime/eps/{id} endpoint (original)
        try {
            val response = client.newCall(
                GET("$apiUrl/anime/eps/$animeId", apiHeaders(referer)),
            ).awaitSuccess()
            val dtos = response.parseAs<List<AnimetsuEpisodeDto>>()
            if (dtos.isNotEmpty()) {
                return dtos.mapNotNull { dto ->
                    val epNum = dto.epNum ?: return@mapNotNull null
                    SEpisode.create().apply {
                        url = "$animeId/$epNum"
                        name = buildString {
                            append("Ep. $epNum")
                            if (!dto.name.isNullOrBlank()) append(" - ${dto.name}")
                            if (dto.isFiller == true) append(" (Filler)")
                        }
                        episode_number = epNum.toFloat()
                        date_upload = dto.airedAt?.toDate() ?: 0L
                    }
                }.reversed()
            }
        } catch (_: Exception) { }

        // Attempt 2: /anime/{id} details endpoint (may have episodes embedded)
        try {
            val response = client.newCall(
                GET("$apiUrl/anime/$animeId", apiHeaders(referer)),
            ).awaitSuccess()
            val dto = response.parseAs<AnimetsuAnimeDto>()
            if (!dto.episodes.isNullOrEmpty()) {
                return dto.episodes.mapNotNull { ep ->
                    val epNum = ep.epNum ?: return@mapNotNull null
                    SEpisode.create().apply {
                        url = "$animeId/$epNum"
                        name = buildString {
                            append("Ep. $epNum")
                            if (!ep.name.isNullOrBlank()) append(" - ${ep.name}")
                            if (ep.isFiller == true) append(" (Filler)")
                        }
                        episode_number = epNum.toFloat()
                        date_upload = ep.airedAt?.toDate() ?: 0L
                    }
                }.reversed()
            }
            // Use totalEps from details response
            val totalEps = dto.totalEps
            if (totalEps != null && totalEps > 0) {
                return generateEpisodes(animeId, totalEps)
            }
        } catch (_: Exception) { }

        // Attempt 3: Fallback from totalEps stored in SAnime.artist during search
        val totalEps = anime.artist?.toIntOrNull()
        if (totalEps != null && totalEps > 0) {
            return generateEpisodes(animeId, totalEps)
        }

        return emptyList()
    }

    private fun generateEpisodes(animeId: String, totalEps: Int): List<SEpisode> = (1..totalEps).map { epNum ->
        SEpisode.create().apply {
            url = "$animeId/$epNum"
            name = "Ep. $epNum"
            episode_number = epNum.toFloat()
        }
    }.reversed()

    override fun episodeListRequest(anime: SAnime): Request = GET("$apiUrl/anime/eps/${extractAnimeId(anime.url)}", apiHeaders("$domain/anime/${extractAnimeId(anime.url)}"))

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.request.url.pathSegments.last()
        val dtos = response.parseAs<List<AnimetsuEpisodeDto>>()

        return dtos.mapNotNull { dto ->
            val epNum = dto.epNum ?: return@mapNotNull null
            SEpisode.create().apply {
                url = "$animeId/$epNum"
                name = buildString {
                    append("Ep. $epNum")
                    if (!dto.name.isNullOrBlank()) append(" - ${dto.name}")
                    if (dto.isFiller == true) append(" (Filler)")
                }
                episode_number = epNum.toFloat()
                date_upload = dto.airedAt?.toDate() ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/")
        val animeId = parts[0]
        val epNum = parts[1]
        val watchReferer = "$domain/watch/$animeId"
        val sourceType = audioType

        val serverResponse = client.newCall(
            GET("$apiUrl/anime/servers/$animeId/$epNum", apiHeaders(watchReferer)),
        ).awaitSuccess()

        val allServers = serverResponse.parseAs<List<AnimetsuServerDto>>()

        // Filter servers based on preference; fall back to all if preferred is unavailable
        val servers = if (preferredServer != PREF_SERVER_DEFAULT) {
            allServers.filter { it.id == preferredServer }
                .takeIf { it.isNotEmpty() }
                ?: allServers
        } else {
            allServers
        }

        val playlistUtils = PlaylistUtils(client, apiHeaders(watchReferer))
        val audioLabel = sourceType.uppercase()

        return coroutineScope {
            servers.map { server ->
                async {
                    try {
                        val sourceResponse = client.newCall(
                            GET("$apiUrl/anime/oppai/$animeId/$epNum?server=${server.id}&source_type=$sourceType", apiHeaders(watchReferer)),
                        ).awaitSuccess()

                        val dto = sourceResponse.parseAs<AnimetsuVideoDto>()
                        val subtitleTracks = dto.subs?.mapNotNull { sub ->
                            Track(sub.url, sub.lang ?: "Unknown")
                        } ?: emptyList()

                        val serverVideos = mutableListOf<Video>()

                        for (source in dto.sources) {
                            val fullUrl = when {
                                source.needProxy -> "$proxyUrl${source.url}"
                                source.url.startsWith("http") -> source.url
                                else -> "$domain${source.url}"
                            }

                            when {
                                source.type?.contains("mp4") == true -> {
                                    serverVideos.add(
                                        Video(
                                            fullUrl,
                                            "${server.id.uppercase()}: ${source.quality} ($audioLabel)",
                                            fullUrl,
                                            apiHeaders(watchReferer),
                                            subtitleTracks,
                                        ),
                                    )
                                }
                                source.type?.contains("mpegurl") == true -> {
                                    serverVideos.addAll(
                                        playlistUtils.extractFromHls(
                                            playlistUrl = fullUrl,
                                            videoNameGen = { quality -> "${server.id.uppercase()}: $quality ($audioLabel)" },
                                            referer = "$domain/",
                                            subtitleList = subtitleTracks,
                                        ),
                                    )
                                }
                            }
                        }
                        serverVideos
                    } catch (_: Exception) {
                        emptyList<Video>()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = baseUrl,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_ENTRIES,
            entryValues = PREF_TITLE_LANG_VALUES,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_AUDIO_TYPE_KEY,
            title = "Preferred audio type",
            entries = PREF_AUDIO_TYPE_ENTRIES,
            entryValues = PREF_AUDIO_TYPE_VALUES,
            default = PREF_AUDIO_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addSwitchPreference(
            key = PREF_HIDE_ADULT_KEY,
            title = "Hide Adult Content",
            summary = "Hides 18+ content from browse, search, and latest updates.",
            default = PREF_HIDE_ADULT_DEFAULT,
        )
    }

    // ============================= Utilities ==============================
    private fun parseStatus(status: String?): Int = when (status) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "NOT_YET_RELEASED" -> SAnime.UNKNOWN
        else -> SAnime.UNKNOWN
    }

    private fun AnimetsuAnimeDto.toSAnime() = SAnime.create().apply {
        val dto = this@toSAnime
        // Store as /anime/{id} so baseUrl + url = correct web page for WebView
        url = "/anime/${dto.id}"
        title = when (titleLanguage) {
            "english" -> dto.title?.english
            "native" -> dto.title?.native
            else -> dto.title?.romaji
        }?.takeIf { it.isNotBlank() }
            ?: dto.title?.romaji
            ?: dto.title?.english
            ?: "Unknown Title"

        thumbnail_url = dto.coverImage?.large ?: dto.coverImage?.medium
        genre = dto.genres?.joinToString(", ") ?: ""
        status = parseStatus(dto.status)
        description = dto.description?.replace("<br>", "\n")?.replace("<br/>", "\n") ?: ""
        // Store totalEps in artist field for fallback episode generation
        // (used when both /anime/eps/ and /anime/{id} endpoints return 502)
        artist = dto.totalEps?.toString() ?: ""
    }

    private fun String.toDate(): Long = try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(this)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("animetsu.live", "animetsu.bz", "animetsu.cc")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
        private val PREF_TITLE_LANG_ENTRIES = listOf("Romaji", "English", "Japanese (Native)")
        private val PREF_TITLE_LANG_VALUES = listOf("romaji", "english", "native")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "all"
        private val PREF_SERVER_ENTRIES = listOf("All", "Pahe", "Kite", "Zoro", "Meg")
        private val PREF_SERVER_VALUES = listOf("all", "pahe", "kite", "zoro", "meg")

        private const val PREF_AUDIO_TYPE_KEY = "preferred_audio_type"
        private const val PREF_AUDIO_TYPE_DEFAULT = "sub"
        private val PREF_AUDIO_TYPE_ENTRIES = listOf("Sub", "Dub")
        private val PREF_AUDIO_TYPE_VALUES = listOf("sub", "dub")

        private const val PREF_HIDE_ADULT_KEY = "hide_adult_content"
        private const val PREF_HIDE_ADULT_DEFAULT = true
    }
}
