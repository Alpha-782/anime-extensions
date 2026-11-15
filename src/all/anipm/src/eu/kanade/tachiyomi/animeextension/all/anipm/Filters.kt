package eu.kanade.tachiyomi.animeextension.all.anipm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object Filters {

    // ============================== Selects ===============================
    class SortFilter : AnimeFilter.Select<String>("Sort", SORT_ENTRIES, 1) {
        fun getValue(): String = SORT_VALUES[state]
        companion object {
            private val SORT_ENTRIES = arrayOf("For you", "Trending", "Popular", "Top rated", "MAL rating", "Newest", "A–Z", "Hard Sub", "Hard Dub")
            private val SORT_VALUES = arrayOf("recommended", "trending", "popular", "score", "mal", "newest", "title", "hardsub", "harddub")
        }
    }

    class VersionFilter :
        TriStateValueGroup(
            "Versions",
            listOf("Sub", "Dub", "Hard Sub", "Hard Dub", "Sub Only", "Dub Only", "Raw"),
        ) {
        fun getIncludedVersions(): List<String> = getIncluded().map(VERSION_VALUES::getValue)

        fun getExcludedVersions(): List<String> = getExcluded().map(VERSION_VALUES::getValue)

        companion object {
            private val VERSION_VALUES = mapOf(
                "Sub" to "sub",
                "Dub" to "dub",
                "Hard Sub" to "hardsub",
                "Hard Dub" to "harddub",
                "Sub Only" to "subonly",
                "Dub Only" to "dubonly",
                "Raw" to "raw",
            )
        }
    }

    class ServerFilter : AnimeFilter.Select<String>("Server", SERVER_ENTRIES, 0) {
        fun getValue(): String? = SERVER_VALUES[state]
        companion object {
            private val SERVER_ENTRIES = arrayOf("Any", "Default", "Beta", "On both", "Default only", "Beta only")
            private val SERVER_VALUES = arrayOf(null, "default", "beta", "both", "defaultonly", "betaonly")
        }
    }

    class OrderFilter : AnimeFilter.Select<String>("Order", ORDER_ENTRIES, 0) {
        fun getValue(): String? = ORDER_VALUES[state]
        companion object {
            private val ORDER_ENTRIES = arrayOf("Normal", "Reversed")
            private val ORDER_VALUES = arrayOf(null, "asc")
        }
    }

    class EpisodeRangeFilter : AnimeFilter.Text("Episode range (e.g. 2-12)") {
        fun getValue(): String? = state.trim().takeIf { it.isNotEmpty() }
    }

    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES, 0) {
        fun getValue(): String? = FORMAT_VALUES[state]
        companion object {
            private val FORMAT_ENTRIES = arrayOf("Any", "TV", "Movie", "OVA", "ONA", "Special")
            private val FORMAT_VALUES = arrayOf(null, "TV", "MOVIE", "OVA", "ONA", "SPECIAL")
        }
    }

    class StatusFilter : AnimeFilter.Select<String>("Airing Status", STATUS_ENTRIES, 0) {
        fun getValue(): String? = STATUS_VALUES[state]
        companion object {
            private val STATUS_ENTRIES = arrayOf("Any", "Airing", "Finished", "Upcoming")
            private val STATUS_VALUES = arrayOf(null, "RELEASING", "FINISHED", "NOT_YET_RELEASED")
        }
    }

    class SeasonFilter : AnimeFilter.Select<String>("Season", SEASON_ENTRIES, 0) {
        fun getValue(): String? = SEASON_VALUES[state]
        companion object {
            private val SEASON_ENTRIES = arrayOf("Any", "Winter", "Spring", "Summer", "Fall")
            private val SEASON_VALUES = arrayOf(null, "WINTER", "SPRING", "SUMMER", "FALL")
        }
    }

    class YearFilter : AnimeFilter.Select<String>("Year", YEARS, 0) {
        fun getValue(): String? = if (state == 0) null else YEARS[state]
        companion object {
            private val YEARS: Array<String> = run {
                val nextYear = Calendar.getInstance().get(Calendar.YEAR) + 1
                (arrayOf("Any") + (nextYear downTo 1990).map(Int::toString))
            }
        }
    }

    // ========================= Checkbox groups =========================

    /** Group of TriState boxes: tap cycles ignore → include → exclude (site-native semantics). */
    abstract class TriStateValueGroup(name: String, values: List<String>) : AnimeFilter.Group<TriStateValueGroup.Box>(name, values.map { Box(it) }) {

        class Box(value: String) : TriState(value)

        /** Values the server should require (genre=, tag=, studio=). */
        fun getIncluded(): List<String> = state.filter { it.isIncluded() }.map { it.name }

        /** Values the server should reject (excludeGenre=, excludeTag=, excludeStudio=). */
        fun getExcluded(): List<String> = state.filter { it.isExcluded() }.map { it.name }
    }

    class GenreFilter(values: List<String>) : TriStateValueGroup("Genres", values)
    class TagFilter(values: List<String>) : TriStateValueGroup("Tags", values)
    class StudioFilter(values: List<String>) : TriStateValueGroup("Studios", values)

    fun build(facets: FacetsDto?): AnimeFilterList {
        val genres = facets?.genres.orEmpty().map { it.name }.sortedBy { it }
        val tags = facets?.tags.orEmpty().map { it.name }.sortedBy { it }
        val studios = facets?.studios.orEmpty().map { it.name }.sortedBy { it }

        val hint = AnimeFilter.Header("Facet lists load from the source — reopen filters in a moment")

        return AnimeFilterList(
            SortFilter(),
            VersionFilter(),
            ServerFilter(),
            EpisodeRangeFilter(),
            OrderFilter(),
            if (genres.isEmpty()) hint else GenreFilter(genres),
            if (tags.isEmpty()) hint else TagFilter(tags),
            if (studios.isEmpty()) hint else StudioFilter(studios),
            YearFilter(),
            SeasonFilter(),
            FormatFilter(),
            StatusFilter(),
        )
    }
}
