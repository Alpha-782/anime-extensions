package eu.kanade.tachiyomi.animeextension.all.anipm

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.nanohttpd.protocols.http.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AniPM(private val config: LangConfig) :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name: String get() = "AniPM"

    override val lang: String get() = config.lang

    override val baseUrl: String = "https://ani.pm"

    private val dubLang: String? get() = config.dubLang

    override val supportsLatest = true

    // Status: https://anipm.tv/
    private val apiUrl get() = "$baseUrl/api"

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val hideAdult: Boolean
        get() = preferences.getBoolean(PREF_HIDE_ADULT_KEY, PREF_HIDE_ADULT_DEFAULT)

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val excludedAudioTypes: Set<String>
        get() = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, PREF_AUDIO_EXCLUDE_DEFAULT)
            ?: PREF_AUDIO_EXCLUDE_DEFAULT

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =============================== Browse ===============================
    private fun apiHeaders(referer: String = "$baseUrl/") = headers.newBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", referer)
        .build()

    private fun catalogUrl(page: Int) = "$apiUrl/anime/catalog".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .apply {
            addQueryParameter("beta", "1")
            if (hideAdult) addQueryParameter("sfw", "1")
            dubLang?.let { addQueryParameter("dublang", it) }
        }

    override fun popularAnimeRequest(page: Int): Request = GET(catalogUrl(page).addQueryParameter("sort", "popular").build(), apiHeaders())

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page).addQueryParameter("sort", "newest").build(), apiHeaders())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = catalogUrl(page).addQueryParameter("q", query.trim())

        filters.forEach { f ->
            when (f) {
                is Filters.SortFilter -> f.getValue().let { url.addQueryParameter("sort", it) }
                is Filters.VersionFilter -> {
                    f.getIncludedVersions().forEach { url.addQueryParameter("ver", it) }
                    f.getExcludedVersions().forEach { url.addQueryParameter("excludeVer", it) }
                }
                is Filters.ServerFilter -> f.getValue()?.let { url.addQueryParameter("server", it) }
                is Filters.EpisodeRangeFilter -> f.getValue()?.let { url.addQueryParameter("eps", it) }
                is Filters.OrderFilter -> f.getValue()?.let { url.addQueryParameter("order", it) }
                is Filters.FormatFilter -> f.getValue()?.let { url.addQueryParameter("format", it) }
                is Filters.StatusFilter -> f.getValue()?.let { url.addQueryParameter("status", it) }
                is Filters.SeasonFilter -> f.getValue()?.let { url.addQueryParameter("season", it) }
                is Filters.YearFilter -> f.getValue()?.let { url.addQueryParameter("year", it) }

                is Filters.GenreFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("genre", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeGenre", it) }
                }
                is Filters.TagFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("tag", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeTag", it) }
                }
                is Filters.StudioFilter -> {
                    f.getIncluded().forEach { url.addQueryParameter("studio", it) }
                    f.getExcluded().forEach { url.addQueryParameter("excludeStudio", it) }
                }
                else -> {}
            }
        }

        return GET(url.build(), apiHeaders())
    }

    override fun popularAnimeParse(response: Response) = parseCatalog(response)
    override fun latestUpdatesParse(response: Response) = parseCatalog(response)
    override fun searchAnimeParse(response: Response) = parseCatalog(response)

    private fun parseCatalog(response: Response): AnimesPage {
        val dto = response.parseAs<CatalogResponseDto>()

        val animes = dto.items.mapNotNull { item ->
            val anime = item.toSAnime(baseUrl)
            if (anime != null && !item.routeId.isNullOrBlank()) {
                cacheRouteId(anime.url, item.routeId)
            }
            anime
        }
        return AnimesPage(animes, dto.hasNextPage)
    }

    // =============================== Filters ==============================
    private val filterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var facetsMemo: FacetsDto? = null

    private fun loadCachedFacets(): FacetsDto? {
        facetsMemo?.let { return it }
        val names = { key: String ->
            preferences.getString(key, null)?.split('\n').orEmpty().filter(String::isNotBlank)
        }
        val genres = names(PREF_FACETS_GENRES_KEY)
        val tags = names(PREF_FACETS_TAGS_KEY)
        val studios = names(PREF_FACETS_STUDIOS_KEY)
        if (genres.isEmpty() && tags.isEmpty() && studios.isEmpty()) return null
        return FacetsDto(
            genres = genres.map { FacetDto(it) },
            tags = tags.map { FacetDto(it) },
            studios = studios.map { FacetDto(it) },
            updatedAt = preferences.getLong(PREF_FACETS_UPDATED_AT, 0L),
        ).also { facetsMemo = it }
    }

    private fun refreshFacetsIfStale() {
        val cached = loadCachedFacets()
        val now = System.currentTimeMillis()
        if (cached != null &&
            now - preferences.getLong(PREF_FACETS_FETCHED_AT, 0L) < FACETS_TTL_MS
        ) {
            return
        }

        filterScope.launch {
            try {
                val res = client.get("$apiUrl/anime/facets", apiHeaders())
                val fresh = res.use { it.parseAs<FacetsDto>() }

                // Server revision gate: rewrite prefs only when facets changed
                if (fresh.updatedAt != cached?.updatedAt) {
                    preferences.edit()
                        .putString(PREF_FACETS_GENRES_KEY, fresh.genres.joinToString("\n") { it.name })
                        .putString(PREF_FACETS_TAGS_KEY, fresh.tags.joinToString("\n") { it.name })
                        .putString(
                            PREF_FACETS_STUDIOS_KEY,
                            fresh.studios.filter { it.count >= STUDIO_MIN_COUNT }
                                .joinToString("\n") { it.name },
                        )
                        .putLong(PREF_FACETS_UPDATED_AT, fresh.updatedAt)
                        .putLong(PREF_FACETS_FETCHED_AT, now)
                        .apply()
                    facetsMemo = fresh
                } else {
                    preferences.edit().putLong(PREF_FACETS_FETCHED_AT, now).apply()
                }
            } catch (_: Exception) {
                // Fetch failed — stale cache keeps serving; retried after next TTL window
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        refreshFacetsIfStale()
        return Filters.build(loadCachedFacets())
    }

    // ============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(seriesUrl(anime.url), apiHeaders("$baseUrl/"))

    override fun animeDetailsParse(response: Response): SAnime {
        val dto = response.parseAs<SeriesResponseDto>()
        dto.routeId?.takeIf(String::isNotBlank)?.let { routeId ->
            val handle = stableMainHandle(dto.source, dto.id, dto.anilistId, dto.routeId)
            if (handle != null) {
                cacheRouteId(handle, routeId)
                cacheRouteId("set-${dto.id}", routeId)
                dto.anilistId?.toLongOrNull()?.let { cacheRouteId("ani-$it", routeId) }
            }
        }
        return dto.toSAnime(baseUrl)
    }

    /** Re-bound entries that 404 to the same anime automatically */
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = resolveSeries(anime.url, anime.title).toSAnime(baseUrl)

    override fun getAnimeUrl(anime: SAnime): String {
        val fresh = cachedHandle(anime.url) ?: anime.url
        val routeId = cachedRouteId(anime.url) ?: cachedRouteId(fresh)
        val path = routeId?.takeIf(String::isNotBlank) ?: cleanHandle(fresh)
        val prefix = if (isAniHandle(fresh)) "ani" else "anime"
        return "$baseUrl/$prefix/$path"
    }

    // ============================ Related ============================
    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val series = resolveSeries(anime.url, anime.title)

        val relationPriority = mapOf(
            "SEQUEL" to 0,
            "PREQUEL" to 1,
            "PARENT" to 2,
            "CHILD" to 3,
            "SIDE_STORY" to 4,
            "ALTERNATIVE" to 5,
            "SPIN_OFF" to 6,
            "OTHER" to 7,
            "CHARACTER" to 8,
            "SUMMARY" to 9,
        )

        val sortedRelations = series.relations.sortedBy { rel ->
            relationPriority[rel.relation?.uppercase()] ?: 10
        }

        val related = sortedRelations.mapNotNull { rel ->
            val handle = rel.toHandle() ?: return@mapNotNull null
            val title = rel.title.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SAnime.create().apply {
                url = handle
                this.title = title
                thumbnail_url = rel.toThumbnailUrl(baseUrl)
            }
        }

        val recommended = fetchRecommendations(series)

        return buildList {
            val seen = HashSet<String>()
            related.forEach { if (seen.add(it.url)) add(it) }
            recommended.forEach { if (seen.add(it.url)) add(it) }
        }
    }

    private suspend fun fetchRecommendations(series: SeriesResponseDto): List<SAnime> = try {
        val context = series.anilistId?.takeIf(String::isNotBlank)?.let { "ani:$it" } ?: "anime:${series.id}"
        val res = client.get(
            "$apiUrl/recommend".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "25")
                .addQueryParameter("context", context)
                .build(),
            apiHeaders(),
        )
        val dto = res.use { it.parseAs<RecommendResponseDto>() }

        dto.items.mapNotNull { it.toSAnime(baseUrl) }
    } catch (_: Exception) {
        emptyList()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val dto = resolveSeries(anime.url, anime.title)
        val handle = stableMainHandle(dto.source, dto.id, dto.anilistId, dto.routeId) ?: anime.url

        val fillerSet = fetchFillerSet(dto.anilistId?.toLongOrNull(), dto.title)

        return dto.episodes.map { ep ->
            SEpisode.create().apply {
                val numStr = fmtNum(ep.number)
                episode_number = ep.number.toFloat()
                url = "$handle/$numStr"
                if (!ep.routeId.isNullOrBlank()) {
                    cacheEpisodeRouteId(handle, numStr, ep.routeId)
                }
                val realTitle = ep.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode ", true) }
                name = buildString {
                    append("Episode $numStr")
                    realTitle?.let { append(" - $it") }
                }
                summary = ep.description
                preview_url = episodePreviewUrl(baseUrl, dto.source, dto.anilistId, dto.routeId, numStr, ep.thumbnail)
                fillermark = ep.number in fillerSet
                scanlator = when {
                    ep.sub == true && ep.dub == true -> "Sub & Dub"
                    ep.sub == true -> "Sub"
                    ep.dub == true -> "Dub"
                    else -> null
                }
                date_upload = dateFormat.tryParse(ep.aired)
            }
        }.reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val (handle, num) = episode.url.split("/").let { it[0] to it.getOrElse(1) { "" } }
        val fresh = cachedHandle(handle) ?: handle
        val routeId = cachedRouteId(handle) ?: cachedRouteId(fresh)
        val path = routeId?.takeIf(String::isNotBlank) ?: cleanHandle(fresh)
        val prefix = if (isAniHandle(fresh)) "ani" else "anime"
        val epRoute = cachedEpisodeRouteId(handle, num) ?: num
        return "$baseUrl/$prefix/$path?ep=$epRoute"
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // ============================ Beta Server =============================
    @Volatile
    private var betaMemo: Pair<Long, BetaServerDto>? = null

    /** Beta server info for a settlar title (keyed by AniList id). */
    private suspend fun fetchBetaServer(anilistId: Long?): BetaServerDto? {
        val aid = anilistId?.takeIf { it in 1..<syntheticAniListBase } ?: return null
        betaMemo?.takeIf { it.first == aid }?.let { return it.second }

        return try {
            val res = client.get("$apiUrl/anime/beta-server/$aid", apiHeaders())
            val dto = res.use { it.parseAs<BetaServerDto>() }
            if (dto.available) betaMemo = aid to dto
            dto.takeIf { it.available }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun BetaServerDto.coversEpisode(anilistId: Long, episode: Double, audio: String?): Boolean {
        val ep = episode.toInt()
        if (episodes.isNotEmpty() && ep !in episodes) return false
        if (audio != null) {
            val langs = episodeLangs?.langsFor(ep) ?: return audio in this.audio
            return audio in langs.first
        }
        return true
    }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val handle = episode.url.substringBefore('/')
        val epNum = episode.url.substringAfterLast('/')
        val dto = resolveSeries(handle)
        val ep = dto.episodes.firstOrNull { fmtNum(it.number) == epNum }
            ?: return emptyList()

        // The bootstrap always rides the settlar provider (the anilist one
        // 404s for every title) and accepts any family handle (slug, set-,
        // ani-), so internalData carries the family-stable handle: /ani/
        // slugs must resolve through the ani endpoint, not /series/ (the
        // numeric tails collide with settlar ids and return wrong titles).
        // Titles with an AniList id bootstrap through the anilist provider with
        // an ani-<id> handle (also the only path that unlocks their backup
        // embed); beta-only titles 404 there, so fetchBootstrap falls back to
        // the settlar slug. Titles without an AniList id use settlar directly.
        val aniHandle = dto.anilistId?.toLongOrNull()
            ?.takeIf { it in 1..<syntheticAniListBase }
            ?.let { "ani-$it" }
        val (bootProvider, bootHandle) = if (aniHandle != null) {
            "anilist" to aniHandle
        } else {
            "settlar" to (dto.routeId?.takeIf(String::isNotBlank) ?: dto.id.toString())
        }

        val bootLang = if (preferredAudio.startsWith("dub")) "dub" else "sub"
        val boot = try {
            fetchBootstrap(bootProvider, bootHandle, epNum, bootLang)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Expected for beta-only titles: no bootstrap exists on any provider
            Log.d(TAG, "Bootstrap unavailable (beta-only titles have none): ${e.message}")
            null
        }
        val bootPackage = boot?.anipmPackages?.episodes?.get(fmtNum(ep.number))
        val backupEmbed = boot?.backupEmbed
        if (backupEmbed != null && !backupEmbed.available) {
            Log.e(TAG, "Backup unavailable: ${backupEmbed.reason} (lang=$bootLang, episode=${backupEmbed.episode})")
        }

        val hasSub = bootPackage?.sub ?: (ep.sub == true)
        val hasSubhard = bootPackage?.subhard ?: (ep.subhard == true)
        val hasDub = bootPackage?.dub ?: (ep.dub == true)
        val hasDubhard = bootPackage?.dubhard ?: (ep.dubhard == true)

        // Beta-only titles play exclusively through the beta server (their
        // bootstraps 404 on every provider), so the main hosters are withheld.
        val betaOnlyTitle = dto.betaOnly == true

        return buildList {
            if (!betaOnlyTitle && hasSub && "[Sub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Sub]", internalData = "anipm::main/$bootProvider/$bootHandle/$epNum/sub"))
            }
            if (!betaOnlyTitle && hasSubhard && "[Hard Sub]" !in excludedAudioTypes) {
                add(Hoster(hosterName = "[Hard Sub]", internalData = "anipm::main/$bootProvider/$bootHandle/$epNum/subhard"))
            }
            if (dubLang == null || dubLang == "eng") {
                // English dubs ride the default settlar flow; Beta is a bonus server
                if (!betaOnlyTitle && hasDub && "[Dub]" !in excludedAudioTypes) {
                    add(Hoster(hosterName = "[Dub]", internalData = "anipm::main/$bootProvider/$bootHandle/$epNum/dub"))
                }
                if (!betaOnlyTitle && hasDubhard && "[Hard Dub]" !in excludedAudioTypes) {
                    add(Hoster(hosterName = "[Hard Dub]", internalData = "anipm::main/$bootProvider/$bootHandle/$epNum/dubhard"))
                }
                val beta = fetchBetaServer(dto.anilistId?.toLongOrNull())
                if (beta != null && beta.coversEpisode(dto.anilistId?.toLongOrNull() ?: 0L, ep.number, null)) {
                    add(Hoster(hosterName = "[Beta]", internalData = "anipm::beta/$bootProvider/$bootHandle/$epNum/dub"))
                }
            } else {
                // Non-English dubs live on the Beta server (audio=<lang>)
                val beta = fetchBetaServer(dto.anilistId?.toLongOrNull())
                if (beta != null && beta.coversEpisode(dto.anilistId?.toLongOrNull() ?: 0L, ep.number, dubLang)) {
                    add(Hoster(hosterName = "[Dub]", internalData = "anipm::beta/$bootProvider/$bootHandle/$epNum/dub"))
                    if ("[Hard Dub]" !in excludedAudioTypes) {
                        add(Hoster(hosterName = "[Hard Dub]", internalData = "anipm::beta/$bootProvider/$bootHandle/$epNum/dubhard"))
                    }
                }
            }
            if ("[Backup]" !in excludedAudioTypes && backupEmbed?.available == true) {
                add(Hoster(hosterName = "[Backup]", internalData = "anipm::backup/$bootProvider/$bootHandle/$epNum/$bootLang"))
            }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ========================== Hoster Sorting ==========================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val (primary, secondary) = when (preferredAudio) {
            "subhard" -> "[Hard Sub]" to "[Sub]"
            "dub" -> "[Dub]" to "[Hard Dub]"
            "dubhard" -> "[Hard Dub]" to "[Dub]"
            "beta" -> "[Beta]" to null
            "backup" -> "[Backup]" to null
            else -> "[Sub]" to "[Hard Sub]"
        }
        return sortedWith(
            compareByDescending<Hoster> { it.hosterName == primary }
                .thenByDescending { secondary != null && it.hosterName == secondary },
        )
    }

    // =============================== Videos ===============================
    private val embedApi = "https://embed.settlar.io"

    private val embedHeaders = headers.newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.removePrefix("anipm::").split("/")
        val kind = parts[0]
        val provider = parts[1]
        val handle = parts[2]
        val epNum = parts[3]
        val channel = parts[4]

        return try {
            val videos = when (kind) {
                "beta" -> extractBeta(handle, epNum, channel)
                "backup" -> extractBackup(provider, handle, epNum, channel)
                else -> extractMain(provider, handle, epNum, channel)
            }

            val qualityRank = PREF_QUALITY_VALUES.reversed()
            val sorted = videos.sortedWith(
                compareByDescending<Video> { it.videoTitle.contains(preferredQuality) }
                    .thenByDescending { video ->
                        qualityRank.indexOfFirst { video.videoTitle.contains(it) }
                    },
            )

            val preferredHoster = when (preferredAudio) {
                "subhard" -> "[Hard Sub]"
                "dub" -> "[Dub]"
                "dubhard" -> "[Hard Dub]"
                "beta" -> "[Beta]"
                "backup" -> "[Backup]"
                else -> "[Sub]"
            }
            val isPreferredHoster = hoster.hosterName == preferredHoster

            sorted.mapIndexed { index, video ->
                video.copy(preferred = index == 0 && isPreferredHoster)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private suspend fun extractMain(provider: String, handle: String, epNum: String, channel: String): List<Video> {
        val bootLang = if (channel.startsWith("dub")) "dub" else "sub"
        val boot = fetchBootstrap(provider, handle, epNum, bootLang)
        val selection = boot.settlarSelection?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val embed = settleSession(selection, epNum, channel)
            ?: return emptyList()

        val manifest = embed.source?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val embedSubs = embed.subtitles.mapNotNull { sub ->
            val subUrl = sub.assUrl?.takeIf(String::isNotBlank) ?: sub.url?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val subLabel = sub.label ?: sub.srclang ?: "Subtitle"
            Track(subUrl, subLabel)
        }

        return playThroughProxy(getProxy(), manifest, embedSubs, buildSkipList(boot.skip))
    }

    private suspend fun extractBeta(id: String, epNum: String, channel: String): List<Video> {
        val dto = resolveSeries(id)
        val aid = dto.anilistId?.toLongOrNull()
            ?: return emptyList()

        val copyUrl = "$apiUrl/anime/beta-server/$aid/copy".toHttpUrl().newBuilder()
            .addQueryParameter("ep", epNum)
            .addQueryParameter("lang", if (channel.startsWith("dub")) "dub" else "sub")
            .apply {
                if (dubLang != null && channel.startsWith("dub")) addQueryParameter("audio", dubLang)
            }
            .build()

        val copy = client.get(copyUrl, apiHeaders()).use { it.parseAs<BetaCopyDto>() }
        val player = copy.player?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val token = player.toHttpUrl().queryParameter("t")
            ?: return emptyList()

        val embed = client.get("$embedApi/api/embed/session?t=$token", embedHeaders)
            .use { it.parseAs<EmbedSessionDto>() }
        val manifest = embed.source?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val boot = try {
            fetchBootstrap("settlar", id, epNum, if (channel.startsWith("dub")) "dub" else "sub")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        val embedSubs = embed.subtitles.mapNotNull { sub ->
            val subUrl = sub.assUrl?.takeIf(String::isNotBlank) ?: sub.url?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val subLabel = sub.label ?: sub.srclang ?: "Subtitle"
            Track(subUrl, subLabel)
        }

        return playThroughProxy(getProxy(), manifest, embedSubs, buildSkipList(boot?.skip))
    }

    private suspend fun extractBackup(provider: String, handle: String, epNum: String, channel: String): List<Video> {
        val bootLang = if (channel.startsWith("dub")) "dub" else "sub"
        val boot = fetchBootstrap(provider, handle, epNum, bootLang)
        val backup = boot.backupEmbed?.takeIf { it.available }
            ?: return emptyList()

        val token = backup.direct?.stream?.takeIf(String::isNotBlank)
            ?.toHttpUrl()
            ?.queryParameter("t")
            ?: run {
                Log.e(TAG, "Backup embed has no stream token (url: " + (backup.url?.take(80) ?: "null") + ")")
                return emptyList()
            }

        val embed = client.get("$embedApi/api/embed/session?t=$token", embedHeaders)
            .use { it.parseAs<EmbedSessionDto>() }
        val manifest = embed.source?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val embedSubs = embed.subtitles.mapNotNull { sub ->
            val subUrl = sub.assUrl?.takeIf(String::isNotBlank) ?: sub.url?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val subLabel = sub.label ?: sub.srclang ?: "Subtitle"
            Track(subUrl, subLabel)
        }

        return playThroughProxy(getProxy(), manifest, embedSubs, buildSkipList(boot.skip))
            .map { it.forceMpegts() }
    }

    private fun Video.forceMpegts(): Video = copy(
        mpvArgs = mpvArgs.filterNot { it.first == "demuxer-lavf-o" } +
            ("demuxer-lavf-o" to "force_mpegts=1"),
        ffmpegStreamArgs = ffmpegStreamArgs.filterNot { it.first == "force_mpegts" } +
            ("force_mpegts" to "1"),
    )

    private suspend fun settleSession(selection: String, epNum: String, channel: String): EmbedSessionDto? {
        val settlar = client.get(
            "$apiUrl/anime/settlar/session".toHttpUrl().newBuilder()
                .addQueryParameter("selection", selection)
                .addQueryParameter("provider", "anipm")
                .addQueryParameter("ep", epNum)
                .addQueryParameter("channel", channel)
                .addQueryParameter("telemetry", "0")
                .build(),
            apiHeaders(),
        ).use { it.parseAs<SettlarSessionDto>() }
        val token = settlar.embedUrl?.toHttpUrl()?.queryParameter("t")
            ?: return null

        return client.get("$embedApi/api/embed/session?t=$token", embedHeaders)
            .use { it.parseAs<EmbedSessionDto>() }
    }

    private fun buildSkipList(skip: SkipDto?) = buildList {
        skip?.op?.takeIf { it.end > it.start }?.let {
            add(TimeStamp(it.start, it.end, name = "Intro", type = ChapterType.Opening))
        }
        skip?.ed?.takeIf { it.end > it.start }?.let {
            add(TimeStamp(it.start, it.end, name = "Outro", type = ChapterType.Ending))
        }
    }

    private fun playThroughProxy(proxy: SettlarProxy, manifest: String, subs: List<Track>, stamps: List<TimeStamp>): List<Video> {
        val vids = playlistUtils.extractFromHls(
            playlistUrl = proxy.proxyUrl(manifest),
            referer = embedApi,
            masterHeaders = headers,
            videoHeaders = headers,
            subtitleList = subs,
        ).map { video ->
            val subtitles = video.subtitleTracks.map { track ->
                track.copy(url = proxy.subtitleUrl(track.url))
            }
            if (subtitles.isNotEmpty()) {
                video.copy(subtitleTracks = subtitles)
            } else {
                video
            }
        }

        return if (stamps.isEmpty()) vids else vids.map { it.copy(timestamps = stamps) }
    }

    // ============================ Series fetch ============================
    private fun isAniHandle(handle: String): Boolean = handle.startsWith("ani-") || handle.startsWith("ani/")

    private fun cleanHandle(handle: String): String = handle.removePrefix("ani-").removePrefix("ani/")
        .removePrefix("set-").removePrefix("series/").removePrefix("anime/")

    private fun seriesUrl(handle: String): String = apiPath(useAniEndpoint = isAniHandle(handle), cleanHandle(handle))

    private fun apiPath(useAniEndpoint: Boolean, key: String): String = "$apiUrl/anime/${if (useAniEndpoint) "ani" else "series"}/$key?routes=e4"

    /**
     * Resolution URLs, primary first. Clean handles (stable ids) dissolve into
     * a single request; legacy handles carry slugs that the site regenerates
     * periodically, so their numeric tail is tried before the slug itself.
     */
    private fun fetchUrls(handle: String): List<String> {
        val clean = cleanHandle(handle)
        val numericTail = clean.substringAfterLast('-').toLongOrNull()?.toString()

        return buildList {
            when {
                isAniHandle(handle) -> {
                    add(apiPath(useAniEndpoint = true, key = clean))
                    numericTail?.let { add(apiPath(useAniEndpoint = true, key = it)) }
                }
                handle.startsWith("set-") -> {
                    add(apiPath(useAniEndpoint = false, key = clean))
                    numericTail?.let { add(apiPath(useAniEndpoint = false, key = it)) }
                }
                else -> {
                    // Legacy raw routeId handles: family unknown, try clean first, then numeric tail
                    add(apiPath(useAniEndpoint = false, key = clean))
                    add(apiPath(useAniEndpoint = true, key = clean))
                    numericTail?.let { add(apiPath(useAniEndpoint = false, key = it)) }
                    numericTail?.let { add(apiPath(useAniEndpoint = true, key = it)) }
                }
            }
        }.distinct()
    }

    private fun matchesHandle(handle: String, dto: SeriesResponseDto, title: String = ""): Boolean {
        val clean = cleanHandle(handle)
        val tail = clean.substringAfterLast('-').toLongOrNull()
        return when {
            isAniHandle(handle) -> dto.anilistId?.toLongOrNull() == tail || dto.routeId == clean
            handle.startsWith("set-") -> dto.id == tail || dto.routeId == clean
            else -> {
                if (dto.routeId == clean) return true
                val matchesTail = tail != null && (dto.id == tail || dto.anilistId?.toLongOrNull() == tail)
                if (!matchesTail) return false
                if (title.isBlank()) return true
                normalizeTitle(dto.title) == normalizeTitle(title)
            }
        }
    }

    private suspend fun tryFetchSeries(handle: String, title: String = ""): Pair<String, SeriesResponseDto>? {
        for (url in fetchUrls(handle)) {
            try {
                client.get(url, apiHeaders()).use { response ->
                    val dto = response.parseAs<SeriesResponseDto>()
                    if (matchesHandle(handle, dto, title)) {
                        val stableHandle = stableMainHandle(dto.source, dto.id, dto.anilistId, dto.routeId)
                            ?: dto.routeId
                            ?: handle
                        dto.routeId?.takeIf(String::isNotBlank)?.let { routeId ->
                            cacheRouteId(handle, routeId)
                            cacheRouteId(stableHandle, routeId)
                            cacheRouteId("set-${dto.id}", routeId)
                            dto.anilistId?.toLongOrNull()?.let { aid ->
                                cacheRouteId("ani-$aid", routeId)
                            }
                        }
                        dto.episodes.forEach { ep ->
                            if (!ep.routeId.isNullOrBlank()) {
                                val numStr = fmtNum(ep.number)
                                cacheEpisodeRouteId(handle, numStr, ep.routeId)
                                cacheEpisodeRouteId(stableHandle, numStr, ep.routeId)
                            }
                        }
                        return stableHandle to dto
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // try the next candidate
            }
        }
        return null
    }

    /**
     * Resolves any stored handle to a series. Library entries may still hold
     * slugs rotated away by the site, so resolution is layered:
     *  1. the re-bound handle cached for this stored handle, if any,
     *  2. the id/slug chain of the stored handle itself,
     *  3. only after every lookup 404s: a single /api/anime/search re-bind
     *     against the entry's title, verified by id or accepted only on one
     *     exact match.
     */
    private suspend fun resolveSeries(handle: String, title: String = ""): SeriesResponseDto {
        val cached = cachedHandle(handle)
        if (cached != null && handleCacheKey(cached) != handleCacheKey(handle)) {
            tryFetchSeries(cached, title)?.let { (resolvedHandle, dto) ->
                cacheHandle(handle, resolvedHandle)
                return dto
            }
        }

        tryFetchSeries(handle, title)?.let { (resolvedHandle, dto) ->
            cacheHandle(handle, resolvedHandle)
            return dto
        }

        if (title.isBlank()) {
            throw IOException("Series not found: $handle")
        }

        val rebound = rescueSeries(handle, title)
            ?: throw IOException("Series not found for '$title' (handle: $handle)")

        cacheHandle(handle, rebound.first)
        return rebound.second
    }

    /**
     * Re-binds a dead handle via /api/anime/search. Prefer items whose ids
     * match the old handle's numeric tail (family-proof); slugs-free handles
     * (e.g. migrated hash URLs) can only be matched by title, so a single
     * exact match is accepted and anything ambiguous is rejected — a wrong
     * re-bind is worse than a failed entry.
     */
    private suspend fun rescueSeries(handle: String, title: String): Pair<String, SeriesResponseDto>? = try {
        val items = client.get(
            "$apiUrl/anime/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", title)
                .build(),
            apiHeaders(),
        ).use { it.parseAs<CatalogResponseDto>().items }

        val wanted = normalizeTitle(title)
        val clean = cleanHandle(handle)
        val tail = clean.substringAfterLast('-').toLongOrNull()
        val exactMatches = items.filter { item ->
            val candidate = normalizeTitle(item.title?.takeIf { it.isNotBlank() } ?: return@filter false)
            candidate == wanted
        }
        val substringMatches = items.filter { item ->
            val candidate = normalizeTitle(item.title?.takeIf { it.isNotBlank() } ?: return@filter false)
            candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
        }
        val verified = substringMatches.filter { item ->
            val tailMatch = tail != null && when {
                isAniHandle(handle) -> item.anilistId?.toLongOrNull() == tail
                handle.startsWith("set-") -> item.id == tail
                else -> item.id == tail || item.anilistId?.toLongOrNull() == tail
            }
            tailMatch || item.routeId == clean
        }
        val bound = when {
            verified.isNotEmpty() -> verified
            tail == null && exactMatches.size == 1 -> exactMatches
            else -> emptyList()
        }
            .firstNotNullOfOrNull { it.toHandle() }
            ?: return null

        tryFetchSeries(bound, title)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    private fun normalizeTitle(value: String): String = value.lowercase()
        .map { char -> if (char.isLetterOrDigit()) char else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ")

    // ============================ RouteId Cache ===========================
    private fun routeIdCacheKey(handle: String): String {
        val prefix = when {
            isAniHandle(handle) -> "ani"
            handle.startsWith("set-") -> "set"
            else -> "raw"
        }
        return "route_id_cache_${prefix}_${cleanHandle(handle)}"
    }

    private fun cachedRouteId(handle: String?): String? = handle?.takeIf(String::isNotBlank)
        ?.let { preferences.getString(routeIdCacheKey(it), null) }
        ?.takeIf(String::isNotBlank)

    private fun cacheRouteId(handle: String, routeId: String) {
        if (handle.isBlank() || routeId.isBlank()) return
        preferences.edit().putString(routeIdCacheKey(handle), routeId).apply()
    }

    // ========================== Episode Route Cache =======================
    private fun episodeRouteIdCacheKey(handle: String, numStr: String): String = "episode_route_${cleanHandle(handle)}_$numStr"

    private fun cachedEpisodeRouteId(handle: String, numStr: String): String? = preferences.getString(episodeRouteIdCacheKey(handle, numStr), null)
        ?.takeIf { it.isNotBlank() }

    private fun cacheEpisodeRouteId(handle: String, numStr: String, episodeRouteId: String) {
        if (handle.isBlank() || numStr.isBlank() || episodeRouteId.isBlank()) return
        preferences.edit().putString(episodeRouteIdCacheKey(handle, numStr), episodeRouteId).apply()
    }

    // ============================ Handle cache ============================

    /**
     * The stored handle (not the site URL) is the identity the app keeps
     * for the entry, so it doubles as the cache key across refreshes.
     */
    private fun handleCacheKey(handle: String): String {
        val prefix = when {
            isAniHandle(handle) -> "ani"
            handle.startsWith("set-") -> "set"
            else -> "raw"
        }
        return "handle_cache_${prefix}_${cleanHandle(handle)}"
    }

    private fun cachedHandle(handle: String?): String? = handle?.takeIf(String::isNotBlank)
        ?.let { preferences.getString(handleCacheKey(it), null) }
        ?.takeIf(String::isNotBlank)

    private fun cacheHandle(oldHandle: String, newHandle: String) {
        if (handleCacheKey(newHandle) == handleCacheKey(oldHandle)) return
        preferences.edit().putString(handleCacheKey(oldHandle), newHandle).apply()
    }

    /**
     * The anilist provider 404s for beta-only titles, in which case the
     * settlar provider with the route slug still resolves.
     */
    private suspend fun fetchBootstrap(provider: String, id: String, epNum: String, lang: String): BootstrapDto = try {
        bootstrapRequest(provider, id, epNum, lang)
    } catch (e: Exception) {
        if (e is CancellationException || provider == "settlar") throw e
        val slug = resolveSeries(id).routeId?.takeIf(String::isNotBlank) ?: id
        bootstrapRequest("settlar", slug, epNum, lang)
    }

    private suspend fun bootstrapRequest(provider: String, id: String, epNum: String, lang: String): BootstrapDto {
        val bootLang = if (lang.startsWith("dub")) "dub" else "sub"
        val headers = apiHeaders().newBuilder()
            .apply {
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
            }
            .build()
        return client.get(
            "$apiUrl/anime/playback-bootstrap/$provider/$id?ep=$epNum&lang=$bootLang&backup=1",
            headers,
        ).use { it.parseAs<BootstrapDto>() }
    }

    private suspend fun fetchFillerSet(anilistId: Long?, title: String?): Set<Double> = try {
        val aid = anilistId?.takeIf { it in 1..<syntheticAniListBase } ?: return emptySet()
        val url = "$apiUrl/anime/filler".toHttpUrl().newBuilder()
            .addQueryParameter("anilistId", aid.toString())
            .addQueryParameter("title", title ?: "")
            .build()
        val res = client.get(url, apiHeaders())
        val ranges = res.use { it.parseAs<FillerResponseDto>() }.ranges
        buildSet {
            ranges?.filler.orEmpty().forEach { addRange(it) }
        }
    } catch (_: Exception) {
        emptySet()
    }

    private fun MutableSet<Double>.addRange(range: List<Int>) {
        val start = range.getOrNull(0) ?: return
        val end = range.getOrNull(1) ?: start
        if (end >= start) (start..end).forEach { add(it.toDouble()) }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun fmtNum(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen): Unit = with(screen) {
        addSwitchPreference(
            key = PREF_HIDE_ADULT_KEY,
            title = "Hide 18+ Titles",
            summary = "Filters adult titles out of browse/search results.",
            default = PREF_HIDE_ADULT_DEFAULT,
        )

        addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio",
            entries = PREF_AUDIO_ENTRIES,
            entryValues = PREF_AUDIO_VALUES,
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
        )
        addSetPreference(
            key = PREF_AUDIO_EXCLUDE_KEY,
            title = "Exclude Servers",
            entries = arrayOf("Sub", "Hard Sub", "Dub", "Hard Dub", "Beta", "Backup").toList(),
            entryValues = arrayOf("[Sub]", "[Hard Sub]", "[Dub]", "[Hard Dub]", "[Beta]", "[Backup]").toList(),
            default = PREF_AUDIO_EXCLUDE_DEFAULT,
            summary = "Hide videos of the selected servers.",
        )
    }

    // ============================== Proxy =================================
    @Volatile
    private var proxy: SettlarProxy? = null

    @Synchronized
    private fun getProxy(): SettlarProxy {
        proxy?.takeIf { it.isAlive }?.let { return it }
        proxy?.stop()
        return SettlarProxy(headers).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            proxy = it
        }
    }

    companion object {
        private const val TAG = "AniPM"
        private const val PAGE_SIZE = 30

        private const val PREF_HIDE_ADULT_KEY = "hide_adult"
        private const val PREF_HIDE_ADULT_DEFAULT = true

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private val PREF_AUDIO_ENTRIES = listOf("Sub", "Hard Sub", "Dub", "Hard Dub", "Beta", "Backup")
        private val PREF_AUDIO_VALUES = listOf("sub", "subhard", "dub", "dubhard", "beta", "backup")
        private const val PREF_AUDIO_DEFAULT = "sub"

        private const val PREF_AUDIO_EXCLUDE_KEY = "excluded_audio_types"
        private val PREF_AUDIO_EXCLUDE_DEFAULT = emptySet<String>()

        private const val PREF_FACETS_GENRES_KEY = "facets_genres"
        private const val PREF_FACETS_TAGS_KEY = "facets_tags"
        private const val PREF_FACETS_STUDIOS_KEY = "facets_studios"
        private const val PREF_FACETS_UPDATED_AT = "facets_updated_at"
        private const val PREF_FACETS_FETCHED_AT = "facets_fetched_at"
        private val FACETS_TTL_MS = 24 * 60 * 60 * 1000L

        private const val STUDIO_MIN_COUNT = 3

        fun parseStatus(status: String?): Int {
            val s = status?.lowercase() ?: return SAnime.UNKNOWN
            return when {
                "not" in s || "upcoming" in s -> SAnime.UNKNOWN
                "releasing" in s || "currently" in s -> SAnime.ONGOING
                "finished" in s -> SAnime.COMPLETED
                "cancel" in s -> SAnime.CANCELLED
                "hiatus" in s -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
        }
    }
}
