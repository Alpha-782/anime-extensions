package eu.kanade.tachiyomi.multisrc.anikototheme

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
class ResultResponse(
    private val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
class ServerResponseDto(
    val result: ServerResultDto,
)

@Serializable
class ServerResultDto(
    val url: String,
)

@Serializable
class MapperServerDto(
    val sub: MapperLinkDto? = null,
    val dub: MapperLinkDto? = null,
)

@Serializable
class MapperLinkDto(
    val url: String? = null, // URL can be missing ill let Alpha-782 fix this
)
