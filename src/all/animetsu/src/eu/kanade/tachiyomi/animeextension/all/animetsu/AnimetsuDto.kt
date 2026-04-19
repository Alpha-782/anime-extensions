package eu.kanade.tachiyomi.animeextension.all.animetsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimetsuSearchDto(
    val results: List<AnimetsuAnimeDto>,
    val page: Int,
    val last_page: Int,
    val total: Int,
)

@Serializable
data class AnimetsuAnimeDto(
    val id: String,
    val type: String? = null,
    val title: AnimetsuTitleDto? = null,
    val status: String? = null,
    val isAdult: Boolean = false,
    @SerialName("cover_image") val coverImage: AnimetsuCoverDto? = null,
    val banner: String? = null,
    val description: String? = null,
    @SerialName("total_eps") val totalEps: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val year: Int? = null,
    val format: String? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    @SerialName("average_score") val averageScore: Int? = null,
    val trailer: String? = null,
    val season: String? = null,
    val episodes: List<AnimetsuEpisodeDto>? = null,
)

@Serializable
data class AnimetsuTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnimetsuCoverDto(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

@Serializable
data class AnimetsuEpisodeDto(
    @SerialName("ep_num") val epNum: Int? = null,
    @SerialName("aired_at") val airedAt: String? = null,
    val desc: String? = null,
    @SerialName("is_filler") val isFiller: Boolean? = null,
    val name: String? = null,
    val id: String = "",
)

@Serializable
data class AnimetsuServerDto(
    val id: String,
    val default: Boolean = false,
    val tip: String? = null,
)

@Serializable
data class AnimetsuVideoDto(
    val sources: List<AnimetsuSourceDto>,
    val subs: List<AnimetsuSubDto>? = null,
    val skips: AnimetsuSkipsDto? = null,
    val from: String? = null,
    val server: String? = null,
)

@Serializable
data class AnimetsuSourceDto(
    val quality: String,
    val url: String,
    @SerialName("old_hls") val oldHls: Boolean = false,
    val type: String? = null,
    @SerialName("need_proxy") val needProxy: Boolean = false,
)

@Serializable
data class AnimetsuSubDto(
    val url: String,
    val lang: String? = null,
)

@Serializable
data class AnimetsuSkipsDto(
    val intro: AnimetsuSkipTimeDto? = null,
    val outro: AnimetsuSkipTimeDto? = null,
    @SerialName("ep_num") val epNum: Int? = null,
)

@Serializable
data class AnimetsuSkipTimeDto(
    val start: Double,
    val end: Double,
)
