package aniyomi.lib.megaplayextractor

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay stream extractor. Works with the MegaPlay embed pages
 * (e.g. `https://megaplay.buzz/stream/s-2/131394/sub` or
 * `https://vidtube.site/stream/...`) which share the same
 * `/stream/getSources` API, AES-encrypted payload and token scheme.
 */
class MegaPlayExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    /**
     * Extracts the videos of a MegaPlay stream page. Returns the videos of
     * all HLS variants with subtitle tracks and skip timestamps applied.
     *
     * @param embedUrl the MegaPlay stream page URL
     * @param headers base headers of the source the embed is played on
     * @param referer the site URL the embed is opened from
     * @param videoNameGen receives the HLS quality label of each variant
     */
    suspend fun videosFromUrl(
        embedUrl: String,
        headers: Headers,
        referer: String,
        videoNameGen: (String) -> String = { quality -> quality },
    ): List<Video> {
        val pageHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .build()

        val pageBody = client.get(embedUrl, pageHeaders)
            .body.string()

        val mediaId = parseMediaId(pageBody)
            ?: throw Exception("Failed to find MegaPlay media ID")

        val getSourcesUrl = buildGetSourcesUrl(embedUrl, mediaId)

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json,*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .build()

        val sourcesDto = client.get(getSourcesUrl, apiHeaders)
            .use { it.parseAs<MegaPlaySourcesDto>() }

        val m3u8 = processSource(sourcesDto.enc, sourcesDto.sources)
            ?: throw Exception("Failed to decrypt/find MegaPlay source")

        val tracks = sourcesDto.tracks
            ?.filter { it.label.isNotBlank() }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val skipTimeStamps = buildList {
            sourcesDto.intro?.takeIf { it.start != 0 || it.end != 0 }?.let {
                add(TimeStamp(it.start.toDouble(), it.end.toDouble(), name = "Intro", type = ChapterType.Opening))
            }
            sourcesDto.outro?.takeIf { it.start != 0 || it.end != 0 }?.let {
                add(TimeStamp(it.start.toDouble(), it.end.toDouble(), name = "Outro", type = ChapterType.Ending))
            }
        }

        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            DEFAULT_HOST
        }

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        val videos = playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = videoNameGen,
            subtitleList = tracks,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return if (skipTimeStamps.isNotEmpty()) {
            videos.map { it.copy(timestamps = skipTimeStamps) }
        } else {
            videos
        }
    }

    private fun parseMediaId(html: String): String? {
        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1)?.trim()

        if (!dataId.isNullOrBlank()) return dataId

        return FILE_ID_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun buildGetSourcesUrl(embedUrl: String, id: String): String {
        val base = try {
            val u = embedUrl.toHttpUrl()
            "${u.scheme}://${u.host}/stream/getSources"
        } catch (_: Exception) {
            "https://$DEFAULT_HOST/stream/getSources"
        }

        val urlBuilder = base.toHttpUrl().newBuilder()
            .addQueryParameter("id", id)

        try {
            val original = embedUrl.toHttpUrl()
            original.queryParameter("s")?.let { urlBuilder.addQueryParameter("s", it) }
        } catch (_: Exception) { }

        return urlBuilder.build().toString()
    }

    private fun processSource(enc: String?, source: String?): String? {
        var m3u8: String? = null
        var wasDecrypted = false

        if (!enc.isNullOrBlank()) {
            try {
                val keyBytes = ByteArray(32)
                val keySrc = AES_KEY.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(keySrc, 0, keyBytes, 0, keySrc.size.coerceAtMost(32))

                val iv = AES_IV.toByteArray(StandardCharsets.UTF_8)

                val encrypted = Base64.decode(
                    enc.replace('-', '+').replace('_', '/'),
                    Base64.DEFAULT,
                )

                if (encrypted.isNotEmpty() && encrypted.size % 16 == 0) {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        IvParameterSpec(iv),
                    )
                    val decrypted = cipher.doFinal(encrypted)
                    val json = String(decrypted, StandardCharsets.UTF_8)

                    val fileMatch = FILE_JSON_REGEX.find(json)
                    if (fileMatch != null) {
                        m3u8 = fileMatch.groupValues[1]
                        wasDecrypted = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MegaPlay AES decrypt failed: ${e.message}")
            }
        }

        if (m3u8.isNullOrBlank()) {
            m3u8 = source
        }

        if (m3u8.isNullOrBlank()) return null

        if (!wasDecrypted || TOKEN_PARAM_REGEX.containsMatchIn(m3u8)) {
            return m3u8
        }

        val match = PATH_KEY_REGEX.find(m3u8) ?: return m3u8

        val pathKey = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val expiry = (System.currentTimeMillis() / 1000) + 90
        val payload = "$expiry|$pathKey"

        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val signatureBytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            val signature = Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_WRAP)
                .trimEnd('=')

            val payloadB64 = Base64.encodeToString(
                payload.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            ).trimEnd('=')

            val token = "$payloadB64.$signature"

            m3u8.toHttpUrl().newBuilder()
                .setQueryParameter("token", token)
                .build()
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "MegaPlay token generation failed: ${e.message}")
            m3u8
        }
    }

    @Serializable
    private class MegaPlaySourcesDto(
        val enc: String? = null,
        @Serializable(with = MegaPlaySourcesFieldSerializer::class)
        val sources: String? = null,
        val tracks: List<MegaPlayTrackDto>? = null,
        val intro: MegaPlaySkipDto? = null,
        val outro: MegaPlaySkipDto? = null,
    )

    @Serializable
    private class MegaPlayTrackDto(
        val file: String,
        val label: String = "",
    )

    @Serializable
    private class MegaPlaySkipDto(
        val start: Int = 0,
        val end: Int = 0,
    )

    /** `sources` can arrive as an object, an array or a bare string. */
    private object MegaPlaySourcesFieldSerializer : KSerializer<String?> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun deserialize(decoder: Decoder): String? = when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonObject -> element["file"]?.jsonPrimitive?.content
            is JsonArray -> element.firstOrNull()?.let {
                when (it) {
                    is JsonObject -> it["file"]?.jsonPrimitive?.content
                    is JsonPrimitive -> it.content
                    else -> null
                }
            }
            is JsonPrimitive -> element.content
        }

        override fun serialize(encoder: Encoder, value: String?): Unit = throw UnsupportedOperationException("Serialization not supported")
    }

    companion object {
        private const val TAG = "MegaPlayExtractor"
        private const val DEFAULT_HOST = "megaplay.buzz"

        private const val AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val AES_IV = "W0;27ToaUpl_P%'c"
        private const val TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

        private val DATA_ID_REGEX = Regex("""data-id=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_ID_REGEX = Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val FILE_JSON_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
        private val TOKEN_PARAM_REGEX = Regex("""[?&]token=""", RegexOption.IGNORE_CASE)
        private val PATH_KEY_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

        /**
         * Returns true when the URL points to a MegaPlay-family stream page,
         * i.e. it can be passed to [videosFromUrl]. Detection is structural
         * (`/stream/` path) rather than host-based, so mirror and backup
         * domains (`megaplay.buzz`, `vidtube.site`, any future host) are
         * covered — they all serve the same page layout and
         * `/stream/getSources` API derived from the URL's own host. Bare
         * m3u8 URLs must be routed to direct-HLS handling first.
         */
        fun isMegaPlayUrl(url: String): Boolean = url.contains("/stream/", ignoreCase = true)
    }
}
