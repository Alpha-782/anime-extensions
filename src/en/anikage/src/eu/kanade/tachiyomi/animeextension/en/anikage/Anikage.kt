package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.associate
import kotlin.collections.isNotEmpty

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc"

    override val lang: String = "en"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    override val name: String = "Anikage"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnime(client.newCall(popularAnimeRequest(page)).awaitSuccess())

    override fun popularAnimeRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "popularity")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", "true")
        }

        return buildGet(requestUrl.build())
    }

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnime(client.newCall(latestUpdatesRequest(page)).awaitSuccess())

    override fun latestUpdatesRequest(page: Int): Request {
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("sort", "updated")
        requestUrl.addQueryParameter("limit", "25")
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val segments = url.pathSegments
            val slug = when {
                segments.size >= 3 &&
                    segments[0] == "anime" &&
                    segments[1] == "info" -> segments[2]
                else -> null
            } ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$slug", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            val details = getAnimeDetails(
                SAnime.create().apply { url = "/anime/info/$slug" },
            )
            return AnimesPage(listOf(details), false)
        }

        return parseAnime(client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess())
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = Filters.getSearchParameters(filters)
        val requestUrl = ANIKAGE_API_URL
            .newBuilder()
        requestUrl.addQueryParameter("page", page.toString())
        requestUrl.addQueryParameter("limit", "25")
        if (query != "") requestUrl.addQueryParameter("q", query)
        if (searchParams.sortBy.isNotEmpty()) {
            requestUrl.addQueryParameter("sort", searchParams.sortBy)
        }
        if (searchParams.status != "ALL") {
            requestUrl.addQueryParameter("status", searchParams.status)
        }
        if (searchParams.season != "ALL") {
            requestUrl.addQueryParameter("season", searchParams.season)
        }
        if (searchParams.origin != "ALL") {
            requestUrl.addQueryParameter("country", searchParams.origin)
        }
        if (searchParams.format != "ALL") {
            requestUrl.addQueryParameter("format", searchParams.format)
        }
        if (searchParams.releaseYear != "ALL") {
            requestUrl.addQueryParameter("yearMin", searchParams.releaseYear)
            requestUrl.addQueryParameter("yearMax", searchParams.releaseYear)
        }
        if (searchParams.genres.isNotEmpty()) {
            requestUrl.addQueryParameter("genres", searchParams.genres.joinToString(","))
        }
        if (searchParams.tags.isNotEmpty()) {
            requestUrl.addQueryParameter("tags", searchParams.tags.joinToString(","))
        }
        if (searchParams.source != "ALL") {
            requestUrl.addQueryParameter("source", searchParams.source)
        }
        if (searchParams.scoreMin != "ALL") {
            requestUrl.addQueryParameter("scoreMin", searchParams.scoreMin)
        }
        if (searchParams.episodes != "ALL") {
            val parts = searchParams.episodes.split("-")
            if (parts.isNotEmpty()) {
                requestUrl.addQueryParameter("epMin", parts[0])
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    requestUrl.addQueryParameter("epMax", parts[1])
                }
            }
        }
        when (searchParams.audio) {
            "sub" -> requestUrl.addQueryParameter("sub", "true")
            "dub" -> requestUrl.addQueryParameter("dub", "true")
        }
        if (preferences.isAdult) {
            requestUrl.addQueryParameter("adult", true.toString())
        }

        return buildGet(requestUrl.build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = animeDetailsParse(client.newCall(animeDetailsRequest(anime)).awaitSuccess())

    override fun animeDetailsParse(response: Response): SAnime {
        val info = response.parseAs<AnimeInfoResponse>().anime

        val titleName = if (preferences.titleStyle == "english") {
            info.title.english ?: info.title.romaji!!
        } else {
            info.title.romaji!!
        }
        val studioNames = info.studios
            .filter { it.isAnimationStudio }
            .ifEmpty { info.studios }
            .map { it.name }

        return SAnime.create().apply {
            title = titleName
            description = info.description?.cleanDescription()
            thumbnail_url = info.coverImage?.let { it.extraLarge ?: it.large ?: it.medium }
            author = studioNames.joinToString()
            update_strategy = if (info.status == "FINISHED") {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            status = when (info.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ========================== Related Anime =============================

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        return buildGet("$baseUrl/api/media/anime/$animeId".toHttpUrl())
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = relatedAnimeListParse(client.newCall(relatedAnimeListRequest(anime)).awaitSuccess())

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val info = response.parseAs<AnimeInfoResponse>().anime
        val currentSlug = info.slug

        fun RelatedAnimeTitle.preferred() = if (preferences.titleStyle == "english") {
            english ?: romaji
        } else {
            romaji ?: english
        }

        fun relatedStatus(status: String?) = when (status) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }

        return buildList {
            info.relations.mapNotNull { rel ->
                if (rel.slug == currentSlug) return@mapNotNull null
                val title = rel.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rel.slug}")
                    this.title = title
                    thumbnail_url = rel.coverImage
                    status = relatedStatus(rel.status)
                    genre = listOfNotNull(rel.format, rel.relationType).joinToString()
                }
            }.let(::addAll)

            info.recommendations.mapNotNull { rec ->
                if (rec.slug == currentSlug) return@mapNotNull null
                val title = rec.title.preferred() ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain("/anime/info/${rec.slug}")
                    this.title = title
                    thumbnail_url = rec.coverImage
                    status = relatedStatus(rec.status)
                    genre = rec.format
                }
            }.let(::addAll)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")
        val getHeaders = headersBuilder()
            .set("Referer", "$baseUrl${anime.url}")
            .set("Origin", baseUrl)
            .set("Accept", "*/*")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Dest", "empty")
            .build()

        return GET(animeId.animeEpisodeBuilder(), headers = getHeaders)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.removeSuffix("/").substringAfterLast("/")

        val episodesData = client.newCall(episodeListRequest(anime))
            .awaitSuccess()
            .parseAs<List<EpisodeResult>>()

        val episode = episodesData.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = buildString {
                    append("Episode ${it.number}")
                    if (!it.title.isNullOrBlank()) {
                        append(" - ${it.title}")
                    }
                    // Surface recap status in the title — there's no
                    // recapMark field on SEpisode, so this is the only
                    // way to flag it. (Filler is handled by fillermark
                    // below and shown via the player UI's own badge.)
                    if (it.isRecap) append(" (Recap)")
                }
                // Rich metadata from the API — previously dropped.
                summary = it.description
                preview_url = it.image
                // fillermark lets the player auto-skip filler episodes in
                // supported viewing modes; the UI shows its own badge
                // from this, no need to pollute the title.
                fillermark = it.isFiller
                date_upload = DATE_FORMAT.tryParse(it.airDate)
                setUrlWithoutDomain(
                    animeEpisodeUrlFormat(
                        animeId,
                        it.number,
                    ),
                )
            }
        }
        return episode
    }

    override fun getEpisodeUrl(episode: SEpisode): String = "$baseUrl${episode.url}"

    // =============================== Hosters =============================

    /**
     * Build the list of available hosters for this episode. Each Hoster is
     * a (audioType, provider) pair packaged as a self-contained URL — the
     * /sources endpoint with `?lang={type}&provider={provider}` query.
     *
     * The framework picks one (or all) hosters and feeds each to
     * [getVideoList] which actually extracts playable Video streams.
     *
     * Hosters are built from the /servers endpoint's response. Servers
     * carrying a `urls` field are embed-style (e.g. kage → vidcloud.se)
     * and are skipped — extracting them is out of scope.
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isDubPreferred = preferences.subOrDub == "dub"
        val primaryLabel = if (isDubPreferred) "Dub" else "Sub"
        val secondaryLabel = if (isDubPreferred) "Sub" else "Dub"
        val preferredSource = if (isDubPreferred) preferences.dubSource else preferences.subSource
        val otherSource = if (isDubPreferred) preferences.subSource else preferences.dubSource

        val primaryProviders = SUB_PROVIDER.sortedByDescending { it.contains(preferredSource) }
        val secondaryProviders = DUB_PROVIDER.sortedByDescending { it.contains(otherSource) }

        val servers = getEpisodeServers(episode)

        val providers = if (servers.isNotEmpty()) {
            // Use the API-returned server list directly so newly-added
            // providers automatically work without an extension update.
            // Skip servers that carry a `urls` field — those are embed-style
            // (e.g. kage → vidcloud.se) and require an HTML extractor we
            // don't ship.
            val streamable = servers.filter { it.urls == null }
            val availability = streamable.associate { it.providerId to it.subTypes }
            val apiProviders = streamable
                .map { it.providerId }
                .distinct()
                .sortedByDescending { it.contains(preferredSource) }

            // Group: all primary-label options first, then all secondary-label
            // (matches the original behavior so video sorting downstream
            // still works the same way).
            val primary = apiProviders.filter {
                availability[it].orEmpty().contains(primaryLabel.lowercase())
            }.map { primaryLabel to it }
            val secondary = apiProviders.filter {
                availability[it].orEmpty().contains(secondaryLabel.lowercase())
            }.map { secondaryLabel to it }
            primary + secondary
        } else {
            // Fallback when /servers endpoint fails entirely: use the
            // hardcoded list with no availability info (best-effort).
            primaryProviders.map(primaryLabel::to) + secondaryProviders.map(secondaryLabel::to)
        }

        val excludedServers = preferences.excludedServers
        return providers
            .filter { (_, provider) -> provider !in excludedServers }
            .map { (type, provider) ->
                Hoster(
                    hosterUrl = videoListRequestUrl(episode, type.lowercase(), provider),
                    hosterName = "$type - $provider",
                )
            }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val isDubPreferred = preferences.subOrDub == "dub"
        val preferredSource = if (isDubPreferred) preferences.dubSource else preferences.subSource
        val primaryType = if (isDubPreferred) "Dub" else "Sub"
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(preferredSource, ignoreCase = true) }
                .thenByDescending { it.hosterName.contains(primaryType, ignoreCase = true) },
        )
    }

    // ============================== Videos ===============================

    private fun videoListRequestUrl(episode: SEpisode, lang: String, provider: String): String = "$baseUrl${episode.url}?lang=$lang&provider=$provider"

    private fun forceMpegTs(video: Video): Video = video.copy(
        mpvArgs = video.mpvArgs.filterNot { it.first == "demuxer-lavf-o" } +
            ("demuxer-lavf-o" to "force_mpegts=1"),
        ffmpegStreamArgs = video.ffmpegStreamArgs.filterNot { it.first == "force_mpegts" } +
            ("force_mpegts" to "1"),
    )

    /**
     * Extract playable Videos from a single Hoster. The hosterName is
     * formatted as "{type} - {provider}" (e.g. "Sub - koto") so we can
     * recover the audio type and provider ID for proper labelling and
     * the neko-specific LocalProxy routing.
     */
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playlistUtils = PlaylistUtils(client, headers)
        val excludedTypes = preferences.excludedTypes

        // Recover the provider from the hoster name.
        val provider = hoster.hosterName.substringAfter(" - ")

        val episodeData = client.get(hoster.hosterUrl)
            .parseAs<EpisodeSource>()

        val tracks = episodeData.subtitles.map {
            Track("https://og.bakayaro.live/stream/${it.file}", it.label)
        }

        val vidHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("Sec-Fetch-Site", "cross-site")
            .set("Accept", "*/*")
            .build()

        val videos = episodeData.sources.parallelCatchingFlatMap { source ->
            val videoUrl = source.episodeSourceUrl()
            if (source.isM3U8 == true) {
                playlistUtils.extractFromHls(
                    playlistUrl = videoUrl,
                    masterHeaders = vidHeaders,
                    videoHeaders = vidHeaders,
                    videoNameGen = { "${source.quality} - $it" },
                    subtitleList = tracks,
                )
            } else {
                Video(
                    videoUrl = videoUrl,
                    videoTitle = source.quality,
                    subtitleTracks = tracks,
                    headers = vidHeaders,
                ).let(::listOf)
            }
        }.map(::forceMpegTs)

        return videos.filterNot { video ->
            val videoType = video.videoTitle.substringBefore(" - ")
            excludedTypes.any { videoType.equals(it, ignoreCase = true) }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val isDubPreferred = preferences.subOrDub == "dub"
        val quality = preferences.quality
        val primaryType = if (isDubPreferred) "Dub" else "Sub"
        val secondaryType = if (isDubPreferred) "Sub" else "Dub"
        val preferredServer = if (isDubPreferred) preferences.dubSource else preferences.subSource
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.videoTitle.contains(it) } }
                .thenByDescending { video ->
                    when {
                        video.videoTitle.contains(primaryType, ignoreCase = true) -> 2
                        video.videoTitle.contains(secondaryType, ignoreCase = true) -> 1
                        else -> 0
                    }
                }
                .thenByDescending { video ->
                    val videoServer = video.videoTitle.substringAfter(" - ").substringBefore(" - ")
                    if (videoServer.equals(preferredServer, ignoreCase = true)) 1 else 0
                },
        )
    }

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    private fun serversListUrl(episode: SEpisode): String = "$baseUrl${episode.url}".substringBefore("/sources") + "/servers"

    private suspend fun getEpisodeServers(episode: SEpisode): List<ServerInfo> = try {
        client.get(serversListUrl(episode))
            .parseAs<EpisodeServers>()
            .servers
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // ============================= Utilities ==============================

    private fun String.cleanDescription(): String = Jsoup.parse(replace(DESCRIPTION_BR_REGEX, "\n"))
        .wholeText()
        .trim()

    private fun String.animeEpisodeBuilder(): String = "$baseUrl/api/media/anime/$this/episodes"

    private fun animeEpisodeUrlFormat(id: String, number: Int): String = "$baseUrl/api/media/anime/$id/episodes/$number/sources"

    private fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<AnikageResponse>()

        val animes = jsonData.data.map {
            val id = it.slug
            val titleFormat = preferences.titleStyle
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji!!
            } else {
                it.title.romaji!!
            }
            val coverUrl = it.coverImage?.let { c -> c.extraLarge ?: c.large ?: c.medium }

            SAnime.create().apply {
                setUrlWithoutDomain("/anime/info/$id")
                thumbnail_url = coverUrl
                title = titleName
                description = null
                status = when (it.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                genre = it.genres.joinToString()
            }
        }

        return AnimesPage(animes, jsonData.hasNext)
    }

    private fun buildGet(url: HttpUrl): Request {
        val postHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            set("Host", ANIKAGE_API_URL.host)
            set("Origin", baseUrl)
            set("Referer", "$ANIKAGE_API/")
        }.build()

        return GET(url, headers = postHeaders)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SITE_TITLE_FORMAT,
            title = "Preferred Title Style",
            entries = listOf("English", "Romaji"),
            entryValues = listOf("english", "romaji"),
            default = PREF_SITE_TITLE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_SOURCE,
            title = "Preferred Sub Server",
            entries = SUB_PROVIDER,
            entryValues = SUB_PROVIDER,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DUB_SOURCE,
            title = "Preferred Dub Server",
            entries = DUB_PROVIDER,
            entryValues = DUB_PROVIDER,
            default = PREF_DUB_DEFAULT,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Choose which servers you want to exclude"
            entries = SUB_PROVIDER.toTypedArray()
            entryValues = SUB_PROVIDER.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addListPreference(
            key = PREF_ISSUBORDUB_SOURCE,
            title = "Sub or Dub?",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
            default = PREF_ISSUBORDUB_DEFAULT,
            summary = "%s",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_TYPES_KEY
            title = "Exclude Types"
            summary = "Choose which audio types you want to exclude"
            entries = listOf("Sub", "Dub").toTypedArray()
            entryValues = listOf("sub", "dub").toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Enable NSFW Content",
            summary = "Show adult content in search results and popular anime",
            default = PREF_ADULT_DEFAULT,
        )
    }

    private val SharedPreferences.titleStyle
        get() = getString(PREF_SITE_TITLE_FORMAT, PREF_SITE_TITLE_DEFAULT)!!

    private val SharedPreferences.isAdult
        get() = getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)!!

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.excludedServers
        get() = getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()).orEmpty()

    private val SharedPreferences.excludedTypes
        get() = getStringSet(PREF_EXCLUDE_TYPES_KEY, emptySet()).orEmpty()

    companion object {
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        private val DESCRIPTION_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        private const val ANIKAGE_API = "https://anikage.cc/api/media/anime/browse"
        private val ANIKAGE_API_URL by lazy { ANIKAGE_API.toHttpUrl() }
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false
        private const val PREFIX_SEARCH = "id:"

        private val provider = listOf(
            "koto",
            "kite",
            "ken",
            "megg",
            "wave",
            "zen",
        )
        private val SUB_PROVIDER = provider
        private val DUB_PROVIDER = provider

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private const val PREF_SUB_DEFAULT = "koto"

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private const val PREF_DUB_DEFAULT = "koto"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_EXCLUDE_SERVERS_KEY = "excluded_servers"
        private const val PREF_EXCLUDE_TYPES_KEY = "excluded_types"

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
