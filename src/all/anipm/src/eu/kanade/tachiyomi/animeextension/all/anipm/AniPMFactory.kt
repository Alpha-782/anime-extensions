package eu.kanade.tachiyomi.animeextension.all.anipm

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class LangConfig(val dubLang: String?, val lang: String)

class AniPMFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = LANGS.map { AniPM(it) }

    companion object {
        private val LANGS = listOf(
            LangConfig(null, "all"),
            LangConfig("eng", "en"),
            LangConfig("fre", "fr"),
            LangConfig("chi", "zh"),
            LangConfig("spa", "es"),
            LangConfig("ger", "de"),
            LangConfig("por", "pt"),
            LangConfig("ita", "it"),
            LangConfig("tha", "th"),
            LangConfig("hin", "hi"),
            LangConfig("kor", "ko"),
            LangConfig("rus", "ru"),
            LangConfig("pol", "pl"),
            LangConfig("ind", "id"),
            LangConfig("tur", "tr"),
            LangConfig("ara", "ar"),
            LangConfig("hun", "hu"),
            LangConfig("tam", "ta"),
            LangConfig("tel", "te"),
            LangConfig("vie", "vi"),
            LangConfig("cze", "cs"),
            LangConfig("fin", "fi"),
            LangConfig("swe", "sv"),
            LangConfig("nor", "no"),
            LangConfig("dut", "nl"),
            LangConfig("dan", "da"),
            LangConfig("cat", "ca"),
            LangConfig("fil", "fil"),
            LangConfig("gre", "el"),
            LangConfig("ukr", "uk"),
            LangConfig("heb", "he"),
            LangConfig("rum", "ro"),
            LangConfig("may", "ms"),
            LangConfig("yue", "yue"),
            LangConfig("jav", "jv"),
            LangConfig("hrv", "hr"),
            LangConfig("baq", "eu"),
            LangConfig("glg", "gl"),
            LangConfig("kur", "ku"),
            LangConfig("new", "new"),
            LangConfig("slo", "sk"),
            LangConfig("afr", "af"),
            LangConfig("aka", "ak"),
            LangConfig("bre", "br"),
            LangConfig("cha", "ch"),
            LangConfig("cmn", "cmn"),
            LangConfig("cre", "cr"),
            LangConfig("hau", "ha"),
            LangConfig("mal", "ml"),
            LangConfig("per", "fa"),
        )
    }
}
