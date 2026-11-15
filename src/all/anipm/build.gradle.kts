import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AniPM"
    className = "AniPMFactory"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    val sourceLangs = listOf(
        "all", "en", "fr", "zh", "es", "de", "pt", "it", "th", "hi", "ko",
        "ru", "pl", "id", "tr", "ar", "hu", "ta", "te", "vi", "cs", "fi",
        "sv", "no", "nl", "da", "ca", "fil", "el", "uk", "he", "ro", "ms",
        "yue", "jv", "hr", "eu", "gl", "ku", "new", "sk", "af", "ak", "br",
        "ch", "cmn", "cr", "ha", "ml", "fa",
    )

    sourceLangs.forEach { sourceLang ->
        source {
            name = "AniPM"
            lang = sourceLang
            if (sourceLang == "en") id = 3404_1189_3136_5067_168L
            baseUrl = "https://ani.pm"
        }
    }

    dependencies {
        implementation("com.github.komikku-app.nanohttpd:nanohttpd:gradle-upgrade-SNAPSHOT")
        implementation(project(":lib:playlistutils"))
    }
}
